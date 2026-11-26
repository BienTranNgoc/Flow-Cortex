"""Composes a generated vertical clip with approved speech for Product Video."""

from __future__ import annotations

import json
import logging
from collections.abc import Mapping
from pathlib import Path

from ..contracts.media_job import MediaJob
from ..errors import PermanentError
from ..provider.edge_adapter import extract_audio_mode, extract_mc_end_ms
from ..render.document import PROJECT_PROFILES
from ..render.execute import render_project
from ..runtime import ProcessRunner
from . import HandlerOutcome, OutputFile
from .luxury_bgm import write_luxury_bgm_wav

log = logging.getLogger("flowcortex_worker.render_video")

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
_FASTSTART_FLAG = "+faststart"


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


def _build_single_clip_compose(
    generated_video_1: Path,
    speech: Path,
    bgm: Path,
    audio_mode: str | None,
    target_dur: float,
) -> tuple[list[str], str]:
    fade_dur = 0.35
    fade_start = round(max(0.0, target_dur - fade_dur), 3)
    video_filter = (
        f"[0:v]scale={WIDTH}:{HEIGHT}:force_original_aspect_ratio=decrease,"
        f"pad={WIDTH}:{HEIGHT}:(ow-iw)/2:(oh-ih)/2:black,setsar=1,"
        f"format=yuv420p,tpad=stop_mode=clone:stop_duration={target_dur},"
        f"trim=duration={target_dur},setpts=PTS-STARTPTS,fps=30,"
        f"fade=t=out:st={fade_start}:d={fade_dur}[v];"
    )
    if audio_mode == "music_only":
        audio_filter = (
            f"[2:a]volume=0.82,apad=whole_dur={target_dur},"
            f"atrim=duration={target_dur},asetpts=PTS-STARTPTS,"
            f"afade=t=out:st={fade_start}:d={fade_dur}[a]"
        )
    else:
        audio_filter = (
            f"[1:a]volume=0.92,apad=whole_dur={target_dur},"
            f"atrim=duration={target_dur},asetpts=PTS-STARTPTS[voice];"
            f"[2:a]volume=0.13,apad=whole_dur={target_dur},"
            f"atrim=duration={target_dur},asetpts=PTS-STARTPTS[bgm];"
            "[voice][bgm]amix=inputs=2:duration=first:normalize=0,"
            "alimiter=limit=0.95:attack=5:release=50:level=0,"
            f"afade=t=out:st={fade_start}:d={fade_dur}[a]"
        )
    input_args = ["-i", str(generated_video_1), "-i", str(speech), "-i", str(bgm)]
    return input_args, f"{video_filter}{audio_filter}"


