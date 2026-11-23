"""Suggests where the people are in each scene of a normalised clip; standard library only.

The detector runs as a `python -m` child of the job's process runner, so torch and transformers
are never imported here and a watchdog kill reaches it.
"""

from __future__ import annotations

import json
import math
import sys
from collections.abc import Mapping
from pathlib import Path
from typing import Any

from ..contracts.media_job import MediaJob
from ..engines import EXIT_UNLOADABLE
from ..errors import PermanentError, TransientError
from ..runtime import ProcessRunner
from ..trend_studio.boxes import person_boxes
from ..trend_studio.scenes import Scene, key_frames, validate_scenes
from ..video_analysis.scenes import FPS, MAX_SEC
from ..video_analysis.streams import probe_video, tool_failure
from . import HandlerOutcome
from ._audio_input import last_line

ENGINE_MODULE = "flowcortex_worker.engines.role_suggest"
_FRAME_PATTERN = "role_frame_%03d.jpg"
_REPORT_NAME = "role_boxes.json"
_BOX_LENGTH = 5

Report = list[tuple[int, int, list[list[float]]]]


def _invalid(detail: str) -> PermanentError:
    return PermanentError("provider_response_invalid", f"the person detector's report {detail}")


def _is_number(value: Any) -> bool:
    if isinstance(value, bool) or not isinstance(value, int | float):
        return False
    try:
        return math.isfinite(value)
    except OverflowError:  # an int beyond the float range is not finite either
        return False


def _is_size(value: Any) -> bool:
    return type(value) is int and value > 0


def _count_frames(video_path: Path, stream_index: int, processes: ProcessRunner) -> int:
    """Frames the stream decodes to; ffmpeg exits non-zero when none of the chosen frames exist,
    so a frame past the end of the clip must be told apart before the extraction."""
    completed = processes.run(
        [
            "ffprobe", "-v", "error", "-protocol_whitelist", "file",
            "-count_frames", "-select_streams", str(stream_index),
            "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0", str(video_path),
        ]
    )  # fmt: skip
    if completed.returncode != 0:
        raise tool_failure(
            "ffprobe",
            f"could not count the frames (exit {completed.returncode}): "
            f"{last_line(completed, video_path.parent)}",
        )
    first = completed.stdout.decode("utf-8", "replace").strip().split(",")[0]
    if not first.isdigit() or int(first) <= 0:
        raise tool_failure("ffprobe", f"the clip has no frames (got {first!r})")
    return int(first)


def frame_selection(frame_numbers: list[int]) -> str:
    """A `select` expression true on exactly these frames, summed as a balanced tree.

    ffmpeg 7.1 refuses a flat sum of more than 100 terms, and a role job carries up to 288.
    """
    if len(frame_numbers) == 1:
        return f"eq(n\\,{frame_numbers[0]})"
    middle = len(frame_numbers) // 2
    left, right = frame_numbers[:middle], frame_numbers[middle:]
    return f"({frame_selection(left)}+{frame_selection(right)})"


def _extract_frames(
    video_path: Path, stream_index: int, frame_numbers: list[int], processes: ProcessRunner
) -> list[Path]:
    """One ffmpeg decode that writes the chosen frames beside the clip, in the order given."""
    work = video_path.parent
    selection = frame_selection(frame_numbers)
    completed = processes.run(
        [
            "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
            "-protocol_whitelist", "file", "-i", str(video_path),
            "-map", f"0:{stream_index}", "-vf", f"select='{selection}'",
            "-fps_mode", "passthrough", "-q:v", "2", "-start_number", "0",
            str(work / _FRAME_PATTERN),
        ]
    )  # fmt: skip
    if completed.returncode != 0:
        raise tool_failure(
            "ffmpeg",
            f"extracting the scene frames failed (exit {completed.returncode}): "
            f"{last_line(completed, work)}",
        )
    return [work / (_FRAME_PATTERN % position) for position in range(len(frame_numbers))]


