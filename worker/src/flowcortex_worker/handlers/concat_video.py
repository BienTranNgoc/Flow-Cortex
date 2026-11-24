"""`concat_video`: normalises `clip_1 … clip_N` to one frame format and joins them into an MP4."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

from ..assembly.duration import plan, resolve_policy
from ..assembly.inputs import numbered
from ..contracts.media_job import MediaJob
from ..errors import PermanentError
from ..runtime import ProcessRunner
from . import HandlerOutcome, OutputFile
from ._assembly_io import (
    ceil_ms,
    duration_seconds,
    has_stream,
    input_args,
    input_seconds,
    mask_dir,
    require_audio,
    require_video,
    run_ffmpeg,
)

PREFIX = "clip"
NARRATION_KEY = "narration"
MIN_CLIPS = 2
MAX_CLIPS = 20
LIMIT_SECONDS = 600
ASPECT_DIMS = {"16:9": (1280, 720), "9:16": (720, 1280), "1:1": (1080, 1080)}
DEFAULT_ASPECT = "16:9"
OUTPUT_NAME = "video.mp4"
OUTPUT_MIME = "video/mp4"
JOINED_NAME = "joined.mp4"
LIST_NAME = "list.txt"
SILENCE = "anullsrc=r=48000:cl=stereo"


def handle(
    job: MediaJob,
    inputs: Mapping[str, Path],
    processes: ProcessRunner,
) -> HandlerOutcome:
    ordered, roles = numbered(inputs, PREFIX, MIN_CLIPS, MAX_CLIPS, extra=(NARRATION_KEY,))
    frame = _frame(job.params)
    drop_audio = _flag(job.params, "dropAudio")
    policy = resolve_policy(job.params.get("durationPolicy"))
    # A muted join wins over a narration: the narration is neither read nor probed.
    narration = None if drop_audio else roles.get(NARRATION_KEY)

    total_seconds = 0.0
    with_audio: list[bool] = []
    for number, path in enumerate(ordered, start=1):
        key = f"{PREFIX}_{number}"
        require_video(key, path, processes)
        total_seconds += input_seconds(key, path, processes, "input_rejected")
        with_audio.append(not drop_audio and _has_audio(key, path, processes))
    if ceil_ms(total_seconds) > LIMIT_SECONDS * 1000:
        raise PermanentError(
            "duration_exceeded", f"the clips are longer than the limit of {LIMIT_SECONDS} seconds"
        )
    narration_seconds = 0.0
    if narration is not None:
        require_audio(NARRATION_KEY, narration, processes)
        narration_seconds = input_seconds(
            NARRATION_KEY, narration, processes, "audio_extraction_failed"
        )

    scratch = processes.scratch_dir()
    hide = mask_dir([*ordered, *([narration] if narration is not None else [])], scratch)
    normalised: list[Path] = []
    for number, (path, audible) in enumerate(zip(ordered, with_audio, strict=True), start=1):
        key = f"{PREFIX}_{number}"
        target = scratch / f"norm_{number}.mp4"
        run_ffmpeg(
            _normalise_argv(path, audible, drop_audio, frame, target),
            processes,
            "video_concat_failed",
            f"normalising {key}",
            hide,
            output=target,
        )
        normalised.append(target)

    listing = scratch / LIST_NAME
    listing.write_text("".join(f"file '{_quoted(path)}'\n" for path in normalised))
    final = scratch / OUTPUT_NAME
    joined = scratch / JOINED_NAME if narration is not None else final
    run_ffmpeg(
        _join_argv(listing, drop_audio, joined),
        processes,
        "video_concat_failed",
        "the join",
        hide,
        output=joined,
    )

    if narration is not None:
        film_seconds = duration_seconds(joined, processes, "video_concat_failed")
        chosen = plan(film_seconds, narration_seconds, policy, keep_longer=True)
        run_ffmpeg(
            _overlay_argv(joined, narration, chosen.tpad, chosen.target, final),
            processes,
            "video_concat_failed",
            "the narration overlay",
            hide,
            output=final,
        )
    length = duration_seconds(final, processes, "video_concat_failed")
    return HandlerOutcome(
        data={"clipCount": len(ordered), "durationMs": round(length * 1000)},
        files=[OutputFile(OUTPUT_NAME, OUTPUT_MIME, final)],
    )


def _frame(params: Mapping[str, Any]) -> tuple[int, int]:
    value = params.get("aspectRatio")
    if value is None:
        return ASPECT_DIMS[DEFAULT_ASPECT]
    if not isinstance(value, str) or value not in ASPECT_DIMS:
        raise PermanentError(
            "contract_violation",
            f"param 'aspectRatio' must be one of {', '.join(ASPECT_DIMS)} (got {value!r})",
        )
    return ASPECT_DIMS[value]


def _flag(params: Mapping[str, Any], name: str) -> bool:
    value = params.get(name)
    if value is None:
        return False
    if not isinstance(value, bool):
        raise PermanentError(
            "contract_violation", f"param '{name}' must be true or false (got {value!r})"
        )
    return value


def _has_audio(key: str, path: Path, processes: ProcessRunner) -> bool:
    try:
        return has_stream(path, "audio", processes, "input_rejected")
    except PermanentError as failed:
        raise PermanentError(failed.code, f"input '{key}': {failed.message}") from failed


def _quoted(path: Path) -> str:
    """A path inside the concat list's single quotes."""
    return str(path).replace("'", "'\\''")


