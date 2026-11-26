"""Gains that bring the voice, the music and the effects to their target loudness."""

from __future__ import annotations

import math

VOICE_TARGET_LUFS = -16.0
# Music sits 4 LU under the voice; the sidechain then ducks it further while someone speaks.
BG_TARGET_LUFS = VOICE_TARGET_LUFS - 4.0
SFX_TARGET_LUFS = -20.0
GAIN_MIN_DB = -20.0
GAIN_MAX_DB = 12.0
# ebur128 reports -70 for digital silence; amplifying it only raises the noise floor.
SILENCE_LUFS = -60.0


def unusable_reason(lufs: float | None) -> str | None:
    """Why a source keeps its level (unmeasurable or silent), or None when it is measurable."""
    if lufs is None:
        return "its loudness could not be measured"
    if lufs < SILENCE_LUFS:
        return f"it is silent ({lufs:.1f} LUFS, below {SILENCE_LUFS:.0f})"
    return None


def _toward(target: float, lufs: float | None, low: float, high: float) -> float:
    if lufs is None or unusable_reason(lufs) is not None:
        return 0.0
    return round(min(high, max(low, target - lufs)), 2)


def gains(voice: float | None, background: float | None) -> tuple[float, float]:
    """(voice, background) in dB: the voice to -16 LUFS within [-20, +12], the background to
    -20 LUFS but only ever lowered; an unmeasurable or silent source gets 0."""
    return (
        _toward(VOICE_TARGET_LUFS, voice, GAIN_MIN_DB, GAIN_MAX_DB),
        _toward(BG_TARGET_LUFS, background, -math.inf, 0.0),
    )


def effects_gain(effects: float | None) -> float:
    """Effects to -20 LUFS within [-20, +12] dB; an unmeasurable or silent source gets 0."""
    return _toward(SFX_TARGET_LUFS, effects, GAIN_MIN_DB, GAIN_MAX_DB)
