"""Audio mix of a project render: one pass writes `mix.wav`, then loudness is measured and applied
in the final mux.

Pure: nothing runs here. Every file name comes from the caller's `files` mapping (asset id to a
local path) and every number is formatted from a value `document` and `timeline` already checked.
"""

from __future__ import annotations

import json
import math
import os
import re
from collections.abc import Mapping, Sequence
from typing import Any

from .document import SAMPLE_RATE
from .timeline import MIX_DEFAULTS, PlannedMusicClip, PlannedVoiceClip, TimelinePlan

ATEMPO_STAGE_MIN = 0.5
ATEMPO_STAGE_MAX = 2.0
_ASSET_INPUT = ("-protocol_whitelist", "file")
_STEREO = "aformat=sample_fmts=fltp:channel_layouts=stereo"
_LOUDNORM_BLOCK = re.compile(r'\{[^{}]*"input_i"[^{}]*\}', re.S)
_LOUDNORM_KEYS = ("input_i", "input_lra", "input_tp", "input_thresh", "target_offset")


def atempo_stages(rate: float) -> list[float]:
    """`atempo` stages for a speed `rate` (tempo change, pitch kept). One stage only holds 0.5..2
    without dropping samples, so a larger change is chained; the product of the stages is `rate`
    and a rate of 1 needs none."""
    stages: list[float] = []
    rest = float(rate)
    while rest > ATEMPO_STAGE_MAX:
        stages.append(ATEMPO_STAGE_MAX)
        rest /= ATEMPO_STAGE_MAX
    while rest < ATEMPO_STAGE_MIN:
        stages.append(ATEMPO_STAGE_MIN)
        rest /= ATEMPO_STAGE_MIN
    if abs(rest - 1.0) > 1e-9:
        stages.append(rest)
    return stages


def _sec(ms: float) -> str:
    return f"{ms / 1000.0:.4f}"


def _join(labels: Sequence[str], out: str) -> str:
    """Sum the labelled streams into `out`; one stream passes through unchanged."""
    if len(labels) == 1:
        return f"{labels[0]}anull{out}"
    return f"{''.join(labels)}amix=inputs={len(labels)}:duration=longest:normalize=0{out}"


def _clip_chain(
    index: int,
    clip: PlannedVoiceClip | PlannedMusicClip,
    label: str,
    total_s: str,
    *,
    loop: bool,
    rate: float,
) -> str:
    trim = clip.trim if isinstance(clip, PlannedVoiceClip) else None
    play_ms = trim.play_ms if trim else clip.dur_ms
    fade_out_ms = trim.fade_ms if trim else clip.fade_out_ms
    src_end = min(clip.src_in_ms + play_ms * rate, clip.src_out_ms) if trim else clip.src_out_ms
    chain = (
        f"[{index}:a]atrim=start={_sec(clip.src_in_ms)}:end={_sec(src_end)},"
        f"asetpts=PTS-STARTPTS,aresample={SAMPLE_RATE},{_STEREO}"
    )
    stages = atempo_stages(rate)
    if stages:
        chain += "".join(f",atempo={stage:.6g}" for stage in stages)
        chain += f",atrim=duration={_sec(play_ms)}"
    if loop:
        segment = max(1, round((clip.src_out_ms - clip.src_in_ms) * SAMPLE_RATE / 1000))
        chain += (
            f",aloop=loop=-1:size={segment},atrim=duration={_sec(clip.dur_ms)},asetpts=PTS-STARTPTS"
        )
    if clip.fade_in_ms > 0:
        chain += f",afade=t=in:st=0:d={_sec(clip.fade_in_ms)}"
    if fade_out_ms > 0:
        chain += f",afade=t=out:st={_sec(play_ms - fade_out_ms)}:d={_sec(fade_out_ms)}"
    # `adelay` can leave the first frame without a timestamp after `atempo`, and later filters
    # then drop the inserted silence; counting samples keeps the start independent of scheduling.
    delay = round(clip.at_ms)
    chain += (
        f",volume={clip.gain_db:g}dB,adelay={delay}|{delay},asetpts=N/SR/TB,"
        f"apad=whole_dur={total_s},atrim=duration={total_s}{label}"
    )
    return chain


