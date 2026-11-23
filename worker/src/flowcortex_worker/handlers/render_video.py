"""Composes a generated vertical clip with approved speech for Product Video."""

from __future__ import annotations

import json
from collections.abc import Mapping
from pathlib import Path

from ..contracts.media_job import MediaJob
from ..errors import PermanentError
from ..render.document import PROJECT_PROFILES
from ..render.execute import render_project
from ..runtime import ProcessRunner
from . import HandlerOutcome, OutputFile

PROFILE = "vertical_ad_v1"
PROFILE_V2 = "vertical_ad_v2"
VIDEO_NAME = "video.mp4"
MANIFEST_NAME = "manifest.json"
VIDEO_MIME = "video/mp4"
TARGET_DURATION_SECONDS = 10
CLIP_DURATION_SECONDS = 5
WIDTH = 480
HEIGHT = 854
VIDEO_PRESET = "medium"
VIDEO_CRF = "20"
MAX_IMAGES = 10


def handle(
    job: MediaJob,
    inputs: Mapping[str, Path],
    processes: ProcessRunner,
) -> HandlerOutcome:
    profile = job.params.get("profile")
    if profile in PROJECT_PROFILES:
        return render_project(job, inputs, processes)
    if profile == PROFILE:
        return _render_product_video(job, inputs, processes)
    if profile == PROFILE_V2:
        return _render_product_video_v2(job, inputs, processes)
    allowed = ", ".join(repr(name) for name in (PROFILE, PROFILE_V2, *PROJECT_PROFILES))
    raise PermanentError("contract_violation", f"render_video requires profile {allowed}")


