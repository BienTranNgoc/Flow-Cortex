"""How yt-dlp's error text maps to a permanent or a transient failure.

The rows are evaluated in order and the first match wins: "Private video. Sign in ..." must reach
the unavailable row before the bot-check row sees "sign in". Messages name the link's scheme and
host only; yt-dlp's text is matched, never echoed.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from .url_fetch import parse_target


@dataclass(frozen=True)
class Failure:
    permanent: bool
    stop_rotating: bool
    message: str


@dataclass(frozen=True)
class _Row:
    pattern: re.Pattern[str]
    permanent: bool
    stop_rotating: bool
    reason: str
    # A platform block is worth a retry only when the proxy's upstream changes address.
    blocked_address: bool = False


def _row(
    pattern: str, permanent: bool, stop_rotating: bool, reason: str, *, blocked: bool = False
) -> _Row:
    return _Row(re.compile(pattern, re.IGNORECASE), permanent, stop_rotating, reason, blocked)


_BLOCKED = "the platform blocked the server's address"

_TABLE: tuple[_Row, ...] = (
    _row(
        r"Tunnel connection failed: (?:403|407)\b|X-Smokescreen-Error|Request Rejected by Proxy",
        True,
        True,
        "the server is not allowed to reach that address",
    ),
    _row(r"larger than max-filesize", True, False, "the file is larger than the allowed maximum"),
    _row(r"\bDRM\b", True, True, "it is protected by DRM"),
    _row(
        r"video is not available|video is unavailable|video unavailable|private video|members-only"
        r"|has been removed|no longer available",
        True,
        False,
        "it was removed, is private or is blocked in this region",
    ),
    _row(
        r"login required|log in to|requires authentication|use --cookies|age-restricted"
        r"|confirm your age",
        True,
        False,
        "it needs a login the server does not have",
    ),
    _row(r"confirm you.{0,3}re not a bot", True, False, _BLOCKED, blocked=True),
    _row(
        r"unsupported url|no video formats found",
        True,
        True,
        "the link is not one the server can download",
    ),
    _row(r"requested format is not available", True, True, "it offers no downloadable format"),
    _row(r"does not pass filter", True, True, "live streams cannot be downloaded"),
    _row(r"HTTP Error 403", True, False, _BLOCKED, blocked=True),
)


def classify(stderr: str, url: str, *, upstream_rotates: bool) -> Failure:
    """The first table row that matches `stderr`; no row is a transient network failure."""
    label = parse_target(url).label
    for row in _TABLE:
        if row.pattern.search(stderr):
            permanent = row.permanent and not (row.blocked_address and upstream_rotates)
            return Failure(permanent, row.stop_rotating, _message(label, row.reason))
    return Failure(False, False, _message(label, "the download failed"))


def _message(label: str, reason: str) -> str:
    return f"The video at {label} cannot be downloaded: {reason}"
