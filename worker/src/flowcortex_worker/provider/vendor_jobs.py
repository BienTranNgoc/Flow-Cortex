"""Vendor job identity and reuse: the request fingerprint, the attach decision and the probe port.

A video vendor job is paid for when the vendor accepts it. The backend ledger remembers the jobs
of one ask; this module decides, for one execution, whether a remembered job is the same ask
(fingerprint) and still usable (probe), so the worker waits on it instead of submitting again.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import socket
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable, Mapping, Sequence
from contextlib import suppress
from typing import Any, Literal, NoReturn

from ..errors import PermanentError, TransientError
from .adapter import VendorProbe, VideoIdentity, VideoRequest
from .contracts.provider_job import VendorCandidate

log = logging.getLogger("flowcortex_provider_worker.vendor_jobs")

EndedStatus = Literal["failed", "expired"]
# (vendor job id, stored handle) -> what the vendor says about the job now; raises if it cannot say.
ProbeFn = Callable[[str, dict[str, Any]], VendorProbe]
ProbeRegistry = Mapping[str, ProbeFn]

# A probe answering one of these means the vendor no longer serves the job to us.
PROBE_GONE_STATUSES = frozenset({400, 401, 403, 404, 410})
# A result download answering one of these means the clip is no longer retrievable.
RESULT_GONE_STATUSES = frozenset({403, 404, 410})
_PROBE_TIMEOUT_SECONDS = 30.0
WORKER_USER_AGENT = "FlowCortexWorker/1.0 (compatible; Mozilla/5.0)"
REPLICATE_DOWNLOAD_ATTEMPTS = 8
REPLICATE_HANDSHAKE_TIMEOUT_SECONDS = 8.0
REPLICATE_DIRECT_ATTEMPTS_BEFORE_FALLBACK = 2
_REPLICATE_FALLBACK_ENV = "REPLICATE_DELIVERY_FALLBACK_BASE_URL"


class VendorJobEnded(PermanentError):
    """The vendor ended the job as failed or expired while the worker waited on it."""

    def __init__(self, status: EndedStatus, message: str) -> None:
        super().__init__("provider_request_failed", message)
        self.status: EndedStatus = status


class VendorResultGone(PermanentError):
    """The vendor no longer serves the job (HTTP 404) or its clip (download 403, 404 or 410)."""

    def __init__(self, message: str) -> None:
        super().__init__("provider_request_failed", message)


class VendorJobTimeout(TransientError):
    """The wait budget ended with the vendor job still running. The job stays valid at the vendor.

    On the last delivery the consumer answers `exhausted_code` instead of the generic failure.
    """

    exhausted_code = "provider_timeout"
    exhausted_retryable = True

    def __init__(self, vendor: str, vendor_job_id: str, *, ledger_id: str | None = None) -> None:
        self.vendor = vendor
        self.vendor_job_id = vendor_job_id
        self.ledger_id = ledger_id
        super().__init__(
            f"{vendor} task polling timed out; vendor job {vendor_job_id} is kept"
            + (f" (ledger {ledger_id})" if ledger_id else "")
        )

    def with_ledger(self, ledger_id: str) -> VendorJobTimeout:
        return VendorJobTimeout(self.vendor, self.vendor_job_id, ledger_id=ledger_id)


def canonical_json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def vendor_scope(base_url: str, api_key: str) -> dict[str, str]:
    """Endpoint host and a key hash: task ids are not portable across platforms or accounts.

    The key itself never enters the fingerprint, only the first 16 hex of its SHA-256.
    """
    return {
        "endpoint": (urllib.parse.urlparse(base_url).hostname or "").lower(),
        "account": hashlib.sha256(api_key.encode()).hexdigest()[:16],
    }


def fingerprint_document(
    identity: VideoIdentity, scope: Mapping[str, str], request: VideoRequest
) -> dict[str, Any]:
    primary_image = request.reference_image_bytes or (
        request.reference_images[0] if request.reference_images else None
    )
    extra_images = (
        [hashlib.sha256(img).hexdigest() for img in request.reference_images[1:]]
        if len(request.reference_images) > 1
        else None
    )
    document: dict[str, Any] = {
        "vendor": identity.provider,
        "model": identity.model,
        "capability": "generate_video",
        "profile": request.profile,
        "durationSeconds": identity.duration_seconds,
        "aspectRatio": identity.aspect_ratio,
        "resolution": identity.resolution,
        "generateAudio": identity.generate_audio,
        "scope": dict(scope),
        "prompt": request.prompt,
        "imageSha256": hashlib.sha256(primary_image).hexdigest() if primary_image else None,
        "extraImageSha256s": extra_images,
        "firstFrameSha256": (
            hashlib.sha256(request.first_frame_image_bytes).hexdigest()
            if request.first_frame_image_bytes
            else None
        ),
    }
    return {key: value for key, value in document.items() if value is not None}


def vendor_fingerprint(
    identity: VideoIdentity, scope: Mapping[str, str], request: VideoRequest
) -> str:
    """SHA-256 of the canonical request JSON, independent of run, step, attempt and object keys."""
    return hashlib.sha256(
        canonical_json(fingerprint_document(identity, scope, request)).encode()
    ).hexdigest()


def select_attach(
    candidates: Sequence[VendorCandidate],
    fingerprint: str,
    probes: ProbeRegistry,
    on_verdict: Callable[[VendorCandidate, Literal["failed", "expired", "gone"]], None],
) -> VendorCandidate | None:
    """The first candidate with this fingerprint whose probe reads running or succeeded.

    A dead candidate is reported through `on_verdict` and the next one is tried. A probe that
    cannot answer counts as running: waiting on a possibly live paid job beats paying twice.
    """
    for candidate in candidates:
        if candidate.fingerprint != fingerprint:
            _log("skip_fingerprint", candidate)
            continue
        probe = probes.get(candidate.vendor)
        if probe is None:
            _log("skip_vendor", candidate)
            continue
        if not isinstance(candidate.handle, dict):
            _dead(candidate, "gone", on_verdict)
            continue
        try:
            verdict = probe(candidate.vendorJobId, candidate.handle)
        except Exception as exc:
            log.warning(
                "vendor_job outcome=probe_failed_attach ledger=%s vendor=%s job=%s error=%s",
                candidate.vendorTaskId,
                candidate.vendor,
                candidate.vendorJobId,
                type(exc).__name__,
            )
            return candidate
        if verdict == "running":
            _log("attach", candidate)
            return candidate
        if verdict == "succeeded":
            _log("reuse", candidate)
            return candidate
        _dead(candidate, verdict, on_verdict)
    return None


def _dead(
    candidate: VendorCandidate,
    verdict: Literal["failed", "expired", "gone"],
    on_verdict: Callable[[VendorCandidate, Literal["failed", "expired", "gone"]], None],
) -> None:
    _log(verdict, candidate)
    on_verdict(candidate, verdict)


def _log(outcome: str, candidate: VendorCandidate) -> None:
    log.info(
        "vendor_job outcome=%s ledger=%s vendor=%s job=%s",
        outcome,
        candidate.vendorTaskId,
        candidate.vendor,
        candidate.vendorJobId,
    )


def fetch_status(
    url: str,
    *,
    headers: Mapping[str, str],
    method: str = "GET",
    byte_range: bool = False,
    timeout: float = _PROBE_TIMEOUT_SECONDS,
) -> tuple[int, bytes]:
    """One request returning status and body whatever the status; transport errors raise."""
    request_headers = dict(headers)
    request_headers.setdefault("User-Agent", WORKER_USER_AGENT)
    if byte_range:
        request_headers["Range"] = "bytes=0-0"
    request = urllib.request.Request(url, method=method, headers=request_headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return int(response.status), response.read(1024 * 1024) if method == "GET" else b""
    except urllib.error.HTTPError as exc:
        return exc.code, b""
    except (urllib.error.URLError, TimeoutError) as exc:
        raise TransientError(f"vendor probe failed: {type(exc).__name__}") from exc


def probe_json(
    url: str, headers: Mapping[str, str], timeout: float = _PROBE_TIMEOUT_SECONDS
) -> dict[str, Any] | None:
    """The vendor's JSON status object, or None when it answers that the job is gone."""
    status, body = fetch_status(url, headers=headers, timeout=timeout)
    if status in PROBE_GONE_STATUSES:
        return None
    if not 200 <= status < 300:
        raise TransientError(f"vendor probe answered HTTP {status}")
    try:
        parsed = json.loads(body)
    except json.JSONDecodeError as exc:
        raise TransientError("vendor probe answered a body that is not JSON") from exc
    if not isinstance(parsed, dict):
        raise TransientError("vendor probe answered a body that is not an object")
    return parsed


