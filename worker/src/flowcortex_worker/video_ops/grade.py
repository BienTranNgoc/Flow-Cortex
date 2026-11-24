"""Rules of the grade_match capability: targets, shortfall, edge measurements, the colour ramp and
the filter graph that joins the shots."""

from __future__ import annotations

import math
import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from ..errors import PermanentError
from ..video_analysis.streams import VideoStream, tool_failure
from .media import Clip

LUMA_RANGE = (16, 235)
CHROMA_RANGE = (16, 240)
# Generated takes drift by a few hundred ms; up to this much is filled by holding the last
# frame, anything longer is a bad take and is refused.
MAX_PAD_MS = 500
# Length of the opening and ending stretch whose mean is moved onto each neighbouring shot.
EDGE_WINDOW_S = 0.3
RAMP_STEP_LEVELS = 0.45
MAX_RAMP_STEPS = 64

CHANNELS = ("Y", "U", "V")
_PLANE_RANGE = {"Y": LUMA_RANGE, "U": CHROMA_RANGE, "V": CHROMA_RANGE}
_TARGET_MAX = 255

Targets = dict[str, float | None]
Offsets = dict[str, float]


def _channel(value: Any, field: str) -> float | None:
    if value is None:
        return None
    if (
        isinstance(value, bool)
        or not isinstance(value, int | float)
        or not math.isfinite(value)
        or not 0 <= value <= _TARGET_MAX
    ):
        raise PermanentError(
            "contract_violation",
            f"param {field!r} must be null or a number in 0-{_TARGET_MAX} (got {value!r})",
        )
    return float(value)


def _side(params: Mapping[str, Any], key: str) -> Targets:
    raw = params.get(key)
    if raw is None:
        return dict.fromkeys(CHANNELS)
    if not isinstance(raw, Mapping):
        raise PermanentError(
            "contract_violation", f"param {key!r} must be an object or null (got {raw!r})"
        )
    return {ch: _channel(raw.get(ch.lower()), f"{key}.{ch.lower()}") for ch in CHANNELS}


def read_targets(params: Mapping[str, Any]) -> tuple[Targets, Targets]:
    """`(targetIn, targetOut)` as `{"Y", "U", "V"} -> number | None`.

    A channel that is null on one side takes the other side's value; one that is null on both is
    left alone.
    """
    target_in, target_out = _side(params, "targetIn"), _side(params, "targetOut")
    return (
        {ch: target_in[ch] if target_in[ch] is not None else target_out[ch] for ch in CHANNELS},
        {ch: target_out[ch] if target_out[ch] is not None else target_in[ch] for ch in CHANNELS},
    )


def has_target(target_in: Targets, target_out: Targets) -> bool:
    return any(v is not None for v in (*target_in.values(), *target_out.values()))


def shortfall(total_ms: int, trim_ms: int) -> int:
    """Milliseconds of padding the shots need to reach `trim_ms`; 0 when they are long enough."""
    missing = trim_ms - total_ms
    if missing > MAX_PAD_MS:
        raise PermanentError(
            "input_rejected",
            f"the shots last {total_ms} ms, more than {MAX_PAD_MS} ms short of the {trim_ms} ms "
            f"to trim to",
        )
    return max(missing, 0)


def parse_signal_means(text: str, channel: str) -> list[float]:
    """The per-frame means of one channel from a `metadata=print` file of `signalstats`."""
    found = re.findall(rf"signalstats\.{channel}AVG=(\d+(?:\.\d+)?)", text)
    if not found:
        raise tool_failure("ffmpeg", f"the measuring pass found no {channel}AVG frame value")
    return [float(v) for v in found]