def mix_command(
    plan: TimelinePlan,
    base_path: str,
    files: Mapping[str, str],
    work_dir: str,
    threads: int = 2,
) -> tuple[list[str], str]:
    """ffmpeg argv writing `mix.wav` (stereo PCM, exactly the timeline length) and the path of
    that file. Inputs: the joined base for the scene audio, then voice clips, then music clips."""
    total_s = _sec(plan.total_ms)
    argv = [
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-threads", str(threads),
        "-i", base_path,
    ]  # fmt: skip
    inputs = 1
    parts = [
        f"[0:a]aresample=async=1:first_pts=0,{_STEREO},"
        f"apad=whole_dur={total_s},atrim=duration={total_s}[scene]"
    ]

    voice_labels: list[str] = []
    for i, clip in enumerate(plan.voice):
        argv += [*_ASSET_INPUT, "-i", files[clip.asset_id]]
        parts.append(_clip_chain(inputs, clip, f"[vo{i}]", total_s, loop=False, rate=clip.rate))
        inputs += 1
        voice_labels.append(f"[vo{i}]")

    # Narration and dubbing key the ducking; original-voice stems are mixed but never duck.
    keys = [i for i, clip in enumerate(plan.voice) if not clip.stem]
    has_music = bool(plan.music)
    duck = has_music and plan.duck_enabled and bool(keys)
    some_stems = len(keys) < len(plan.voice)
    if duck and some_stems:
        for i in keys:
            parts.append(f"[vo{i}]asplit=2[vm{i}][vk{i}]")
            voice_labels[i] = f"[vm{i}]"
        parts.append(_join([f"[vk{i}]" for i in keys], "[voice_sc]"))

    music_labels: list[str] = []
    for i, bed in enumerate(plan.music):
        argv += [*_ASSET_INPUT, "-i", files[bed.asset_id]]
        parts.append(_clip_chain(inputs, bed, f"[mu{i}]", total_s, loop=bed.loop, rate=1.0))
        inputs += 1
        music_labels.append(f"[mu{i}]")

    if voice_labels:
        parts.append(_join(voice_labels, "[voice]"))
    else:
        argv += ["-f", "lavfi", "-t", total_s, "-i", f"anullsrc=r={SAMPLE_RATE}:cl=stereo"]
        parts.append(f"[{inputs}:a]atrim=duration={total_s}[voice]")
        inputs += 1

    if music_labels:
        parts.append(_join(music_labels, "[music0]"))
        voice_out = "[voice]"
        if duck:
            settings = MIX_DEFAULTS["duck"]
            if not some_stems:
                parts.append("[voice]asplit=2[voice_mix][voice_sc]")
                voice_out = "[voice_mix]"
            parts.append(
                "[music0][voice_sc]sidechaincompress="
                f"threshold={settings['threshold']:g}:ratio={settings['ratio']:g}:"
                f"attack={settings['attack']:g}:release={settings['release']:g}[music]"
            )
        else:
            parts.append("[music0]anull[music]")
        parts.append(
            f"[scene]{voice_out}[music]amix=inputs=3:duration=longest:normalize=0,"
            f"atrim=duration={total_s}[mix]"
        )
    else:
        parts.append(
            f"[scene][voice]amix=inputs=2:duration=longest:normalize=0,"
            f"atrim=duration={total_s}[mix]"
        )

    out = os.path.join(work_dir, "mix.wav")
    argv += [
        "-filter_complex", ";".join(parts), "-map", "[mix]",
        "-c:a", "pcm_s16le", "-ar", str(SAMPLE_RATE), "-t", total_s, out,
    ]  # fmt: skip
    return argv, out


def parse_loudnorm(stderr: str) -> dict[str, Any]:
    """The JSON block `loudnorm=print_format=json` prints on stderr.

    ffmpeg 7.1 prints an `[out#0/null ...]` and a `size=` line after the block, so the block is
    matched by its `input_i` key rather than cut from the last brace; the last match wins.
    """
    found = _LOUDNORM_BLOCK.findall(stderr)
    if not found:
        raise ValueError(f"no loudnorm measurement in ffmpeg output: {stderr[-400:]!r}")
    try:
        block = json.loads(found[-1])
    except json.JSONDecodeError as exc:
        raise ValueError(f"unreadable loudnorm measurement: {found[-1][:200]!r}") from exc
    if not isinstance(block, dict):
        raise ValueError("loudnorm measurement is not an object")
    return block


def loudness_measure_command(path: str) -> list[str]:
    """First loudnorm pass: measure `path` (audio only, nothing written)."""
    return [
        "ffmpeg", "-hide_banner", "-nostats", "-i", path, "-af",
        f"loudnorm=I={MIX_DEFAULTS['targetLufs']:g}:TP={MIX_DEFAULTS['truePeakDb']:g}"
        f":LRA={MIX_DEFAULTS['lra']:g}:print_format=json",
        "-f", "null", "-",
    ]  # fmt: skip


def loudnorm_filter(measured: Mapping[str, Any]) -> str | None:
    """Second loudnorm pass built from the measurement, or None when the mix is silent
    (`input_i` is `-inf`) and there is nothing to normalise."""
    for key in _LOUDNORM_KEYS:
        if key not in measured:
            raise ValueError(f"loudnorm measurement lacks {key}")
    try:
        integrated = float(measured["input_i"])
    except (TypeError, ValueError) as exc:
        raise ValueError(f"loudnorm input_i is not a number: {measured['input_i']!r}") from exc
    if not math.isfinite(integrated):
        return None
    return (
        f"loudnorm=I={MIX_DEFAULTS['targetLufs']:g}:TP={MIX_DEFAULTS['truePeakDb']:g}"
        f":LRA={MIX_DEFAULTS['lra']:g}:measured_I={measured['input_i']}"
        f":measured_LRA={measured['input_lra']}:measured_TP={measured['input_tp']}"
        f":measured_thresh={measured['input_thresh']}:offset={measured['target_offset']}"
        f":linear=true:print_format=json,aresample={SAMPLE_RATE}"
    )


def final_mux_command(
    base_path: str,
    mix_path: str,
    out_path: str,
    duration_ms: float,
    video_start_s: float = 0.0,
    audio_filter: str | None = None,
) -> list[str]:
    """Copy the picture of the joined base, encode `mix.wav` to AAC once. The copied picture keeps
    the B-frame start offset of the first chunk, so `video_start_s` is shifted out on the picture
    input alone."""
    seconds = _sec(duration_ms)
    argv = [
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-itsoffset", f"{-video_start_s:.6f}", "-i", base_path, "-i", mix_path,
        "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy",
    ]  # fmt: skip
    if audio_filter:
        argv += ["-af", audio_filter]
    argv += [
        "-c:a", "aac", "-b:a", "160k", "-ar", str(SAMPLE_RATE),
        "-t", seconds, "-movflags", "+faststart", out_path,
    ]  # fmt: skip
    return argv
