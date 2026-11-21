"""Vendor job identity and reuse: the request fingerprint, the attach decision and the probe port.

A video vendor job is paid for when the vendor accepts it. The backend ledger remembers the jobs
of one ask; this module decides, for one execution, whether a remembered job is the same ask
(fingerprint) and still usable (probe), so the worker waits on it instead of submitting again.
"""

from __future__ import annotations

import hashlib
import json
import logging
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable, Mapping, Sequence
from typing import Any, Literal

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
        "imageSha256": (
            hashlib.sha256(request.reference_image_bytes).hexdigest()
            if request.reference_image_bytes
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
