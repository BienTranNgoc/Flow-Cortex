"""Liveness probe for a recorded fal queue request.

Registered only behind FAL_QUEUE_PROBE_ENABLED: no adapter here submits to fal yet, so the probe
exists for the day one does. It never follows a redirect off the hosts it trusts and never sends
the fal key anywhere but queue.fal.run.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable, Mapping, Sequence
from typing import Any

from ..errors import TransientError
from .adapter import VendorProbe

FAL_QUEUE_HOST = "queue.fal.run"
MAX_REDIRECTS = 5
_TIMEOUT_SECONDS = 30.0
_GONE = frozenset({404, 410})
_MEDIA_GONE = frozenset({403, 404, 410})

FetchResponse = tuple[int, Mapping[str, str], bytes]
# (method, url, headers) -> (status, response headers, body); never follows a redirect.
Fetch = Callable[[str, str, Mapping[str, str]], FetchResponse]


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args: Any, **kwargs: Any) -> None:
        return None


_OPENER = urllib.request.build_opener(_NoRedirect)


def urllib_fetch(method: str, url: str, headers: Mapping[str, str]) -> FetchResponse:
    request = urllib.request.Request(url, method=method, headers=dict(headers))
    try:
        with _OPENER.open(request, timeout=_TIMEOUT_SECONDS) as response:
            body = response.read(1024 * 1024) if method == "GET" else b""
            return int(response.status), dict(response.headers.items()), body
    except urllib.error.HTTPError as exc:
        return exc.code, dict(exc.headers.items()) if exc.headers else {}, b""
    except (urllib.error.URLError, TimeoutError) as exc:
        raise TransientError(f"fal probe failed: {type(exc).__name__}") from exc


def probe_fal_queue(
    handle: Mapping[str, Any],
    headers: Mapping[str, str],
    fetch: Fetch,
    media_host_suffixes: Sequence[str],
) -> VendorProbe:
    """running / failed / gone from the status URL; on COMPLETED, succeeded only when the result
    and every media file it names still answer. Raises for anything it cannot classify."""
    status_url = _queue_url(handle.get("statusUrl"))
    response_url = _queue_url(handle.get("responseUrl"))
    if status_url is None or response_url is None:
        return "gone"

    status, _, body = _follow("GET", status_url, headers, fetch, media_host_suffixes=None)
    if status in _GONE:
        return "gone"
    document = _json_object(status, body)
    state = document.get("status")
    if state in ("IN_QUEUE", "IN_PROGRESS"):
        return "running"
    if state in ("FAILED", "ERROR"):
        return "failed"
    if state != "COMPLETED":
        raise TransientError("fal probe read an unknown queue status")
    if document.get("error"):
        return "failed"

    status, _, body = _follow("GET", response_url, headers, fetch, media_host_suffixes=None)
    if status in _MEDIA_GONE:
        return "gone"
    urls = _media_urls(_json_object(status, body))
    if not urls:
        return "gone"
    for url in urls:
        _require_media_host(url, media_host_suffixes)
        status, _, _ = _follow("HEAD", url, {}, fetch, media_host_suffixes=media_host_suffixes)
        if status in _MEDIA_GONE:
            return "gone"
        if not 200 <= status < 300:
            raise TransientError(f"fal media probe answered HTTP {status}")
    return "succeeded"


def _queue_url(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    parsed = urllib.parse.urlparse(value)
    return value if parsed.scheme == "https" and parsed.hostname == FAL_QUEUE_HOST else None


def _follow(
    method: str,
    url: str,
    headers: Mapping[str, str],
    fetch: Fetch,
    *,
    media_host_suffixes: Sequence[str] | None,
) -> FetchResponse:
    """One request with manual redirects: the key goes only to queue.fal.run, and a media
    redirect must stay on the allowlisted hosts."""
    current = url
    for _ in range(MAX_REDIRECTS + 1):
        host = urllib.parse.urlparse(current).hostname
        sent = dict(headers) if host == FAL_QUEUE_HOST else {}
        status, response_headers, body = fetch(method, current, sent)
        if status not in (301, 302, 303, 307, 308):
            return status, response_headers, body
        location = next(
            (v for k, v in response_headers.items() if k.lower() == "location" and v), None
        )
        if location is None:
            raise TransientError("fal probe got a redirect without a location")
        current = urllib.parse.urljoin(current, location)
        parsed = urllib.parse.urlparse(current)
        if parsed.scheme != "https" or not parsed.hostname:
            raise TransientError("fal probe refused a redirect to a non-https URL")
        if media_host_suffixes is None:
            if parsed.hostname != FAL_QUEUE_HOST:
                raise TransientError("fal probe refused a redirect off the queue host")
        else:
            _require_media_host(current, media_host_suffixes)
    raise TransientError("fal probe followed too many redirects")


def _require_media_host(url: str, suffixes: Sequence[str]) -> None:
    parsed = urllib.parse.urlparse(url)
    host = parsed.hostname or ""
    allowed = parsed.scheme == "https" and any(
        host == suffix.lstrip(".") or host.endswith(suffix) for suffix in suffixes if suffix
    )
    if not allowed:
        raise TransientError("fal media URL is outside the allowed media hosts")


def _json_object(status: int, body: bytes) -> dict[str, Any]:
    if not 200 <= status < 300:
        raise TransientError(f"fal probe answered HTTP {status}")
    try:
        parsed = json.loads(body)
    except json.JSONDecodeError as exc:
        raise TransientError("fal probe answered a body that is not JSON") from exc
    if not isinstance(parsed, dict):
        raise TransientError("fal probe answered a body that is not an object")
    return parsed


def _media_urls(value: Any) -> list[str]:
    """Every string under a `url` key of a fal result, depth first."""
    found: list[str] = []
    if isinstance(value, dict):
        for key, item in value.items():
            if key == "url" and isinstance(item, str) and item:
                found.append(item)
            else:
                found.extend(_media_urls(item))
    elif isinstance(value, list):
        for item in value:
            found.extend(_media_urls(item))
    return found