def probe_media(
    url: str, headers: Mapping[str, str], timeout: float = _PROBE_TIMEOUT_SECONDS
) -> Literal["succeeded", "gone"]:
    """Whether a result URL still answers: HEAD, then a one-byte ranged GET where HEAD is refused.

    Signed result URLs often bind the HTTP method, so a HEAD refusal (403, 405, 501) is retried as
    a ranged GET before the clip is written off.
    """
    status, _ = fetch_status(url, headers=headers, method="HEAD", timeout=timeout)
    if status in (403, 405, 501):
        status, _ = fetch_status(
            url, headers=headers, method="GET", byte_range=True, timeout=timeout
        )
    if 200 <= status < 300:
        return "succeeded"
    if status in PROBE_GONE_STATUSES:
        return "gone"
    raise TransientError(f"vendor result probe answered HTTP {status}")


def read_bounded_response(
    resp: Any,
    *,
    max_bytes: int,
    provider: str,
    task_id: str,
    abort: Any,
    deadline: float,
) -> bytes:
    """Read an HTTP response body while cutting the underlying socket on abort or deadline."""
    raw = getattr(getattr(resp, "fp", None), "raw", None)
    base_sock = getattr(raw, "_sock", None)
    # SSLSocket subclasses socket.socket and raises NotImplementedError on dup(); hold the live
    # socket under a lock and clear it before urlopen's context manager closes the response.
    active_sock: socket.socket | None = (
        base_sock if isinstance(base_sock, socket.socket) else None
    )
    lock = threading.Lock()
    timed_out = False

    def cut() -> None:
        with lock:
            if active_sock is not None:
                with suppress(OSError):
                    active_sock.shutdown(socket.SHUT_RDWR)

    def on_timeout() -> None:
        nonlocal timed_out
        timed_out = True
        cut()

    on_kill = getattr(abort, "on_kill", None)
    if callable(on_kill):
        on_kill(cut)

    remaining = max(0.0, deadline - time.monotonic())
    timer = threading.Timer(remaining, on_timeout)
    timer.daemon = True
    timer.start()
    read_fn = getattr(resp, "read1", None) or resp.read
    chunks = bytearray()
    try:
        while True:
            abort.check()
            if timed_out or time.monotonic() >= deadline:
                raise VendorJobTimeout(provider, task_id)
            chunk = read_fn(65536)
            if not chunk:
                break
            chunks.extend(chunk)
            if len(chunks) > max_bytes:
                return bytes(chunks)
        abort.check()
        if timed_out or time.monotonic() >= deadline:
            raise VendorJobTimeout(provider, task_id)
        expected_len = _expected_content_length(resp)
        if expected_len is not None and len(chunks) < expected_len:
            raise TransientError(
                f"{provider} download truncated: got {len(chunks)} of {expected_len} bytes"
            )
        return bytes(chunks)
    except OSError:
        abort.check()
        if timed_out or time.monotonic() >= deadline:
            raise VendorJobTimeout(provider, task_id) from None
        raise
    finally:
        timer.cancel()
        timer.join()
        with lock:
            active_sock = None


