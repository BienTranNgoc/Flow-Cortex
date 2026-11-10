"""The one guarded download of a URL to a local file.

Every request goes to the configured forward proxy (a CONNECT tunnel for https, the absolute form
for http) and never to the target; no redirect is followed; the body is capped and the whole
exchange has a wall-clock deadline. Errors name the scheme and host only: a presigned URL's query
string is a credential, and some exceptions carry the request path.
"""

from __future__ import annotations

import http.client
import ipaddress
import logging
import os
import re
import socket
import ssl
import sys
import threading
import time
from contextlib import suppress
from dataclasses import dataclass
from pathlib import Path
from types import TracebackType
from typing import Self
from urllib.parse import quote, urlsplit

from .config import FetchSettings
from .errors import PermanentError, TransientError
from .storage import Abort

log = logging.getLogger(__name__)

# One recv, one connect attempt or one TLS handshake; the deadline bounds the whole exchange.
SOCKET_TIMEOUT_SECONDS = 60
CHUNK_BYTES = 64 * 1024

_DISALLOWED_URL_CHARS = re.compile(r"[\x00-\x20\x7f]")
_NON_ASCII = re.compile(r"[^\x00-\x7f]")
_TUNNEL_FAILURE =re.compile(r"^Tunnel connection failed: (\d{3})\b")
_DEFAULT_PORTS = {"http": 80, "https": 443}


@dataclass(frozen=True)
class Target:
    """A checked URL: `host` is IDNA-encoded and without brackets; `request_target` is the
    absolute form for http and the path for https."""

    scheme: str
    host: str
    port: int
    request_target: str
    label: str


@dataclass(frozen=True)
class Fetched:
    bytes: int
    content_type: str | None


def _rejected(message: str) -> PermanentError:
    return PermanentError("input_rejected", message)


def _host_for_url(host: str) -> str:
    return f"[{host}]" if ":" in host else host


def parse_target(url: str) -> Target:
    """Check `url` before any socket opens; refusals never quote it."""
    # urlsplit silently strips tab, CR and LF, so this must look at the raw string.
    if _DISALLOWED_URL_CHARS.search(url):
        raise _rejected("the URL contains whitespace or a control character")
    try:
        parts = urlsplit(url)
        port = parts.port
    except ValueError:
        raise _rejected("the URL is malformed or its port is out of range") from None
    scheme = parts.scheme.lower()
    if scheme not in _DEFAULT_PORTS:
        raise _rejected("only http and https URLs are accepted")
    raw_host = parts.hostname
    if not raw_host:
        raise _rejected("the URL has no host")
    if "@" in parts.netloc:
        raise _rejected("the URL carries credentials")
    if port == 0:
        raise _rejected("the URL port is out of range")
    if ":" in raw_host:
        try:
            host = str(ipaddress.ip_address(raw_host))
        except ValueError:
            raise _rejected("the URL host is not a valid address") from None
    else:
        try:
            host = raw_host.encode("idna").decode("ascii")
        except UnicodeError:
            raise _rejected("the URL host cannot be encoded") from None
    effective_port = port if port is not None else _DEFAULT_PORTS[scheme]
    path = (parts.path or "/") + (f"?{parts.query}" if parts.query else "")
    try:
        # The request line is ASCII only; encode as a browser does rather than let http.client
        # raise an unclassified UnicodeEncodeError at send time.
        path = _NON_ASCII.sub(lambda m: quote(m.group(), safe=""), path)
    except UnicodeEncodeError:
        raise _rejected("the URL path or query is not valid text") from None
    if scheme == "http":
        authority = _host_for_url(host) + (f":{port}" if port not in (None, 80) else "")
        request_target = f"{scheme}://{authority}{path}"
    else:
        request_target = path
    return Target(
        scheme=scheme,
        host=host,
        port=effective_port,
        request_target=request_target,
        label=f"{scheme}://{_host_for_url(host)}",
    )


MAX_HINT_DIGITS = 9
MAX_LENGTH_DIGITS = 18


