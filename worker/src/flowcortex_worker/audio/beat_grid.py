"""Beat result rules: whole-ms marks, bars, bar energy and drops, shaped from engine output."""

from __future__ import annotations

import math
import statistics
from bisect import bisect_left
from collections.abc import Iterable, Sequence
from decimal import ROUND_HALF_UP, Decimal
from itertools import pairwise

RESULT_VERSION = 1
ENGINE = "beat-this/1.1.0+final0"
DROP_RATIO = 2.0
LOOKBACK_BARS = 4
BEATS_PER_BAR = 4
RMS_FLOOR = 1e-3
SILENCE_RMS = 1e-3
MIN_BEATS = 4
MIN_AUDIO_MS = 2000


class NoBeats(ValueError):
    """The audio has no usable pulse."""


def _ms(seconds: float) -> int:
    return int((Decimal(repr(seconds)) * 1000).quantize(Decimal(1), rounding=ROUND_HALF_UP))


def marks_ms(seconds: Iterable[float], duration_ms: int) -> list[int]:
    """Engine marks as sorted, distinct whole ms inside `[0, duration_ms]`."""
    return sorted({m for m in map(_ms, seconds) if 0 <= m <= duration_ms})


def bars(beats: Sequence[int], downbeats: Sequence[int]) -> list[int]:
    """Downbeats as given when there are two or more and the median beats per bar is at least 2;
    otherwise every 4th beat from the beat nearest the first downbeat (the first beat if none)."""
    if not beats:
        return []
    if len(downbeats) >= 2:
        edges = [*downbeats, None]
        counts = [
            bisect_left(beats, end) - bisect_left(beats, start)
            if end is not None
            else len(beats) - bisect_left(beats, start)
            for start, end in zip(downbeats, edges[1:], strict=True)
        ]
        if statistics.median(counts) >= 2:
            return list(downbeats)
    start_index = 0
    if downbeats:
        first = downbeats[0]
        start_index = min(range(len(beats)), key=lambda i: (abs(beats[i] - first), i))
    return list(beats[start_index::BEATS_PER_BAR])


def bar_rms(rms: Sequence[float], hop_ms: float, start_ms: int, end_ms: int) -> float:
    """Mean of the RMS frames whose start time lies in `[start_ms, end_ms)`; 0 for an empty bar."""
    first = max(0, math.ceil(start_ms / hop_ms))
    last = min(len(rms), math.ceil(end_ms / hop_ms))
    if last <= first:
        return 0.0
    window = rms[first:last]
    return sum(window) / len(window)


def drops_from_energy(
    downbeats: Sequence[int], energies: Sequence[float]
) -> list[dict[str, float]]:
    """A bar after the first is a drop when its energy is at least `DROP_RATIO` times the mean of
    up to `LOOKBACK_BARS` bars before it (floored at `RMS_FLOOR`); the next `LOOKBACK_BARS` bars
    cannot fire."""
    if len(energies) != len(downbeats):
        raise ValueError("one energy per bar is required")
    drops: list[dict[str, float]] = []
    last_drop = -(LOOKBACK_BARS + 1)
    for i in range(1, len(downbeats)):
        if i - last_drop <= LOOKBACK_BARS:
            continue
        before = energies[max(0, i - LOOKBACK_BARS) : i]
        base = max(sum(before) / len(before), RMS_FLOOR)
        score = energies[i] / base
        if score >= DROP_RATIO:
            drops.append({"atMs": downbeats[i], "score": round(score, 2)})
            last_drop = i
    return drops


def shape_result(
    beats_s: Iterable[float],
    downbeats_s: Iterable[float],
    rms: Sequence[float],
    hop_ms: float,
    duration_ms: int,
) -> dict[str, object]:
    """The `beats` job's result data. Raises `NoBeats` for audio under `MIN_AUDIO_MS`, fewer than
    `MIN_BEATS` beats inside the file, or no RMS frame reaching `SILENCE_RMS`."""
    if duration_ms < MIN_AUDIO_MS:
        raise NoBeats("the audio is too short to hold a pulse")
    beats = marks_ms(beats_s, duration_ms)
    if len(beats) < MIN_BEATS:
        raise NoBeats("the engine found too few beats")
    if not rms or max(rms) < SILENCE_RMS:
        raise NoBeats("the audio is silent")
    downbeats = bars(beats, marks_ms(downbeats_s, duration_ms))
    ends = [*downbeats[1:], duration_ms]
    energies = [
        bar_rms(rms, hop_ms, start, end) for start, end in zip(downbeats, ends, strict=True)
    ]
    median_interval = statistics.median(b - a for a, b in pairwise(beats))
    return {
        "v": RESULT_VERSION,
        "beats": beats,
        "downbeats": downbeats,
        "bpm": round(60000 / median_interval, 2),
        "drops": drops_from_energy(downbeats, energies),
        "durationMs": duration_ms,
        "engine": ENGINE,
    }