def _expected_content_length(resp: Any) -> int | None:
    headers = getattr(resp, "headers", None)
    raw_len = headers.get("Content-Length") if headers is not None else None
    if raw_len is None:
        return None
    try:
        parsed = int(str(raw_len).strip())
    except ValueError:
        return None
    return parsed if parsed >= 0 else None


def _sleep_with_abort(abort: Any, delay: float) -> None:
    wait_fn = getattr(abort, "wait", None)
    if callable(wait_fn):
        wait_fn(delay)
    else:
        time.sleep(delay)


def _download_replicate_once(
    url: str,
    *,
    provider: str,
    label: str,
    task_id: str,
    max_bytes: int,
    request_timeout_seconds: float,
    handshake_timeout: float,
    abort: Any,
    deadline: float,
    on_http_error: Callable[[urllib.error.HTTPError], NoReturn],
    is_fallback: bool = False,
) -> bytes:
    req = urllib.request.Request(url, method="GET", headers={"User-Agent": WORKER_USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=handshake_timeout) as resp:
            abort.check()
            if time.monotonic() >= deadline:
                raise VendorJobTimeout(provider, task_id)
            raw_sock = getattr(getattr(getattr(resp, "fp", None), "raw", None), "_sock", None)
            if raw_sock is not None and hasattr(raw_sock, "settimeout"):
                body_timeout = min(request_timeout_seconds, max(1.0, deadline - time.monotonic()))
                raw_sock.settimeout(body_timeout)
            return read_bounded_response(
                resp,
                max_bytes=max_bytes,
                provider=provider,
                task_id=task_id,
                abort=abort,
                deadline=deadline,
            )
    except urllib.error.HTTPError as exc:
        try:
            if is_fallback:
                raise TransientError(f"{label} fallback relay HTTP {exc.code}") from exc
            on_http_error(exc)
        finally:
            with suppress(Exception):
                exc.close()
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        raise TransientError(f"{label} download failed: {type(exc).__name__}") from exc


def _build_replicate_fallback_url(original_url: str, fallback_base_url: str) -> str | None:
    base = fallback_base_url.strip().rstrip("/")
    if not base:
        return None
    try:
        parsed_base = urllib.parse.urlparse(base)
        base_host = parsed_base.hostname
        _ = parsed_base.port
    except ValueError:
        return None
    if (
        parsed_base.scheme != "https"
        or not base_host
        or parsed_base.query
        or parsed_base.fragment
        or parsed_base.username is not None
        or parsed_base.password is not None
    ):
        return None
    try:
        orig = urllib.parse.urlparse(original_url)
        host = (orig.hostname or "").lower()
    except ValueError:
        return None
    if not host:
        return None
    base_path = parsed_base.path.rstrip("/")
    orig_path = orig.path if orig.path.startswith("/") else f"/{orig.path}"
    joined_path = f"{base_path}/{host}{orig_path}"
    return urllib.parse.urlunparse(
        (parsed_base.scheme, parsed_base.netloc, joined_path, "", orig.query, "")
    )


def download_replicate_media(
    url: str,
    *,
    provider: str,
    label: str,
    task_id: str,
    max_bytes: int,
    request_timeout_seconds: float,
    abort: Any,
    deadline: float,
    on_http_error: Callable[[urllib.error.HTTPError], NoReturn],
    logger: logging.Logger = log,
    attempts: int = REPLICATE_DOWNLOAD_ATTEMPTS,
    handshake_timeout_seconds: float = REPLICATE_HANDSHAKE_TIMEOUT_SECONDS,
    fallback_base_url: str | None = None,
) -> bytes:
    """Download Replicate CDN media with fail-fast TLS handshake timeout and bounded retries."""
    job_deadline = getattr(abort, "deadline_monotonic", None)
    effective_deadline = (
        min(deadline, float(job_deadline)) if isinstance(job_deadline, (int, float)) else deadline
    )
    resolved_fallback_base = (
        fallback_base_url
        if fallback_base_url is not None
        else os.environ.get(_REPLICATE_FALLBACK_ENV, "")
    )
    fallback_url = _build_replicate_fallback_url(url, resolved_fallback_base)
    for attempt in range(1, attempts + 1):
        abort.check()
        remaining = effective_deadline - time.monotonic()
        if remaining <= 0:
            raise VendorJobTimeout(provider, task_id)
        use_fallback = (
            fallback_url is not None and attempt > REPLICATE_DIRECT_ATTEMPTS_BEFORE_FALLBACK
        )
        target_url = fallback_url if use_fallback and fallback_url is not None else url
        host = (urllib.parse.urlparse(target_url).hostname or "").lower()
        try:
            hs_timeout = min(request_timeout_seconds, handshake_timeout_seconds, remaining)
            return _download_replicate_once(
                target_url,
                provider=provider,
                label=label,
                task_id=task_id,
                max_bytes=max_bytes,
                request_timeout_seconds=request_timeout_seconds,
                handshake_timeout=hs_timeout,
                abort=abort,
                deadline=effective_deadline,
                on_http_error=on_http_error,
                is_fallback=use_fallback,
            )
        except TransientError as exc:
            if attempt == attempts:
                raise
            remaining = effective_deadline - time.monotonic()
            if remaining <= 0:
                raise VendorJobTimeout(provider, task_id) from exc
            logger.warning(
                "%s media download will retry",
                label,
                extra={
                    "downloadAttempt": attempt,
                    "maxAttempts": attempts,
                    "host": host,
                    "usedFallback": use_fallback,
                    "errorType": type(exc.__cause__).__name__,
                },
            )
            delay = min(1.0 if attempt == 1 else 2.0, remaining)
            _sleep_with_abort(abort, delay)
    raise TransientError(f"{label} download failed")  # pragma: no cover