def edge_window(frames: int, fps: float) -> int:
    """Frames averaged at each edge: 0.3 s of them, at least 1 and at most half the stream."""
    return max(1, min(round(EDGE_WINDOW_S * fps), frames // 2))


def edge_means(series: Mapping[str, Sequence[float]], fps: float) -> tuple[Offsets, Offsets]:
    """`(opening, ending)` mean of each channel over the first and the last frames."""
    window = edge_window(len(series["Y"]), fps)
    opening = {ch: sum(series[ch][:window]) / window for ch in CHANNELS}
    ending = {ch: sum(series[ch][-window:]) / window for ch in CHANNELS}
    return opening, ending


def offsets(
    target_in: Targets, target_out: Targets, opening: Offsets, ending: Offsets
) -> tuple[Offsets, Offsets]:
    """The offset moving each edge onto its target; a channel with no target is not moved."""
    d_in = dict.fromkeys(CHANNELS, 0.0)
    d_out = dict(d_in)
    for ch in CHANNELS:
        goal_in, goal_out = target_in[ch], target_out[ch]
        if goal_in is not None:
            d_in[ch] = round(goal_in - opening[ch], 2)
        if goal_out is not None:
            d_out[ch] = round(goal_out - ending[ch], 2)
    return d_in, d_out


def lut(plane_offsets: Offsets, enable: str | None = None) -> str:
    """One `lutyuv` adding a constant offset per plane (+0.5: the table truncates); a plane at 0
    is skipped."""
    options = []
    for ch in CHANNELS:
        if plane_offsets[ch] == 0:
            continue
        low, high = _PLANE_RANGE[ch]
        options.append(f"{ch.lower()}='clip(val+({plane_offsets[ch]:.3f})+0.5\\,{low}\\,{high})'")
    if enable:
        options.append(f"enable='{enable}'")
    return "lutyuv=" + ":".join(options)


def grade_filter(d_in: Offsets, d_out: Offsets, seconds: float) -> str | None:
    """`lutyuv` chain moving the offset linearly from `d_in` to `d_out` over `seconds` as a
    piecewise-constant ramp, or None when nothing moves. A per-pixel `geq` ramp is ~100x slower."""
    if all(d_in[ch] == 0 and d_out[ch] == 0 for ch in CHANNELS):
        return None
    if all(d_in[ch] == d_out[ch] for ch in CHANNELS):
        return lut(d_in)
    spread = max(abs(d_out[ch] - d_in[ch]) for ch in CHANNELS)
    steps = max(2, min(MAX_RAMP_STEPS, int(spread / RAMP_STEP_LEVELS) + 1))
    edges = [f"{seconds * k / steps:.4f}" for k in range(steps + 1)]
    parts = []
    for k in range(steps):
        fraction = (k + 0.5) / steps
        at = {ch: d_in[ch] + (d_out[ch] - d_in[ch]) * fraction for ch in CHANNELS}
        # Neighbouring windows share one formatted edge value, so no timestamp falls between them.
        if k == 0:
            window = f"lt(t\\,{edges[1]})"
        elif k == steps - 1:
            window = f"gte(t\\,{edges[k]})"
        else:
            window = f"gte(t\\,{edges[k]})*lt(t\\,{edges[k + 1]})"
        parts.append(lut(at, window))
    return ",".join(parts)


@dataclass(frozen=True)
class Join:
    """The ffmpeg input arguments and filter graph that join the shots into `[v]` (and `[a]`)."""

    inputs: list[str]
    parts: list[str]
    any_audio: bool


def join_graph(shots: Sequence[tuple[Path, Clip]], trim_s: float, pad_s: float) -> Join:
    """Join the shots in order at shot 1's size and frame rate, in BT.709 TV range.

    A later shot is scaled to cover the frame and centre-cropped; a shot without audio gets
    silence when any shot has it; `pad_s` holds the last frame and pads the audio up to `trim_s`.
    The result is cut by the output's `-t`, never a `trim` filter, which drops the last frame.
    """
    first = shots[0][1].video
    if first.rate is None:
        raise ValueError("the frame rate was not read; probe with require_rate=True")
    rate = f"{first.rate[0]}/{first.rate[1]}"
    any_audio = any(clip.has_audio for _, clip in shots)
    inputs, silence = _input_args(shots, any_audio)

    parts: list[str] = []
    for n, (_, clip) in enumerate(shots):
        parts.append(_video_part(n, clip, first, rate))
        if any_audio:
            source = f"{n}:a" if clip.has_audio else f"{silence[n]}:a"
            parts.append(f"[{source}]aresample=48000,aformat=channel_layouts=stereo[a{n}]")
    parts += _concat_parts(len(shots), any_audio, trim_s, pad_s)
    return Join(inputs, parts, any_audio)


def _input_args(
    shots: Sequence[tuple[Path, Clip]], any_audio: bool
) -> tuple[list[str], dict[int, int]]:
    """The `-i` arguments, and the input index of the silent source of each shot with no audio."""
    inputs: list[str] = []
    for path, _ in shots:
        inputs += ["-protocol_whitelist", "file", "-i", str(path)]
    silence: dict[int, int] = {}
    if any_audio:
        for n, (_, clip) in enumerate(shots):
            if not clip.has_audio:
                inputs += [
                    "-f", "lavfi", "-t", f"{clip.duration_ms / 1000:.3f}",
                    "-i", "anullsrc=r=48000:cl=stereo",
                ]  # fmt: skip
                silence[n] = len(shots) + len(silence)
    return inputs, silence


def _video_part(n: int, clip: Clip, first: VideoStream, rate: str) -> str:
    # An untagged shot is read as BT.709: ffmpeg otherwise assumes BT.601 and the conversion
    # shifts luma by about 2 before the means are measured.
    matrix = "" if clip.color_tagged else ":in_color_matrix=bt709"
    return (
        f"[{n}:v]scale={first.width}:{first.height}:force_original_aspect_ratio=increase"
        f"{matrix}:out_color_matrix=bt709:out_range=tv,"
        f"crop={first.width}:{first.height},setsar=1,fps={rate},format=yuv420p[v{n}]"
    )


def _concat_parts(count: int, any_audio: bool, trim_s: float, pad_s: float) -> list[str]:
    pairs = "".join(f"[v{n}]" + (f"[a{n}]" if any_audio else "") for n in range(count))
    parts = [
        f"{pairs}concat=n={count}:v=1:a={1 if any_audio else 0}"
        + ("[vc][ac]" if any_audio else "[vc]"),
        f"[vc]tpad=stop_mode=clone:stop_duration={pad_s:.3f}[v]" if pad_s > 0 else "[vc]null[v]",
    ]
    if any_audio:
        parts.append(f"[ac]apad=whole_dur={trim_s:.3f}[a]" if pad_s > 0 else "[ac]anull[a]")
    return parts
