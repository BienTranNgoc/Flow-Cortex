"""Turns one still image into an H.264 clip with a silent audio track and an optional slow zoom."""

from __future__ import annotations

import logging
import subprocess
from collections.abc import Mapping
from pathlib import Path

from ..contracts.media_job import MediaJob
from ..errors import PermanentError
from ..runtime import ProcessRunner
from ..video_analysis.streams import last_line, tool_failure
from ..video_ops.media import describe_output, probe_image
from ..video_ops.still import check_params, static_filter, target_size, zoom_filter
from . import HandlerOutcome, OutputFile

log = logging.getLogger(__name__)

OUTPUT_NAME = "still.mp4"
OUTPUT_MIME = "video/mp4"
_REPORTED = ("width", "height", "durationMs")
# A clip that always has an audio stream joins other clips without a missing-track mismatch.
_SILENCE = "anullsrc=channel_layout=stereo:sample_rate=44100"


def _encode_argv(
    image: Path, image_format: str, output: Path, duration: float, fps: int, video_filter: str
) -> list[str]:
    seconds = str(duration)
    return [
        "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
        "-protocol_whitelist", "file",
        "-loop", "1", "-f", image_format, "-i", str(image),
        "-f", "lavfi", "-t", seconds, "-i", _SILENCE,
        "-t", seconds, "-vf", video_filter, "-r", str(fps),
        "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "128k", "-shortest", "-movflags", "+faststart",
        str(output),
    ]  # fmt: skip


def _failure(completed: subprocess.CompletedProcess[bytes]) -> PermanentError:
    return tool_failure(
        "ffmpeg",
        f"could not encode the image (exit {completed.returncode}): {last_line(completed.stderr)}",
    )


def handle(
    job: MediaJob,
    inputs: Mapping[str, Path],
    processes: ProcessRunner,
) -> HandlerOutcome:
    if set(inputs) != {"image"}:
        raise PermanentError(
            "contract_violation",
            f"still needs exactly one input with key 'image' (got {sorted(inputs)})",
        )
    duration, fps, motion = check_params(job.params)
    image = inputs["image"]
    image_format, source_width, source_height = probe_image(image, processes)
    width, height = target_size(source_width, source_height)
    output = image.parent / OUTPUT_NAME

    if motion != "none":
        zoom = zoom_filter(width, height, duration, fps, motion)
        zoomed = processes.run(_encode_argv(image, image_format, output, duration, fps, zoom))
        # zoompan is the fragile filter (old ffmpeg, a very large image): a still clip is better
        # than a failed job for what is only a decoration.
        if zoomed.returncode != 0:
            log.warning(
                "the zoom encode failed, encoding a static clip",
                extra={"exitCode": zoomed.returncode, "error": last_line(zoomed.stderr)},
            )
            motion = "none"
    if motion == "none":
        completed = processes.run(
            _encode_argv(
                image, image_format, output, duration, fps, static_filter(width, height, fps)
            )
        )
        if completed.returncode != 0:
            raise _failure(completed)

    described = describe_output(output, processes)
    data = {key: described[key] for key in _REPORTED}
    return HandlerOutcome(
        data={**data, "motion": motion}, files=[OutputFile(OUTPUT_NAME, OUTPUT_MIME, output)]
    )