def _normalise_argv(
    source: Path, has_audio: bool, drop_audio: bool, frame: tuple[int, int], target: Path
) -> list[str]:
    width, height = frame
    # The colour tags are forced because a clip made from a still image (pc range, no matrix) next
    # to one from a video model changes frame properties at the join; ffmpeg 7.x then rebuilds the
    # filter graph and the audio after the seam is lost.
    video_filter = (
        f"scale={width}:{height}:force_original_aspect_ratio=decrease"
        ":out_color_matrix=bt709:out_range=tv,"
        f"pad={width}:{height}:(ow-iw)/2:(oh-ih)/2:black,setsar=1,format=yuv420p"
    )
    silent = not drop_audio and not has_audio
    argv = ["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y", *input_args(source)]
    if silent:
        argv += ["-protocol_whitelist", "file", "-f", "lavfi", "-i", SILENCE]
    argv += ["-map", "0:v:0"]
    if not drop_audio:
        argv += ["-map", "1:a:0" if silent else "0:a:0"]
    argv += [
        "-vf", video_filter,
        "-c:v", "libx264", "-preset", "fast", "-crf", "23",
        "-colorspace", "bt709", "-color_range", "tv",
    ]  # fmt: skip
    # A fixed rate and channel layout: clips from different models arrive at 24 or 32 kHz, and a
    # stream-copy join of mixed rates yields audio the players cannot decode.
    argv += ["-an"] if drop_audio else ["-c:a", "aac", "-b:a", "128k", "-ar", "48000", "-ac", "2"]
    argv += ["-r", "30"]
    if silent:
        argv.append("-shortest")
    argv.append(str(target))
    return argv


def _join_argv(listing: Path, drop_audio: bool, target: Path) -> list[str]:
    argv = [
        "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
        "-f", "concat", "-safe", "0", *input_args(listing),
        "-c:v", "copy",
    ]  # fmt: skip
    argv += ["-an"] if drop_audio else ["-c:a", "copy"]
    argv += ["-movflags", "+faststart", str(target)]
    return argv


def _overlay_argv(
    film: Path, narration: Path, tpad: str | None, length: float, target: Path
) -> list[str]:
    argv = ["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y"]
    for source in (film, narration):
        argv += input_args(source)
    # A stream copy cannot go through a filter, so the picture is re-encoded only when the
    # narration outlasts it and its last frame must be held.
    picture: Sequence[str] = (
        ["-vf", tpad, "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p"]
        if tpad
        else ["-c:v", "copy"]
    )
    argv += [
        *picture,
        "-c:a", "aac", "-b:a", "128k",
        "-map", "0:v:0", "-map", "1:a:0",
        "-movflags", "+faststart",
        "-t", f"{length:.3f}",
        str(target),
    ]  # fmt: skip
    return argv