def _run_detector(frames: list[Path], processes: ProcessRunner, work: Path) -> Path:
    """Runs the detector child once for every frame; returns the report it wrote."""
    report = work / _REPORT_NAME
    completed = processes.run(
        [sys.executable, "-m", ENGINE_MODULE, "--output", str(report), *map(str, frames)]
    )
    code = completed.returncode
    if code == 0:
        return report
    if code < 0:
        # A kill the job sent raises ProcessKilled from `run`; any other signal is the system's,
        # typically the out-of-memory killer, and a redelivery can succeed.
        raise TransientError(f"the person detector was ended by signal {-code}")
    reason = last_line(completed, work)
    if code == EXIT_UNLOADABLE:
        raise PermanentError(
            "provider_capability_unavailable", f"the person detector could not be loaded: {reason}"
        )
    raise PermanentError("provider_request_failed", f"the person detector failed: {reason}")


def _read_report(path: Path, count: int) -> Report:
    """The detector's report as (width, height, pixel boxes) per frame; anything else is refused."""
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as failed:
        raise _invalid("could not be read") from failed
    if not isinstance(raw, list) or len(raw) != count:
        raise _invalid(f"must be a list of {count} items, one per frame")
    report: Report = []
    for position, item in enumerate(raw):
        if not isinstance(item, dict):
            raise _invalid(f"item {position} is not an object")
        width: Any = item.get("width")
        height: Any = item.get("height")
        boxes: Any = item.get("boxes")
        if not _is_size(width) or not _is_size(height):
            raise _invalid(f"item {position} has no positive whole width and height")
        if not isinstance(boxes, list) or any(
            not isinstance(box, list)
            or len(box) != _BOX_LENGTH
            or not all(_is_number(number) for number in box)
            for box in boxes
        ):
            raise _invalid(f"item {position} must hold boxes of {_BOX_LENGTH} finite numbers")
        report.append((width, height, boxes))
    return report


def _suggestions(scenes: list[Scene], report: Report) -> list[dict[str, Any]]:
    return [
        {"scene": scene.index, "boxes": person_boxes(boxes, width, height)}
        for scene, (width, height, boxes) in zip(scenes, report, strict=True)
    ]


def handle(
    job: MediaJob,
    inputs: Mapping[str, Path],
    processes: ProcessRunner,
) -> HandlerOutcome:
    if set(inputs) != {"video"}:
        raise PermanentError(
            "contract_violation",
            f"role_suggest needs exactly one input with key 'video' (got {sorted(inputs)})",
        )
    scenes = validate_scenes(job.params.get("scenes"))

    video_path = inputs["video"]
    duration_ms, stream = probe_video(video_path, processes, require_rate=True)
    if stream is None:
        raise tool_failure("ffprobe", "the input has no moving picture")
    if duration_ms > MAX_SEC * 1000:
        raise PermanentError(
            "duration_exceeded", f"the clip lasts {duration_ms} ms, over {MAX_SEC:g} s"
        )
    if stream.rate != (FPS, 1):
        shown = f"{stream.rate[0]}/{stream.rate[1]}" if stream.rate else "unknown"
        raise PermanentError(
            "contract_violation", f"the clip runs at {shown} fps, not the normalised {FPS}/1"
        )

    numbers = key_frames(scenes)
    total = _count_frames(video_path, stream.index, processes)
    for scene, number in zip(scenes, numbers, strict=True):
        if number >= total:
            raise PermanentError(
                "contract_violation",
                f"scene {scene.index}: frame {number} lies past the end of the clip "
                f"({total} frames)",
            )
    frames = _extract_frames(video_path, stream.index, numbers, processes)
    for scene, frame in zip(scenes, frames, strict=True):
        if not frame.is_file():
            raise tool_failure("ffmpeg", f"no frame was written for scene {scene.index}")

    work = video_path.parent
    report = _read_report(_run_detector(frames, processes, work), len(scenes))
    return HandlerOutcome(data={"suggestions": _suggestions(scenes, report)}, files=[])
