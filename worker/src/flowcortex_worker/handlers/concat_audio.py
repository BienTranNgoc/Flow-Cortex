"""`concat_audio`: joins `audio_1 … audio_N` into one MP3 with the ffmpeg concat filter."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from pathlib import Path

from ..assembly.inputs import numbered
from ..contracts.media_job import MediaJob
from ..runtime import ProcessRunner
from . import HandlerOutcome, OutputFile
from ._assembly_io import (
    ceil_ms,
    input_args,
    input_seconds,
    mask_dir,
    require_audio,
    run_ffmpeg,
)
from ._audio_input import require_within

PREFIX = "audio"
MIN_INPUTS = 2
MAX_INPUTS = 50
LIMIT_SECONDS = 1800
BITRATE = "192k"
OUTPUT_NAME = "audio.mp3"
OUTPUT_MIME = "audio/mpeg"


def handle(
    _job: MediaJob,
    inputs: Mapping[str, Path],
    processes: ProcessRunner,
) -> HandlerOutcome:
    ordered, _ = numbered(inputs, PREFIX, MIN_INPUTS, MAX_INPUTS)

    total_seconds = 0.0
    for number, path in enumerate(ordered, start=1):
        key = f"{PREFIX}_{number}"
        require_audio(key, path, processes)
        total_seconds += input_seconds(key, path, processes, "audio_extraction_failed")
    duration_ms = ceil_ms(total_seconds)
    require_within(duration_ms, LIMIT_SECONDS)

    scratch = processes.scratch_dir()
    target = scratch / OUTPUT_NAME
    run_ffmpeg(
        _mix_argv(ordered, target),
        processes,
        "audio_mix_failed",
        "the mix",
        mask_dir(ordered, scratch),
        output=target,
    )
    return HandlerOutcome(
        data={"segmentCount": len(ordered), "durationMs": duration_ms},
        files=[OutputFile(OUTPUT_NAME, OUTPUT_MIME, target)],
    )


def _mix_argv(sources: Sequence[Path], target: Path) -> list[str]:
    argv = ["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y"]
    for source in sources:
        argv += input_args(source)
    streams = "".join(f"[{index}:a]" for index in range(len(sources)))
    argv += [
        "-filter_complex", f"{streams}concat=n={len(sources)}:v=0:a=1[out]",
        "-map", "[out]", "-vn",
        "-c:a", "libmp3lame", "-b:a", BITRATE,
        str(target),
    ]  # fmt: skip
    return argv