def classify_status(status: int, retry_after: str | None) -> PermanentError | TransientError | None:
    """None for the one success (200); otherwise how the failure is retried."""
    if status == 200:
        return None
    if status in (408, 429) or 500 <= status <= 599:
        hint = retry_after.strip() if retry_after else ""
        # int() refuses digit strings past 4300 characters, and no usable hint is that long.
        usable = hint.isascii() and hint.isdigit() and len(hint) <= MAX_HINT_DIGITS
        seconds = float(int(hint)) if usable else None
        return TransientError(f"the server answered status {status}", retry_after_seconds=seconds)
    if 300 <= status <= 399:
        return _rejected(f"the server answered status {status}: redirects are not followed")
    return _rejected(f"the server answered status {status}")


def _proxy_address(proxy_url: str) -> tuple[str, int]:
    unavailable = PermanentError(
        "provider_capability_unavailable", "no usable forward proxy is configured for URL downloads"
    )
    try:
        parts = urlsplit(proxy_url)
        port = parts.port
    except ValueError:
        raise unavailable from None
    if (
        parts.scheme != "http"
        or not parts.hostname
        or port is None
        or port == 0
        or "@" in parts.netloc
        or parts.path not in ("", "/")
        or parts.query
        or parts.fragment
    ):
        raise unavailable
    return parts.hostname, port


class _CutError(OSError):
    """The exchange was cut (deadline or kill) while a socket was being connected."""


class _Cutter:
    """The deadline of one download and the way to end it early.

    Every connected socket is watched as a dup, so shutdown(2) still reaches it after ssl detached
    the plain socket object, and never lands on a descriptor number reused after a close. A
    shutdown wakes a blocked handshake or recv; the recv then reads as a plain end of stream, which
    is why callers check `expired` and the kill state when a body ends.
    """

    def __init__(self, seconds: float) -> None:
        self._seconds = seconds
        self._end = 0.0
        self._lock = threading.Lock()
        self._watched: list[socket.socket] = []
        self._cut = False
        self._timer = threading.Timer(seconds, self.cut)
        self._timer.daemon = True

    def __enter__(self) -> Self:
        self._end = time.monotonic() + self._seconds
        self._timer.start()
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self._timer.cancel()
        self._timer.join()
        # A kill registered with the job outlives this download: once closed here, a later cut()
        # touches nothing.
        with self._lock:
            self._cut = True
            for watched in self._watched:
                watched.close()
            self._watched.clear()

    @property
    def expired(self) -> bool:
        return time.monotonic() >= self._end

    def cut(self) -> None:
        with self._lock:
            self._cut = True
            for watched in self._watched:
                with suppress(OSError):  # already reset or closed by the peer
                    watched.shutdown(socket.SHUT_RDWR)

    def connect(
        self,
        address: tuple[str, int],
        timeout: float,
        source_address: tuple[str, int] | None = None,
    ) -> socket.socket:
        """`socket.create_connection` as http.client calls it, except that no attempt outlasts the
        deadline and a connected socket is watched."""
        host, port = address
        failure: OSError = OSError("getaddrinfo returned no address")
        for family, kind, proto, _canonical, peer in socket.getaddrinfo(
            host, port, 0, socket.SOCK_STREAM
        ):
            sock: socket.socket | None = None
            try:
                sock = socket.socket(family, kind, proto)
                sock.settimeout(min(timeout, self._left()))
                if source_address:
                    sock.bind(source_address)
                sock.connect(peer)
                sock.settimeout(timeout)
                self._watch(sock)
                return sock
            except _CutError:
                raise
            except OSError as exc:
                if sock is not None:
                    sock.close()
                if self.expired:
                    raise
                failure = exc
        raise failure

    def _left(self) -> float:
        left = self._end - time.monotonic()
        if left <= 0:
            raise _CutError("the deadline has passed")
        return left

    def _watch(self, sock: socket.socket) -> None:
        # Under the lock, a socket connected after the cut is closed instead of handed back: its
        # reads would otherwise block for a full socket timeout.
        with self._lock:
            if self._cut or self.expired:
                sock.close()
                raise _CutError("the download was cut")
            self._watched.append(sock.dup())


def _with_label(
    label: str, error: PermanentError | TransientError
) -> PermanentError | TransientError:
    if isinstance(error, PermanentError):
        return PermanentError(error.code, f"{label}: {error.message}")
    return TransientError(f"{label}: {error}", retry_after_seconds=error.retry_after_seconds)


