"""Mirrors a video left to right and keeps its audio."""

from __future__ import annotations

import logging
from collections.abc import Mapping
from pathlib import Path

from ..contracts.media_job import MediaJob
from ..errors import PermanentError
from ..runtime import ProcessRunner
from ..video_analysis.streams import last_line, tool_failure
from ..video_ops.media import EVEN_SIDES, describe_output, probe_clip
from . import HandlerOutcome, OutputFile

log = logging.getLogger(__name__)

OUTPUT_NAME = "flip.mp4"
OUTPUT_MIME = "video/mp4"
_REPORTED = ("width", "height", "durationMs")


def _encode_argv(video: Path, index: int, output: Path, audio: list[str]) -> list[str]:
    return [
        "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
        "-protocol_whitelist", "file",
        "-i", str(video),
        "-map", f"0:{index}", "-map", "0:a:0?",
        "-vf", f"{EVEN_SIDES},hflip",
        "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p",
        *audio,
        "-movflags", "+faststart",
        str(output),
    ]  # fmt: skip


def handle(
    _job: MediaJob,
    inputs: Mapping[str, Path],
    processes: ProcessRunner,
) -> HandlerOutcome:
    if set(inputs) != {"video"}:
        raise PermanentError(
            "contract_violation",
            f"flip needs exactly one input with key 'video' (got {sorted(inputs)})",
        )
    video = inputs["video"]
    clip = probe_clip(video, processes)
    output = video.parent / OUTPUT_NAME

    copied = processes.run(_encode_argv(video, clip.video.index, output, ["-c:a", "copy"]))
    if copied.returncode != 0:
        # An audio codec that cannot be remuxed into MP4 is the usual cause; AAC always can.
        log.warning(
            "the audio copy failed, re-encoding it as AAC",
            extra={"exitCode": copied.returncode, "error": last_line(copied.stderr)},
        )
        encoded = processes.run(
            _encode_argv(video, clip.video.index, output, ["-c:a", "aac", "-b:a", "128k"])
        )
        if encoded.returncode != 0:
            raise tool_failure(
                "ffmpeg",
                f"could not flip the input (exit {encoded.returncode}): "
                f"{last_line(encoded.stderr)}",
            )

    described = describe_output(output, processes)
    data = {key: described[key] for key in _REPORTED}
    return HandlerOutcome(
        data={**data, "flip": "hflip"}, files=[OutputFile(OUTPUT_NAME, OUTPUT_MIME, output)]
    )
