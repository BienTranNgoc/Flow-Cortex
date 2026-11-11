"""ffprobe report to container length and the moving-picture stream the handlers decode."""

from __future__ import annotations

import json
import math
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from ..errors import PermanentError
from ..runtime import ProcessRunner

# Same containers the render core accepts for video assets: the input is saved without an
# extension, so ffmpeg would otherwise pick demuxers such as hls or concat that open other files.
VIDEO_CONTAINERS = frozenset({"mov,mp4,m4a,3gp,3g2,mj2", "matroska,webm"})

_PROBE_ENTRIES = (
    "stream=index,codec_type,width,height,avg_frame_rate"
    ":stream_disposition=attached_pic:stream_side_data=rotation:format=format_name,duration"
)
_STDERR_LIMIT = 300


@dataclass(frozen=True)
class VideoStream:
    """The moving-picture stream a handler decodes, with its display size."""

    index: int
    width: int
    height: int
    rate: tuple[int, int] | None = None

    def _rate(self) -> tuple[int, int]:
        if self.rate is None:
            raise ValueError("the frame rate was not read; probe with require_rate=True")
        return self.rate

    @property
    def fps(self) -> float:
        num, den = self._rate()
        return num / den

    @property
    def one_frame_expr(self) -> str:
        """One frame in seconds as an exact ffmpeg expression."""
        num, den = self._rate()
        return f"{den}/{num}"

    @property
    def one_frame_ms_floor(self) -> int:
        num, den = self._rate()
        return (1000 * den) // num


def ms(seconds: float) -> int:
    """Whole milliseconds, halves rounded up."""
    return math.floor(seconds * 1000 + 0.5)


def finite_float(value: Any) -> float | None:
    """The number `value` spells, or None when it spells none (missing, "N/A", nan)."""
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def finite_param(params: Mapping[str, Any], key: str) -> float:
    """The finite number in job param `key`; a boolean, string, missing or non-finite is refused."""
    value = params.get(key)
    refused = PermanentError(
        "contract_violation", f"param {key!r} must be a finite number (got {value!r})"
    )
    if isinstance(value, bool) or not isinstance(value, int | float):
        raise refused
    try:
        number = float(value)
    except OverflowError:  # an int beyond the float range is not finite either
        raise refused from None
    if not math.isfinite(number):
        raise refused
    return number


def tool_failure(tool: str, detail: str) -> PermanentError:
    return PermanentError("probe_failed", f"{tool}: {detail}")


def last_line(stderr: bytes) -> str:
    """The last non-empty line of a tool's error output, cut to a message-sized length."""
    lines = [
        line.strip() for line in stderr.decode("utf-8", "replace").splitlines() if line.strip()
    ]
    return lines[-1][:_STDERR_LIMIT] if lines else ""


def _positive_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


def _is_moving_picture(stream: Mapping[str, Any]) -> bool:
    disposition = stream.get("disposition") or {}
    return bool(
        stream.get("codec_type") == "video"
        and disposition.get("attached_pic") != 1
        and _positive_int(stream.get("width"))
        and _positive_int(stream.get("height"))
    )


def _quarter_turn(stream: Mapping[str, Any]) -> bool:
    rotations = [
        s["rotation"]
        for s in stream.get("side_data_list") or []
        if isinstance(s.get("rotation"), int | float) and not isinstance(s.get("rotation"), bool)
    ]
    return bool(rotations) and abs(rotations[0]) % 180 == 90


def _frame_rate(stream: Mapping[str, Any]) -> tuple[int, int]:
    raw = str(stream.get("avg_frame_rate"))
    num, _, den = raw.partition("/")
    if num.isdigit() and den.isdigit() and int(num) > 0 and int(den) > 0:
        return int(num), int(den)
    raise tool_failure(
        "ffprobe",
        f"video stream {stream.get('index')} has no average frame rate (avg_frame_rate={raw!r})",
    )


def read_streams(
    report: Mapping[str, Any], require_rate: bool = False
) -> tuple[int, VideoStream | None]:
    """Container length in ms and the first moving picture (None for audio-only input).

    Only a caller that measures per frame needs the frame rate; with `require_rate` a stream
    without a usable one is refused, otherwise it is left unread.
    """
    container = report.get("format") or {}
    name = container.get("format_name")
    if name not in VIDEO_CONTAINERS:
        raise tool_failure(
            "ffprobe", f"container format {name!r} is not an accepted video container"
        )
    duration = finite_float(container.get("duration"))
    if duration is None or duration <= 0:
        raise tool_failure("ffprobe", "found no container duration")
    pictures = [s for s in report.get("streams") or [] if _is_moving_picture(s)]
    if not pictures:
        return ms(duration), None
    stream = pictures[0]
    width, height = stream["width"], stream["height"]
    if _quarter_turn(stream):
        width, height = height, width
    rate = _frame_rate(stream) if require_rate else None
    return ms(duration), VideoStream(stream["index"], width, height, rate)


def probe_video(
    path: Path, processes: ProcessRunner, require_rate: bool = False
) -> tuple[int, VideoStream | None]:
    """Run ffprobe on `path` and read the result with `read_streams`."""
    completed = processes.run(
        [
            "ffprobe", "-v", "error", "-protocol_whitelist", "file",
            "-show_entries", _PROBE_ENTRIES, "-of", "json", str(path),
        ]
    )  # fmt: skip
    if completed.returncode != 0:
        detail = last_line(completed.stderr)
        raise tool_failure(
            "ffprobe", f"could not read the input (exit {completed.returncode}): {detail}"
        )
    try:
        report = json.loads(completed.stdout)
    except ValueError:
        raise tool_failure("ffprobe", "its report could not be read") from None
    if not isinstance(report, dict):
        raise tool_failure("ffprobe", "its report could not be read")
    return read_streams(report, require_rate)