def _render_product_video(
    job: MediaJob,
    inputs: Mapping[str, Path],
    processes: ProcessRunner,
) -> HandlerOutcome:
    image_count, speech, generated_video_1, generated_video_2 = _validate_inputs(job, inputs)
    if not speech.is_file() or speech.stat().st_size == 0:
        raise PermanentError("contract_violation", "render_video requires a non-empty speech input")
    for generated_video in (generated_video_1, generated_video_2):
        if not generated_video.is_file() or generated_video.stat().st_size == 0:
            raise PermanentError(
                "contract_violation", "render_video requires two non-empty generated videos"
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
            "-i",
            str(generated_video_1),
            "-i",
            str(generated_video_2),
            "-i",
            str(speech),
            "-filter_complex",
            f"[0:v]scale={WIDTH}:{HEIGHT}:force_original_aspect_ratio=decrease,"
            f"pad={WIDTH}:{HEIGHT}:(ow-iw)/2:(oh-ih)/2:black,setsar=1,"
            f"fps=30,tpad=stop_mode=clone:stop_duration={CLIP_DURATION_SECONDS},"
            f"trim=duration={CLIP_DURATION_SECONDS},setpts=PTS-STARTPTS[v0];"
            f"[1:v]scale={WIDTH}:{HEIGHT}:force_original_aspect_ratio=decrease,"
            f"pad={WIDTH}:{HEIGHT}:(ow-iw)/2:(oh-ih)/2:black,setsar=1,"
            f"fps=30,tpad=stop_mode=clone:stop_duration={CLIP_DURATION_SECONDS},"
            f"trim=duration={CLIP_DURATION_SECONDS},setpts=PTS-STARTPTS[v1];"
            f"[v0][v1]concat=n=2:v=1:a=0[v];"
            f"[2:a]apad=whole_dur={TARGET_DURATION_SECONDS},"
            f"atrim=duration={TARGET_DURATION_SECONDS},asetpts=PTS-STARTPTS[a]",
            "-map",
            "[v]",
            "-map",
            "[a]",
            "-t",
            str(TARGET_DURATION_SECONDS),
            "-c:v",
            "libx264",
            "-preset",
            VIDEO_PRESET,
            "-crf",
            VIDEO_CRF,
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


_OPTIONAL_AUX_KEYS = frozenset({"mc", "background"})


def _validate_inputs(
    job: MediaJob, inputs: Mapping[str, Path]
) -> tuple[int, Path, Path, Path]:
    keys = [ref.key for ref in job.inputs]
    if len(keys) != len(set(keys)):
        raise PermanentError("contract_violation", "render_video input keys must be unique")

    image_keys = [key for key in keys if key.startswith("image_")]
    expected_images = [f"image_{position}" for position in range(1, len(image_keys) + 1)]
    expected_keys = {
        *expected_images,
        "speech",
        "generated_video_1",
        "generated_video_2",
    }
    if not 1 <= len(image_keys) <= MAX_IMAGES:
        raise PermanentError(
            "contract_violation",
            f"render_video requires 1-{MAX_IMAGES} ordered images",
        )
    effective_keys = set(keys) - _OPTIONAL_AUX_KEYS
    if image_keys != expected_images or effective_keys != expected_keys:
        raise PermanentError(
            "contract_violation",
            "render_video requires contiguous images, speech and two generated videos",
        )
    if (set(inputs) - _OPTIONAL_AUX_KEYS) != expected_keys:
        raise PermanentError(
            "contract_violation",
            "render_video resolved inputs do not match the declared inputs",
        )
    return (
        len(image_keys),
        inputs["speech"],
        inputs["generated_video_1"],
        inputs["generated_video_2"],
    )


def _render_product_video_v2(
    job: MediaJob,
    inputs: Mapping[str, Path],
    processes: ProcessRunner,
) -> HandlerOutcome:
    """Renders 1 or 2 vertical 480x854 clips synced to actual speech length (<= 10s)."""
    speech = inputs.get("speech")
    clip1 = inputs.get("generated_video_1")
    clip2 = inputs.get("generated_video_2")
    if speech is None or not speech.is_file() or speech.stat().st_size == 0:
        raise PermanentError("contract_violation", "render_video requires a non-empty speech input")
    if clip1 is None or not clip1.is_file() or clip1.stat().st_size == 0:
        raise PermanentError(
            "contract_violation", "render_video requires at least one non-empty generated video"
        )
    if clip2 is not None and (not clip2.is_file() or clip2.stat().st_size == 0):
        raise PermanentError("contract_violation", "generated_video_2 must be non-empty")

    speech_dur = _probe_audio_duration(speech, processes)
    target_dur = round(min(float(TARGET_DURATION_SECONDS), max(1.0, speech_dur)), 3)
    video = speech.parent / VIDEO_NAME
    if clip2 is not None:
        clip2_dur = round(max(0.5, target_dur - CLIP_DURATION_SECONDS), 3)
        cmd = [
            "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
            "-i", str(clip1), "-i", str(clip2), "-i", str(speech),
            "-filter_complex",
            f"[0:v]scale={WIDTH}:{HEIGHT}:force_original_aspect_ratio=decrease,"
            f"pad={WIDTH}:{HEIGHT}:(ow-iw)/2:(oh-ih)/2:black,setsar=1,"
            f"fps=30,tpad=stop_mode=clone:stop_duration={CLIP_DURATION_SECONDS},"
            f"trim=duration={CLIP_DURATION_SECONDS},setpts=PTS-STARTPTS[v0];"
            f"[1:v]scale={WIDTH}:{HEIGHT}:force_original_aspect_ratio=decrease,"
            f"pad={WIDTH}:{HEIGHT}:(ow-iw)/2:(oh-ih)/2:black,setsar=1,"
            f"fps=30,tpad=stop_mode=clone:stop_duration={clip2_dur},"
            f"trim=duration={clip2_dur},setpts=PTS-STARTPTS[v1];"
            "[v0][v1]concat=n=2:v=1:a=0[v];"
            f"[2:a]atrim=duration={target_dur},asetpts=PTS-STARTPTS[a]",
            "-map", "[v]", "-map", "[a]",
            "-t", str(target_dur),
            "-c:v", "libx264", "-preset", VIDEO_PRESET, "-crf", VIDEO_CRF,
            "-pix_fmt", "yuv420p", "-r", "30",
            "-c:a", "aac", "-b:a", "128k", "-ar", "24000", "-ac", "1",
            "-map_metadata", "-1", "-movflags", "+faststart",
            str(video),
        ]
    else:
        cmd = [
            "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
            "-i", str(clip1), "-i", str(speech),
            "-filter_complex",
            f"[0:v]scale={WIDTH}:{HEIGHT}:force_original_aspect_ratio=decrease,"
            f"pad={WIDTH}:{HEIGHT}:(ow-iw)/2:(oh-ih)/2:black,setsar=1,"
            f"fps=30,tpad=stop_mode=clone:stop_duration={target_dur},"
            f"trim=duration={target_dur},setpts=PTS-STARTPTS[v];"
            f"[1:a]atrim=duration={target_dur},asetpts=PTS-STARTPTS[a]",
            "-map", "[v]", "-map", "[a]",
            "-t", str(target_dur),
            "-c:v", "libx264", "-preset", VIDEO_PRESET, "-crf", VIDEO_CRF,
            "-pix_fmt", "yuv420p", "-r", "30",
            "-c:a", "aac", "-b:a", "128k", "-ar", "24000", "-ac", "1",
            "-map_metadata", "-1", "-movflags", "+faststart",
            str(video),
        ]
    completed = processes.run(cmd)
    if completed.returncode != 0 or not video.is_file() or video.stat().st_size == 0:
        video.unlink(missing_ok=True)
        raise PermanentError(
            "contract_violation", "the generated Product Video could not be composed"
        )
    duration_seconds = _verify_video(video, processes, expected_duration=target_dur)
    image_count = sum(1 for ref in job.inputs if ref.key.startswith(("image_", "product_")))
    manifest = {
        "aspectRatio": "9:16",
        "durationSeconds": duration_seconds,
        "height": HEIGHT,
        "imageCount": image_count,
        "profile": PROFILE_V2,
        "renderer": "generated_clip",
        "width": WIDTH,
    }
    encoded_manifest = json.dumps(
        manifest, ensure_ascii=True, separators=(",", ":"), sort_keys=True
    ).encode()
    return HandlerOutcome(
        data=manifest,
        files=[
            OutputFile(VIDEO_NAME, VIDEO_MIME, video),
            OutputFile(MANIFEST_NAME, "application/json", encoded_manifest),
        ],
    )


def _probe_audio_duration(speech: Path, processes: ProcessRunner) -> float:
    completed = processes.run(
        [
            "ffprobe", "-v", "error",
            "-show_entries", "format=duration",
            "-of", "json",
            str(speech),
        ]
    )
    if completed.returncode != 0:
        raise PermanentError("contract_violation", "speech input could not be probed")
    try:
        return float(json.loads(completed.stdout)["format"]["duration"])
    except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
        raise PermanentError("contract_violation", "speech duration is invalid") from exc


def _verify_video(
    video: Path, processes: ProcessRunner, *, expected_duration: float = TARGET_DURATION_SECONDS
) -> float:
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
        valid_duration = abs(duration - expected_duration) <= 0.15
        if not valid_size or len(audio_streams) != 1 or not valid_duration:
            raise ValueError
    except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
        raise PermanentError(
            "contract_violation", "the rendered video does not meet the Product Video contract"
        ) from exc
    return round(duration, 3)
