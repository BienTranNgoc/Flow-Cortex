"""Loudness gains put the voice at -16 LUFS and the music and effects at -20 LUFS."""

from __future__ import annotations

import pytest

from flowcortex_worker.assembly.loudness import (
    BG_TARGET_LUFS,
    SFX_TARGET_LUFS,
    SILENCE_LUFS,
    VOICE_TARGET_LUFS,
    effects_gain,
    gains,
)


def test_targets_are_xoods() -> None:
    assert (VOICE_TARGET_LUFS, BG_TARGET_LUFS, SFX_TARGET_LUFS, SILENCE_LUFS) == (
        -16.0,
        -20.0,
        -20.0,
        -60.0,
    )


def test_a_quiet_voice_over_a_mastered_song() -> None:
    assert gains(voice=-22.8, background=-9.4) == (6.80, -10.60)


def test_a_quiet_background_is_never_raised() -> None:
    assert gains(voice=-16.0, background=-30.0)[1] == 0


def test_the_voice_gain_is_clamped_up() -> None:
    assert gains(voice=-40.0, background=None)[0] == 12


def test_the_voice_gain_is_clamped_down() -> None:
    assert gains(voice=-5.0, background=None)[0] == -11
    assert gains(voice=15.0, background=None)[0] == -20


def test_a_silent_effects_track_gets_no_gain() -> None:
    assert effects_gain(-70.0) == 0


def test_effects_follow_the_same_range_as_the_voice() -> None:
    assert effects_gain(-25.0) == 5.0
    assert effects_gain(-50.0) == 12
    assert effects_gain(-0.5) == -19.5


def test_a_source_below_the_silence_floor_is_left_alone() -> None:
    assert gains(voice=-61.0, background=-61.0) == (0, 0)


def test_the_floor_itself_is_still_measured() -> None:
    assert gains(voice=SILENCE_LUFS, background=None)[0] == 12


def test_an_unmeasurable_source_gets_no_gain() -> None:
    assert gains(voice=None, background=None) == (0, 0)
    assert effects_gain(None) == 0


def test_gains_are_rounded_to_a_hundredth_of_a_decibel() -> None:
    voice, background = gains(voice=-22.8333, background=-9.4166)

    assert voice == pytest.approx(6.83)
    assert background == pytest.approx(-10.58)
    assert voice == round(voice, 2)
