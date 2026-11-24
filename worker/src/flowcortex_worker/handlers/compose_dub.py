"""`compose_dub`: puts a voice, and optionally music and effects, onto a picture as one MP4."""

from __future__ import annotations

import logging
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

from ..assembly.compose import EVEN_CROP, BackgroundSource, audio_graph
from ..assembly.duration import plan, resolve_policy
from ..assembly.inputs import is_run_bound
from ..assembly.loudness import effects_gain, gains, unusable_reason
from ..contracts.media_job import MediaJob
from ..errors import PermanentError
from ..runtime import ProcessRunner
from . import HandlerOutcome, OutputFile
from ._assembly_io import (
    ceil_ms,
    has_stream,
    input_args,
    input_seconds,
    loudness,
    mask_dir,
    require_audio,
    require_video,
    run_ffmpeg,
)

log = logging.getLogger(__name__)

VIDEO_KEY = "video"
UPLOAD_KEY = "media"
VOICE_KEY = "voice"
BACKGROUND_KEY = "background"
EFFECTS_KEY = "effects"
ROLES = frozenset({VIDEO_KEY, UPLOAD_KEY, VOICE_KEY, BACKGROUND_KEY, EFFECTS_KEY})
LIMIT_SECONDS = 600
OUTPUT_NAME = "video.mp4"
OUTPUT_MIME = "video/mp4"


def handle(
    job: MediaJob,
    inputs: Mapping[str, Path],
    processes: ProcessRunner,
) -> HandlerOutcome:
    picture_key = _check_keys(inputs)
    policy = resolve_policy(job.params.get("durationPolicy"))
    use_video_audio = _flag(job.params, "videoAudioAsBackground")

    picture = inputs[picture_key]
    voice = inputs[VOICE_KEY]
    background = inputs.get(BACKGROUND_KEY)
    effects = inputs.get(EFFECTS_KEY)

    require_video(picture_key, picture, processes)
    video_s = input_seconds(picture_key, picture, processes, "input_rejected")
    if ceil_ms(video_s) > LIMIT_SECONDS * 1000:
        raise PermanentError(
            "duration_exceeded", f"the picture is longer than the limit of {LIMIT_SECONDS} seconds"
        )
    require_audio(VOICE_KEY, voice, processes)
    voice_s = input_seconds(VOICE_KEY, voice, processes, "audio_extraction_failed")
    for key, path in ((BACKGROUND_KEY, background), (EFFECTS_KEY, effects)):
        if path is not None:
            require_audio(key, path, processes)

    music: BackgroundSource | None = None
    if background is not None:
        music = "file"
    elif use_video_audio and has_stream(picture, "audio", processes, "input_rejected"):
        music = "video"

    chosen = plan(video_s, voice_s, policy, keep_longer=False)
    if chosen.mismatch:
        log.warning(
            "the voice and the picture differ in length",
            extra={"voiceSeconds": voice_s, "pictureSeconds": video_s},
        )

    mixed: dict[str, float] = {VOICE_KEY: 0.0}
    if music is not None or effects is not None:
        mixed = _gains(
            voice,
            picture if music == "video" else background,
            effects,
            processes,
        )

    scratch = processes.scratch_dir()
    target = scratch / OUTPUT_NAME
    sources = [path for path in (picture, voice, background, effects) if path is not None]
    run_ffmpeg(
        _encode_argv(
            picture, voice, background, effects, music, mixed, chosen.tpad, chosen.target, target
        ),
        processes,
        "compose_failed",
        "the encode",
        mask_dir(sources, scratch),
        output=target,
    )
    return HandlerOutcome(
        data={
            "policy": policy,
            "targetMs": round(chosen.target * 1000),
            "videoMs": round(video_s * 1000),
            "voiceMs": round(voice_s * 1000),
            "gainsDb": mixed,
        },
        files=[OutputFile(OUTPUT_NAME, OUTPUT_MIME, target)],
    )


def _check_keys(inputs: Mapping[str, Path]) -> str:
    """The picture's key (`video`, else the bound `media`); any key outside the roles and the
    run-bound keys, a missing voice or a missing picture is a `contract_violation`."""
    for key in sorted(inputs):
        if key not in ROLES and not is_run_bound(key):
            raise PermanentError("contract_violation", f"unexpected input key '{key}'")
    if VOICE_KEY not in inputs:
        raise PermanentError("contract_violation", f"the job needs an input '{VOICE_KEY}'")
    for key in (VIDEO_KEY, UPLOAD_KEY):
        if key in inputs:
            return key
    raise PermanentError(
        "contract_violation", f"the job needs an input '{VIDEO_KEY}' or '{UPLOAD_KEY}'"
    )


def _flag(params: Mapping[str, Any], name: str) -> bool:
    value = params.get(name)
    if value is None:
        return False
    if not isinstance(value, bool):
        raise PermanentError(
            "contract_violation", f"param '{name}' must be true or false (got {value!r})"
        )
    return value


def _gains(
    voice: Path, music: Path | None, effects: Path | None, processes: ProcessRunner
) -> dict[str, float]:
    """The gain in dB of each source that is mixed. A measurement that cannot be read, or a
    silent source, keeps 0 dB; an ffmpeg that fails on it fails the compose."""
    voice_lufs = _lufs(voice, processes)
    music_lufs = _lufs(music, processes) if music is not None else None
    effects_lufs = _lufs(effects, processes) if effects is not None else None
    for source, lufs, present in (
        (VOICE_KEY, voice_lufs, True),
        (BACKGROUND_KEY, music_lufs, music is not None),
        (EFFECTS_KEY, effects_lufs, effects is not None),
    ):
        reason = unusable_reason(lufs) if present else None
        if reason:
            log.warning("a source keeps its level", extra={"source": source, "reason": reason})
    voice_gain, music_gain = gains(voice_lufs, music_lufs)
    mixed = {VOICE_KEY: voice_gain}
    if music is not None:
        mixed[BACKGROUND_KEY] = music_gain
    if effects is not None:
        mixed[EFFECTS_KEY] = effects_gain(effects_lufs)
    return mixed


def _lufs(path: Path, processes: ProcessRunner) -> float | None:
    return loudness(path, "a:0", processes, "compose_failed")


def _encode_argv(
    picture: Path,
    voice: Path,
    background: Path | None,
    effects: Path | None,
    music: BackgroundSource | None,
    mixed: Mapping[str, float],
    tpad: str | None,
    length: float,
    target: Path,
) -> list[str]:
    video_chain = ",".join(filter(None, [EVEN_CROP, tpad]))
    graph = audio_graph(
        video_chain,
        music,
        effects is not None,
        {BACKGROUND_KEY: 0.0, EFFECTS_KEY: 0.0, **mixed},
        length,
    )
    paths: Sequence[Path]
    if graph is None:
        paths = [picture, voice]
        maps = ["-filter_complex", f"[0:v]{video_chain}[v]", "-map", "[v]", "-map", "1:a:0"]
    else:
        by_role = {
            "video": picture,
            VOICE_KEY: voice,
            BACKGROUND_KEY: background,
            EFFECTS_KEY: effects,
        }
        ordered = [by_role[role] for role in graph.inputs]
        paths = [path for path in ordered if path is not None]
        maps = ["-filter_complex", graph.graph, "-map", "[v]", "-map", "[aout]"]
    argv = ["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y"]
    for path in paths:
        argv += input_args(path)
    argv += [
        *maps,
        "-c:v", "libx264", "-preset", "ultrafast", "-crf", "23", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "160k",
        "-movflags", "+faststart",
        "-t", f"{length:.3f}",
        str(target),
    ]  # fmt: skip
    return argv
