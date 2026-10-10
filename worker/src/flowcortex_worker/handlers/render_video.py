"""Builds the deterministic vertical-video fixture used by the Product Video skeleton."""

from __future__ import annotations

import json
from collections.abc import Mapping
from pathlib import Path

from ..contracts.media_job import MediaJob
from ..errors import PermanentError
from ..runtime import ProcessRunner
from . import HandlerOutcome, OutputFile

PROFILE = "vertical_ad_v1"
VIDEO_NAME = "video.mp4"
MANIFEST_NAME = "manifest.json"
VIDEO_MIME = "video/mp4"
DURATION_SECONDS = 15
WIDTH = 360
HEIGHT = 640
MAX_IMAGES = 10


def handle(
    job: MediaJob,
    inputs: Mapping[str, Path],
    processes: ProcessRunner,
) -> HandlerOutcome:
    image_count, speech = _validate_inputs(job, inputs)
    video = speech.parent / VIDEO_NAME
    completed = processes.run(
        [
            "ffmpeg",
            "-nostdin",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-fflags",
            "+bitexact",
            "-f",
            "lavfi",
            "-i",
            f"color=c=0x111827:s={WIDTH}x{HEIGHT}:r=1:d={DURATION_SECONDS}",
            "-f",
            "lavfi",
            "-i",
            f"anullsrc=r=16000:cl=mono:d={DURATION_SECONDS}",
            "-map",
            "0:v:0",
            "-map",
            "1:a:0",
            "-t",
            str(DURATION_SECONDS),
            "-c:v",
            "libx264",
            "-preset",
            "ultrafast",
            "-tune",
            "stillimage",
            "-threads",
            "1",
            "-pix_fmt",
            "yuv420p",
            "-r",
            "1",
            "-flags:v",
            "+bitexact",
            "-c:a",
            "aac",
            "-b:a",
            "32k",
            "-ar",
            "16000",
            "-ac",
            "1",
            "-flags:a",
            "+bitexact",
            "-map_metadata",
            "-1",
            "-metadata",
            "creation_time=1970-01-01T00:00:00Z",
            "-movflags",
            "+faststart",
            str(video),
        ]
    )
    if completed.returncode != 0 or not video.is_file() or video.stat().st_size == 0:
        video.unlink(missing_ok=True)
        raise PermanentError(
            "contract_violation",
            "the deterministic slideshow fixture could not be created",
        )

    manifest = {
        "aspectRatio": "9:16",
        "durationSeconds": DURATION_SECONDS,
        "height": HEIGHT,
        "imageCount": image_count,
        "profile": PROFILE,
        "width": WIDTH,
    }
    encoded_manifest = json.dumps(
        manifest,
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    ).encode()
    return HandlerOutcome(
        data=manifest,
        files=[
            OutputFile(VIDEO_NAME, VIDEO_MIME, video),
            OutputFile(MANIFEST_NAME, "application/json", encoded_manifest),
        ],
    )


def _validate_inputs(job: MediaJob, inputs: Mapping[str, Path]) -> tuple[int, Path]:
    if job.params.get("profile") != PROFILE:
        raise PermanentError(
            "contract_violation",
            f"render_video requires profile {PROFILE!r}",
        )

    keys = [ref.key for ref in job.inputs]
    if len(keys) != len(set(keys)):
        raise PermanentError("contract_violation", "render_video input keys must be unique")

    image_keys = [key for key in keys if key.startswith("image_")]
    expected_images = [f"image_{position}" for position in range(1, len(image_keys) + 1)]
    expected_keys = {*expected_images, "speech"}
    if not 1 <= len(image_keys) <= MAX_IMAGES:
        raise PermanentError(
            "contract_violation",
            f"render_video requires 1-{MAX_IMAGES} ordered images",
        )
    if image_keys != expected_images or set(keys) != expected_keys:
        raise PermanentError(
            "contract_violation",
            "render_video requires contiguous image_1..image_N inputs and one speech input",
        )
    if set(inputs) != expected_keys:
        raise PermanentError(
            "contract_violation",
            "render_video resolved inputs do not match the declared inputs",
        )
    return len(image_keys), inputs["speech"]
