"""The probe every transforming handler starts from: clips, images, windows, output description."""

from __future__ import annotations

import json
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Literal, overload

from ..errors import PermanentError
from ..runtime import ProcessRunner
from ..video_analysis.streams import (
    VideoStream,
    finite_param,
    last_line,
    read_streams,
    tool_failure,
)

# The demuxers ffmpeg reports for an extensionless JPEG, PNG or WebP; anything else (a GIF, an
# animated container, a playlist) is refused so the decode never picks another demuxer.
IMAGE_FORMATS = frozenset({"jpeg_pipe", "png_pipe", "webp_pipe"})
MAX_IMAGE_PIXELS = 25_000_000

_CLIP_ENTRIES = (
    "stream=index,codec_type,width,height,avg_frame_rate,color_space"
    ":stream_disposition=attached_pic:stream_side_data=rotation:format=format_name,duration"
)
_IMAGE_ENTRIES = "stream=index,codec_type,width,height:format=format_name"
_UNTAGGED_COLOR_SPACES = frozenset({"", "unknown"})


@dataclass(frozen=True)
class Clip:
    """What a handler needs to know about a video input before it decodes it."""

    duration_ms: int
    video: VideoStream
    has_audio: bool
    color_tagged: bool


def _streams(report: Mapping[str, Any]) -> list[Mapping[str, Any]]:
    return list(report.get("streams") or [])


def _has_audio(report: Mapping[str, Any]) -> bool:
    return any(s.get("codec_type") == "audio" for s in _streams(report))


def _no_picture(subject: str) -> PermanentError:
    return tool_failure("ffprobe", f"{subject} has no moving picture")


def read_clip(report: Mapping[str, Any]) -> Clip:
    """A video input from its ffprobe report; refuses a container outside the allowlist, a
    missing moving picture and a picture without a frame rate."""
    duration_ms, video = read_streams(report, require_rate=True)
    if video is None:
        raise _no_picture("the input")
    picture = next((s for s in _streams(report) if s.get("index") == video.index), {})
    space = picture.get("color_space")
    tagged = isinstance(space, str) and space not in _UNTAGGED_COLOR_SPACES
    return Clip(duration_ms, video, _has_audio(report), tagged)


def read_image(report: Mapping[str, Any]) -> tuple[str, int, int]:
    """Format, width and height of a still image; refuses other formats and more than 25 MP."""
    name = (report.get("format") or {}).get("format_name")
    if name not in IMAGE_FORMATS:
        raise tool_failure("ffprobe", f"image format {name!r} is not an accepted still image")
    pictures = [
        s
        for s in _streams(report)
        if s.get("codec_type") == "video" and _is_size(s.get("width")) and _is_size(s.get("height"))
    ]
    if not pictures:
        raise _no_picture("the image")
    width, height = pictures[0]["width"], pictures[0]["height"]
    if width * height > MAX_IMAGE_PIXELS:
        raise PermanentError(
            "input_rejected",
            f"the image is {width}x{height} = {width * height} pixels, "
            f"over the {MAX_IMAGE_PIXELS} pixel cap",
        )
    return str(name), width, height


def _is_size(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


def _bound(params: Mapping[str, Any], key: str, unit: Literal["ms", "s"]) -> float | None:
    if key not in params:
        return None
    number = finite_param(params, key)
    if number < 0 or (unit == "ms" and not number.is_integer()):
        kind = "a whole number of milliseconds" if unit == "ms" else "a number of seconds"
        raise PermanentError(
            "contract_violation", f"param {key!r} must be {kind} >= 0 (got {params[key]!r})"
        )
    return number


@overload
def read_window(
    params: Mapping[str, Any], in_key: str, out_key: str, duration_ms: int, unit: Literal["ms"]
) -> tuple[int, int]: ...


@overload
def read_window(
    params: Mapping[str, Any], in_key: str, out_key: str, duration_ms: int, unit: Literal["s"]
) -> tuple[float, float]: ...


def read_window(
    params: Mapping[str, Any], in_key: str, out_key: str, duration_ms: int, unit: Literal["ms", "s"]
) -> tuple[int, int] | tuple[float, float]:
    """The (start, end) of a window in `unit`, whole milliseconds or seconds.

    An absent start is 0 and an absent end the clip's end; an end past the clip is cut to it.
    `finite_param` refuses a missing key, so absence is checked before it is called.
    """
    start = _bound(params, in_key, unit)
    end = _bound(params, out_key, unit)
    length = duration_ms if unit == "ms" else duration_ms / 1000
    first = 0.0 if start is None else start
    if end is not None and end <= first:
        raise PermanentError(
            "contract_violation",
            f"param {out_key!r} ({params[out_key]!r}) must be above {in_key!r} ({first:g})",
        )
    if first >= length:
        raise PermanentError(
            "contract_violation",
            f"param {in_key!r} ({first:g}) is at or past the end of the clip ({length:g} {unit})",
        )
    last = length if end is None else min(end, length)
    if unit == "ms":
        return int(first), int(last)
    return first, float(last)


def _probe(path: Path, processes: ProcessRunner, entries: str, subject: str) -> Mapping[str, Any]:
    completed = processes.run(
        [
            "ffprobe", "-v", "error", "-protocol_whitelist", "file",
            "-show_entries", entries, "-of", "json", str(path),
        ]
    )  # fmt: skip
    if completed.returncode != 0:
        raise tool_failure(
            "ffprobe",
            f"could not read the {subject} (exit {completed.returncode}): "
            f"{last_line(completed.stderr)}",
        )
    try:
        report = json.loads(completed.stdout)
    except ValueError:
        raise tool_failure("ffprobe", "its report could not be read") from None
    if not isinstance(report, dict):
        raise tool_failure("ffprobe", "its report could not be read")
    return report


def probe_clip(path: Path, processes: ProcessRunner) -> Clip:
    return read_clip(_probe(path, processes, _CLIP_ENTRIES, "input"))


def probe_image(path: Path, processes: ProcessRunner) -> tuple[str, int, int]:
    return read_image(_probe(path, processes, _IMAGE_ENTRIES, "input"))


def describe_output(path: Path, processes: ProcessRunner) -> dict[str, Any]:
    """`width`, `height`, `durationMs` and `hasAudio` read back from a produced file."""
    if not path.is_file() or path.stat().st_size == 0:
        raise tool_failure("ffmpeg", f"produced no output ({path.name} is missing or empty)")
    report = _probe(path, processes, _CLIP_ENTRIES, "output")
    duration_ms, video = read_streams(report)
    if video is None:
        raise _no_picture("the produced file")
    return {
        "width": video.width,
        "height": video.height,
        "durationMs": duration_ms,
        "hasAudio": _has_audio(report),
    }