def _build_two_clip_compose(
    generated_video_1: Path,
    generated_video_2: Path,
    speech: Path,
    bgm: Path,
    image_count: int,
    audio_mode: str | None,
) -> tuple[list[str], str]:
    dissolve_dur = 0.55
    if image_count >= 2:
        divider_h = 2
        half_h = (HEIGHT - divider_h) // 2
        scene1_len = 4.3
        scene2_len = 4.45
        split_len = 2.05
        offset_1 = round(scene1_len - dissolve_dur, 3)
        split_fade = 0.25
        offset_2 = round(offset_1 + scene2_len - split_fade, 3)
        video_filter = (
            f"[0:v]scale={WIDTH}:{HEIGHT}:force_original_aspect_ratio=decrease,"
            f"pad={WIDTH}:{HEIGHT}:(ow-iw)/2:(oh-ih)/2:black,setsar=1,"
            f"format=yuv420p,tpad=stop_mode=clone:stop_duration={CLIP_DURATION_SECONDS},"
            f"split=2[s0a][s0b];"
            f"[1:v]scale={WIDTH}:{HEIGHT}:force_original_aspect_ratio=decrease,"
            f"pad={WIDTH}:{HEIGHT}:(ow-iw)/2:(oh-ih)/2:black,setsar=1,"
            f"format=yuv420p,tpad=stop_mode=clone:stop_duration={CLIP_DURATION_SECONDS},"
            f"split=2[s1a][s1b];"
            f"[s0a]trim=duration={scene1_len},setpts=PTS-STARTPTS,fps=30[v0];"
            f"[s1a]trim=duration={scene2_len},setpts=PTS-STARTPTS,fps=30[v1];"
            f"[v0][v1]xfade=transition=dissolve:duration={dissolve_dur}:offset={offset_1}[v01];"
            f"[s0b]trim=start=2.5:duration={split_len},setpts=PTS-STARTPTS,"
            f"crop={WIDTH}:{half_h}:(iw-{WIDTH})/2:(ih-{half_h})/2,fps=30[top];"
            f"[s1b]trim=start=2.5:duration={split_len},setpts=PTS-STARTPTS,"
            f"crop={WIDTH}:{half_h}:(iw-{WIDTH})/2:(ih-{half_h})/2,fps=30[bot];"
            f"color=c=0xF5E6C8:s={WIDTH}x{divider_h}:r=30:d={split_len},"
            f"format=yuv420p,setsar=1,setpts=PTS-STARTPTS,fps=30[div];"
            f"[top][div][bot]vstack=inputs=3,format=yuv420p,setpts=PTS-STARTPTS,fps=30[v2];"
            f"[v01][v2]xfade=transition=dissolve:duration={split_fade}:offset={offset_2}[v];"
        )
    else:
        xfade_offset = round(float(CLIP_DURATION_SECONDS) - dissolve_dur, 3)
        clip2_len = round(float(CLIP_DURATION_SECONDS) + dissolve_dur, 3)
        video_filter = (
            f"[0:v]scale={WIDTH}:{HEIGHT}:force_original_aspect_ratio=decrease,"
            f"pad={WIDTH}:{HEIGHT}:(ow-iw)/2:(oh-ih)/2:black,setsar=1,"
            f"format=yuv420p,tpad=stop_mode=clone:stop_duration={CLIP_DURATION_SECONDS},"
            f"trim=duration={CLIP_DURATION_SECONDS},setpts=PTS-STARTPTS,fps=30[v0];"
            f"[1:v]scale={WIDTH}:{HEIGHT}:force_original_aspect_ratio=decrease,"
            f"pad={WIDTH}:{HEIGHT}:(ow-iw)/2:(oh-ih)/2:black,setsar=1,"
            f"format=yuv420p,tpad=stop_mode=clone:stop_duration={clip2_len},"
            f"trim=duration={clip2_len},setpts=PTS-STARTPTS,fps=30[v1];"
            f"[v0][v1]xfade=transition=dissolve:duration={dissolve_dur}:offset={xfade_offset}[v];"
        )
    if audio_mode == "music_only":
        audio_filter = (
            f"[3:a]volume=0.82,apad=whole_dur={TARGET_DURATION_SECONDS},"
            f"atrim=duration={TARGET_DURATION_SECONDS},asetpts=PTS-STARTPTS[a]"
        )
    else:
        audio_filter = (
            f"[2:a]volume=0.92,apad=whole_dur={TARGET_DURATION_SECONDS},"
            f"atrim=duration={TARGET_DURATION_SECONDS},asetpts=PTS-STARTPTS[voice];"
            f"[3:a]volume=0.13,apad=whole_dur={TARGET_DURATION_SECONDS},"
            f"atrim=duration={TARGET_DURATION_SECONDS},asetpts=PTS-STARTPTS[bgm];"
            "[voice][bgm]amix=inputs=2:duration=first:normalize=0,"
            "alimiter=limit=0.95:attack=5:release=50:level=0[a]"
        )
    input_args = [
        "-i",
        str(generated_video_1),
        "-i",
        str(generated_video_2),
        "-i",
        str(speech),
        "-i",
        str(bgm),
    ]
    return input_args, f"{video_filter}{audio_filter}"


def _require_non_empty_file(path: Path, message: str) -> None:
    if not path.is_file() or path.stat().st_size == 0:
        raise PermanentError("contract_violation", message)