def _map_failure(label: str, exc: Exception) -> PermanentError | TransientError:
    if isinstance(exc, PermanentError | TransientError):
        return _with_label(label, exc)
    if isinstance(exc, ssl.SSLCertVerificationError | ssl.CertificateError):
        return _rejected(f"{label}: certificate not trusted")
    if isinstance(exc, OSError) and not isinstance(exc, _CutError | TimeoutError):
        match = _TUNNEL_FAILURE.match(str(exc)) if type(exc) is OSError else None
        if match is not None:
            error = classify_status(int(match.group(1)), None)
            if error is not None:
                return _with_label(label, error)
    return TransientError(f"{label}: {type(exc).__name__}")


def download(
    url: str,
    dest: Path,
    *,
    max_bytes: int,
    deadline_seconds: float,
    abort: Abort,
    proxy_url: str | None = None,
) -> Fetched:
    """Copy the body of `url` to the new file `dest` through the forward proxy.

    Raises PermanentError, TransientError or the job's ProcessKilled; `dest` exists only on
    success. A `dest` that already exists raises FileExistsError and is left untouched.
    """
    target = parse_target(url)
    proxy_host, proxy_port = _proxy_address(
        FetchSettings().fetch_proxy_url if proxy_url is None else proxy_url
    )
    if os.path.lexists(dest):
        raise FileExistsError(f"destination exists: {dest.name}")
    abort.check()

    created = False
    try:
        with _Cutter(deadline_seconds) as cutter:
            abort.on_kill(cutter.cut)
            connection: http.client.HTTPConnection
            if target.scheme == "https":
                connection = http.client.HTTPSConnection(
                    proxy_host, proxy_port, timeout=SOCKET_TIMEOUT_SECONDS
                )
                connection.set_tunnel(target.host, target.port)
            else:
                connection = http.client.HTTPConnection(
                    proxy_host, proxy_port, timeout=SOCKET_TIMEOUT_SECONDS
                )
            connection._create_connection = cutter.connect  # type: ignore[attr-defined]
            try:
                try:
                    connection.request(
                        "GET",
                        target.request_target,
                        headers={"User-Agent": "flowcortex-worker", "Accept-Encoding": "identity"},
                    )
                    response = connection.getresponse()
                    refusal = classify_status(response.status, response.getheader("Retry-After"))
                    if refusal is not None:
                        raise refusal
                    declared = _declared_length(response.getheader("Content-Length"))
                    if declared is not None and declared > max_bytes:
                        raise _rejected(
                            f"the body declares {declared} bytes, over the {max_bytes}-byte maximum"
                        )
                    fd = os.open(dest, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                    created = True
                    received = 0
                    with os.fdopen(fd, "wb") as out:
                        while True:
                            abort.check()
                            chunk = response.read1(CHUNK_BYTES)
                            if not chunk:
                                break
                            received += len(chunk)
                            if received > max_bytes:
                                raise _rejected(f"the body grew over the {max_bytes}-byte maximum")
                            out.write(chunk)
                    # A cut body ends as a plain end of stream, so it must be told apart from a
                    # finished one before the length check.
                    abort.check()
                    if cutter.expired:
                        raise _deadline(target.label, deadline_seconds)
                    if declared is not None and received < declared:
                        raise TransientError(f"the body ended after {received} of {declared} bytes")
                except FileExistsError:
                    raise
                except (PermanentError, TransientError, OSError, http.client.HTTPException) as exc:
                    abort.check()
                    if cutter.expired:
                        raise _deadline(target.label, deadline_seconds) from exc
                    raise _map_failure(target.label, exc) from exc
                content_type = response.getheader("Content-Type")
            finally:
                connection.close()
    except BaseException:
        if created:
            with suppress(FileNotFoundError):
                os.unlink(dest)
        raise
    log.info("url downloaded", extra={"source": target.label, "bytes": received})
    return Fetched(bytes=received, content_type=content_type)


def _deadline(label: str, seconds: float) -> PermanentError:
    return _rejected(f"{label}: the download passed its {seconds:g}-second deadline")


def _declared_length(value: str | None) -> int | None:
    text = (value or "").strip()
    if not (text.isascii() and text.isdigit()):
        return None
    # A value this long exceeds every cap, and int() refuses it past 4300 digits.
    return int(text) if len(text) <= MAX_LENGTH_DIGITS else sys.maxsize
