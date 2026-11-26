"""`timeline_audio`: lays `clip_1 … clip_N` on one mono MP3 track of an exact length."""

from __future__ import annotations

import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from ..assembly.inputs import numbered
from ..assembly.timeline import timeline_graph
from ..contracts.media_job import MediaJob
from ..errors import PermanentError
from ..runtime import ProcessRunner
from ..video_analysis.streams import finite_param
from . import HandlerOutcome, OutputFile
from ._assembly_io import input_args, mask_dir, require_audio, run_ffmpeg

PREFIX = "clip"
MIN_CLIPS = 1
MAX_CLIPS = 200
MAX_TRACK_SECONDS = 1800
MIN_SPEED_UP = 1.0
MAX_SPEED_UP = 2.0
BITRATE = "192k"
OUTPUT_NAME = "timeline.mp3"
OUTPUT_MIME = "audio/mpeg"

_CLIP_KEY = re.compile(rf"{PREFIX}_[1-9][0-9]*")


@dataclass(frozen=True)
class Placement:
    start: float
    speed_up: float


def handle(
    job: MediaJob,
    inputs: Mapping[str, Path],
    processes: ProcessRunner,
) -> HandlerOutcome:
    ordered, _ = numbered(inputs, PREFIX, MIN_CLIPS, MAX_CLIPS)
    track_sec = _track_seconds(job.params)
    placements = _placements(job.params.get("clips"), track_sec, len(ordered))

    for number, path in enumerate(ordered, start=1):
        require_audio(f"{PREFIX}_{number}", path, processes)

    scratch = processes.scratch_dir()
    target = scratch / OUTPUT_NAME
    run_ffmpeg(
        _mix_argv(ordered, placements, track_sec, target),
        processes,
        "audio_mix_failed",
        "the mix",
        mask_dir(ordered, scratch),
        output=target,
    )
    return HandlerOutcome(
        data={"clipCount": len(ordered), "durationMs": round(track_sec * 1000)},
        files=[OutputFile(OUTPUT_NAME, OUTPUT_MIME, target)],
    )


def _track_seconds(params: Mapping[str, Any]) -> float:
    track_sec = finite_param(params, "trackSec")
    if not 0 < track_sec <= MAX_TRACK_SECONDS:
        raise PermanentError(
            "contract_violation",
            f"param 'trackSec' must be above 0 and at most {MAX_TRACK_SECONDS} (got {track_sec})",
        )
    return track_sec


def _placements(clips: object, track_sec: float, available: int) -> list[Placement]:
    """The placement of each clip, in input-number order, one clip per `clip_<n>` input."""
    if not isinstance(clips, list) or not clips:
        raise PermanentError("contract_violation", "param 'clips' must be a non-empty list")
    by_number: dict[int, Placement] = {}
    for position, clip in enumerate(clips):
        if not isinstance(clip, dict):
            raise PermanentError("contract_violation", f"clips[{position}] must be an object")
        name = clip.get("input")
        if not isinstance(name, str) or not _CLIP_KEY.fullmatch(name):
            raise PermanentError(
                "contract_violation",
                f"clips[{position}] must name a '{PREFIX}_<n>' input (got {name!r})",
            )
        number = int(name.removeprefix(f"{PREFIX}_"))
        if number > available:
            raise PermanentError(
                "contract_violation", f"clips[{position}] names '{name}', which the job lacks"
            )
        if number in by_number:
            raise PermanentError("contract_violation", f"input '{name}' is placed twice")
        by_number[number] = _placement(clip, name, track_sec)
    unplaced = [f"{PREFIX}_{n}" for n in range(1, available + 1) if n not in by_number]
    if unplaced:
        raise PermanentError(
            "contract_violation", f"input '{unplaced[0]}' is not placed by any clip"
        )
    return [by_number[number] for number in range(1, available + 1)]


def _placement(clip: Mapping[str, Any], name: str, track_sec: float) -> Placement:
    start = _number(clip, "start", name)
    if not 0 <= start < track_sec:
        raise PermanentError(
            "contract_violation",
            f"clip '{name}': 'start' must be at least 0 and below trackSec {track_sec:g} "
            f"(got {start:g})",
        )
    speed_up = MIN_SPEED_UP
    if clip.get("speedUp") is not None:
        speed_up = _number(clip, "speedUp", name)
        if not MIN_SPEED_UP <= speed_up <= MAX_SPEED_UP:
            raise PermanentError(
                "contract_violation",
                f"clip '{name}': 'speedUp' must be {MIN_SPEED_UP:g} to {MAX_SPEED_UP:g} "
                f"(got {speed_up})",
            )
    return Placement(start, speed_up)


def _number(clip: Mapping[str, Any], key: str, name: str) -> float:
    try:
        return finite_param(clip, key)
    except PermanentError as failed:
        raise PermanentError("contract_violation", f"clip '{name}': {failed.message}") from failed


def _mix_argv(
    sources: Sequence[Path], placements: Sequence[Placement], track_sec: float, target: Path
) -> list[str]:
    graph = timeline_graph(
        [placement.start for placement in placements],
        track_sec,
        [placement.speed_up for placement in placements],
    )
    argv = ["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y"]
    for source in sources:
        argv += input_args(source)
    argv += [
        "-filter_complex", graph,
        "-map", "[out]", "-vn",
        "-c:a", "libmp3lame", "-b:a", BITRATE,
        str(target),
    ]  # fmt: skip
    return argv
