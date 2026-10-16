"""Composes a generated vertical clip with approved speech for Product Video."""

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
MIN_DURATION_SECONDS = 15
MAX_DURATION_SECONDS = 30
WIDTH = 360
HEIGHT = 640
MAX_IMAGES = 10


def handle(
    job: MediaJob,
    inputs: Mapping[str, Path],
    processes: ProcessRunner,
) -> HandlerOutcome:
    image_count, speech, generated_video = _validate_inputs(job, inputs)
    if not speech.is_file() or speech.stat().st_size == 0:
        raise PermanentError("contract_violation", "render_video requires a non-empty speech input")
    if not generated_video.is_file() or generated_video.stat().st_size == 0:
        raise PermanentError(
            "contract_violation", "render_video requires a non-empty generated video input"
        )
    video = speech.parent / VIDEO_NAME
    completed = processes.run(
        [
            "ffmpeg",
            "-nostdin",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-stream_loop",
            "-1",
            "-i",
            str(generated_video),
            "-i",
            str(speech),
            "-filter_complex",
            f"[0:v]scale={WIDTH}:{HEIGHT}:force_original_aspect_ratio=decrease,"
            f"pad={WIDTH}:{HEIGHT}:(ow-iw)/2:(oh-ih)/2:black,setsar=1[v];"
            f"[1:a]apad=whole_dur={MIN_DURATION_SECONDS}[a]",
            "-map",
            "[v]",
            "-map",
            "[a]",
            "-t",
            str(MAX_DURATION_SECONDS),
            "-shortest",
            "-c:v",
            "libx264",
            "-preset",
            "fast",
            "-pix_fmt",
            "yuv420p",
            "-r",
            "30",
            "-c:a",
            "aac",
            "-b:a",
            "128k",
            "-ar",
            "24000",
            "-ac",
            "1",
            "-map_metadata",
            "-1",
            "-movflags",
            "+faststart",
            str(video),
        ]
    )
    if completed.returncode != 0 or not video.is_file() or video.stat().st_size == 0:
        video.unlink(missing_ok=True)
        raise PermanentError(
            "contract_violation",
            "the generated Product Video could not be composed",
        )

    duration_seconds = _verify_video(video, processes)

    manifest = {
        "aspectRatio": "9:16",
        "durationSeconds": duration_seconds,
        "height": HEIGHT,
        "imageCount": image_count,
        "profile": PROFILE,
        "renderer": "generated_clip",
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


def _validate_inputs(job: MediaJob, inputs: Mapping[str, Path]) -> tuple[int, Path, Path]:
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
    expected_keys = {*expected_images, "speech", "generated_video"}
    if not 1 <= len(image_keys) <= MAX_IMAGES:
        raise PermanentError(
            "contract_violation",
            f"render_video requires 1-{MAX_IMAGES} ordered images",
        )
    if image_keys != expected_images or set(keys) != expected_keys:
        raise PermanentError(
            "contract_violation",
            "render_video requires contiguous image inputs, speech and generated_video",
        )
    if set(inputs) != expected_keys:
        raise PermanentError(
            "contract_violation",
            "render_video resolved inputs do not match the declared inputs",
        )
    return len(image_keys), inputs["speech"], inputs["generated_video"]


def _verify_video(video: Path, processes: ProcessRunner) -> float:
    completed = processes.run(
        [
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "stream=codec_type,codec_name,width,height:format=duration",
            "-of",
            "json",
            str(video),
        ]
    )
    if completed.returncode != 0:
        raise PermanentError("contract_violation", "the rendered video failed media verification")
    try:
        probe = json.loads(completed.stdout)
        streams = probe["streams"]
        duration = float(probe["format"]["duration"])
        video_streams = [stream for stream in streams if stream.get("codec_type") == "video"]
        audio_streams = [stream for stream in streams if stream.get("codec_type") == "audio"]
        valid_video = len(video_streams) == 1 and video_streams[0].get("codec_name") == "h264"
        valid_size = valid_video and video_streams[0].get("width") == WIDTH \
            and video_streams[0].get("height") == HEIGHT
        valid_duration = MIN_DURATION_SECONDS - 0.1 <= duration <= MAX_DURATION_SECONDS + 0.1
        if not valid_size or len(audio_streams) != 1 or not valid_duration:
            raise ValueError
    except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
        raise PermanentError(
            "contract_violation", "the rendered video does not meet the Product Video contract"
        ) from exc
    return round(duration, 3)