def _render_product_video(
    job: MediaJob,
    inputs: Mapping[str, Path],
    processes: ProcessRunner,
) -> HandlerOutcome:
    image_count, speech, generated_video_1, generated_video_2 = _validate_inputs(job, inputs)
    _require_non_empty_file(speech, "render_video requires a non-empty speech input")
    _require_non_empty_file(generated_video_1, "render_video requires a non-empty generated video")
    if generated_video_2 is not None:
        _require_non_empty_file(
            generated_video_2, "render_video requires two non-empty generated videos"
        )
    video = speech.parent / VIDEO_NAME
    audio_mode = extract_audio_mode(speech.read_bytes())
    target_dur = float(TARGET_DURATION_SECONDS)
    bgm = write_luxury_bgm_wav(speech.parent / "luxury_bgm.wav", target_dur)
    if generated_video_2 is None:
        input_args, filter_graph = _build_single_clip_compose(
            generated_video_1, speech, bgm, audio_mode, target_dur
        )
    else:
        input_args, filter_graph = _build_two_clip_compose(
            generated_video_1, generated_video_2, speech, bgm, image_count, audio_mode
        )
    try:
        completed = processes.run(
            [
                "ffmpeg",
                "-nostdin",
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                *input_args,
                "-filter_complex",
                filter_graph,
                "-map",
                "[v]",
                "-map",
                "[a]",
                "-t",
                str(target_dur),
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
                _FASTSTART_FLAG,
                str(video),
            ]
        )
    finally:
        bgm.unlink(missing_ok=True)
    if completed.returncode != 0 or not video.is_file() or video.stat().st_size == 0:
        log.warning(
            "product video ffmpeg compose failed: rc=%s stderr=%s",
            completed.returncode,
            completed.stderr.decode("utf-8", errors="replace").strip()[-500:],
        )
        video.unlink(missing_ok=True)
        raise PermanentError(
            "contract_violation",
            "the generated Product Video could not be composed",
        )

    duration_seconds = _verify_video(video, processes, expected_duration=target_dur)

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
) -> tuple[int, Path, Path, Path | None]:
    keys = [ref.key for ref in job.inputs]
    if len(keys) != len(set(keys)):
        raise PermanentError("contract_violation", "render_video input keys must be unique")

    image_keys = [key for key in keys if key.startswith("image_")]
    expected_images = [f"image_{position}" for position in range(1, len(image_keys) + 1)]
    has_second_video = "generated_video_2" in keys
    expected_keys = {
        *expected_images,
        "speech",
        "generated_video_1",
        *(("generated_video_2",) if has_second_video else ()),
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
            "render_video requires contiguous images, speech and one or two generated videos",
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
        inputs["generated_video_2"] if has_second_video else None,
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

    speech_dur = _probe_media_duration(speech, processes)
    target_dur = round(min(float(TARGET_DURATION_SECONDS), max(1.0, speech_dur)), 3)
    video = speech.parent / VIDEO_NAME
    if clip2 is not None:
        mc_end_ms = extract_mc_end_ms(speech.read_bytes())
        clip1_dur = (
            round(min(float(CLIP_DURATION_SECONDS), mc_end_ms / 1000.0), 3)
            if mc_end_ms is not None
            else float(CLIP_DURATION_SECONDS)
        )
        target_dur = round(
            min(float(TARGET_DURATION_SECONDS), max(target_dur, clip1_dur + 0.5)), 3
        )
        clip2_dur = round(max(0.5, target_dur - clip1_dur), 3)
        cmd = [
            "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
            "-i", str(clip1), "-i", str(clip2), "-i", str(speech),
            "-filter_complex",
            f"[0:v]scale={WIDTH}:{HEIGHT}:force_original_aspect_ratio=decrease,"
            f"pad={WIDTH}:{HEIGHT}:(ow-iw)/2:(oh-ih)/2:black,setsar=1,"
            f"fps=30,tpad=stop_mode=clone:stop_duration={clip1_dur},"
            f"trim=duration={clip1_dur},setpts=PTS-STARTPTS[v0];"
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
            "-map_metadata", "-1", "-movflags", _FASTSTART_FLAG,
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
            "-map_metadata", "-1", "-movflags", _FASTSTART_FLAG,
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


def _probe_media_duration(media: Path, processes: ProcessRunner) -> float:
    completed = processes.run(
        [
            "ffprobe", "-v", "error",
            "-show_entries", "format=duration",
            "-of", "json",
            str(media),
        ]
    )
    if completed.returncode != 0:
        raise PermanentError("contract_violation", "media input could not be probed")
    try:
        return float(json.loads(completed.stdout)["format"]["duration"])
    except (KeyError, TypeError, ValueError) as exc:
        raise PermanentError("contract_violation", "media duration is invalid") from exc


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
    except (KeyError, TypeError, ValueError) as exc:
        raise PermanentError(
            "contract_violation", "the rendered video does not meet the Product Video contract"
        ) from exc
    return round(duration, 3)
