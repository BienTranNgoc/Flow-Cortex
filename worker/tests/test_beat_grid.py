"""Beat result rules: marks, bars, bar energy, drops and the refusal of audio without a pulse."""

from __future__ import annotations

import math

import pytest

from flowcortex_worker.audio.beat_grid import (
    ENGINE,
    NoBeats,
    bar_rms,
    bars,
    drops_from_energy,
    marks_ms,
    shape_result,
)

HOP_MS = 10.0


def loud(duration_ms: int, level: float = 0.2) -> list[float]:
    return [level] * int(duration_ms / HOP_MS)


# --- marks -------------------------------------------------------------------------------------


def test_marks_are_whole_ms_sorted_distinct_and_inside_the_file() -> None:
    assert marks_ms([1.0, 0.5, 0.5004, 10.2, -0.1, 0.0], 10_000) == [0, 500, 1000]


def test_marks_round_halves_up() -> None:
    assert marks_ms([0.0005], 1000) == [1]


def test_a_mark_at_the_very_end_is_kept() -> None:
    assert marks_ms([10.0], 10_000) == [10_000]


# --- bars --------------------------------------------------------------------------------------


def test_downbeats_equal_to_beats_are_rebuilt_every_four_beats() -> None:
    beats = list(range(0, 4001, 500))
    assert bars(beats, list(beats)) == [0, 2000, 4000]


def test_downbeats_of_four_beat_bars_are_kept() -> None:
    beats = list(range(0, 4001, 500))
    assert bars(beats, [0, 2000, 4000]) == [0, 2000, 4000]


def test_a_single_downbeat_is_rebuilt_from_the_nearest_beat() -> None:
    beats = list(range(0, 4001, 500))
    assert bars(beats, [1100]) == [1000, 3000]


def test_no_downbeat_rebuilds_from_the_first_beat() -> None:
    beats = [250, 750, 1250, 1750, 2250, 2750, 3250, 3750, 4250]
    assert bars(beats, []) == [250, 2250, 4250]


def test_median_beats_per_bar_decides_not_the_mean() -> None:
    beats = list(range(0, 8000, 500))
    # Bars hold 4, 4, 4, 2, 1, 1 beats: the median is 3, so the downbeats stay.
    kept = bars(beats, [0, 2000, 4000, 6000, 7000, 7500])
    assert kept == [0, 2000, 4000, 6000, 7000, 7500]


def test_no_beats_gives_no_bars() -> None:
    assert bars([], []) == []


# --- bar energy --------------------------------------------------------------------------------


def test_bar_rms_is_the_mean_of_the_frames_inside_the_bar() -> None:
    rms = [0.0] * 10 + [0.4] * 10 + [0.0] * 10
    assert bar_rms(rms, HOP_MS, 100, 200) == pytest.approx(0.4)
    assert bar_rms(rms, HOP_MS, 50, 150) == pytest.approx(0.2)


def test_bar_rms_of_an_empty_bar_is_zero() -> None:
    assert bar_rms([0.3] * 10, HOP_MS, 500, 500) == 0.0
    assert bar_rms([0.3] * 10, HOP_MS, 5000, 6000) == 0.0
    assert bar_rms([], HOP_MS, 0, 1000) == 0.0


# --- drops -------------------------------------------------------------------------------------

DOWNBEATS = [0, 2000, 4000, 6000, 8000, 10_000, 12_000]


def test_drop_after_quiet_bars() -> None:
    drops = drops_from_energy(DOWNBEATS[:5], [0.1, 0.1, 0.1, 0.1, 0.5])
    assert drops == [{"atMs": 8000, "score": 5.0}]


def test_flat_energy_has_no_drop() -> None:
    assert drops_from_energy(DOWNBEATS[:5], [0.3] * 5) == []


def test_slow_rise_stays_below_the_ratio() -> None:
    assert drops_from_energy(DOWNBEATS[:5], [0.1, 0.15, 0.2, 0.25, 0.3]) == []


def test_the_ratio_decides() -> None:
    assert len(drops_from_energy(DOWNBEATS[:2], [0.1, 0.2])) == 1
    assert drops_from_energy(DOWNBEATS[:2], [0.1, 0.199]) == []


def test_loud_bars_after_a_drop_do_not_fire_again() -> None:
    drops = drops_from_energy(DOWNBEATS[:6], [0.1, 0.1, 0.5, 0.5, 0.5, 0.5])
    assert drops == [{"atMs": 4000, "score": 5.0}]


def test_a_second_drop_after_a_break() -> None:
    energies = [0.1, 0.1, 0.5, 0.5, 0.5, 0.5, 0.05, 0.05, 0.05, 0.05, 0.4]
    downbeats = [i * 2000 for i in range(len(energies))]
    drops = drops_from_energy(downbeats, energies)
    assert [d["atMs"] for d in drops] == [4000, 20_000]


def test_the_first_bar_never_fires() -> None:
    assert drops_from_energy([0], [5.0]) == []
    assert drops_from_energy(DOWNBEATS[:3], [5.0, 5.0, 5.0]) == []


def test_the_silence_floor_keeps_the_score_finite() -> None:
    drops = drops_from_energy(DOWNBEATS[:5], [0.0, 0.0, 0.0, 0.0, 0.5])
    assert len(drops) == 1
    assert math.isfinite(drops[0]["score"])
    assert drops[0]["score"] == 500.0


def test_the_lookback_is_the_four_bars_before() -> None:
    # Mean of the last four bars (0.1 each) is the base, not the loud first bar.
    energies = [1.0, 0.1, 0.1, 0.1, 0.1, 0.3]
    drops = drops_from_energy([i * 2000 for i in range(6)], energies)
    assert drops == [{"atMs": 10_000, "score": 3.0}]


def test_fewer_than_two_downbeats_has_no_drop() -> None:
    assert drops_from_energy([], []) == []
    assert drops_from_energy([1000], [0.9]) == []


def test_one_energy_per_bar_is_required() -> None:
    with pytest.raises(ValueError, match="one energy per bar"):
        drops_from_energy(DOWNBEATS[:3], [0.1, 0.2])


# --- shape_result ------------------------------------------------------------------------------


def test_the_result_is_sorted_distinct_inside_the_file_and_versioned() -> None:
    beats_s = [2.0, 0.0, 0.5, 0.5, 1.0, 1.5, 2.5, 3.0, 12.0]
    result = shape_result(beats_s, [0.0, 2.0], loud(4000), HOP_MS, 4000)
    assert result["v"] == 1
    assert result["engine"] == ENGINE == "beat-this/1.1.0+final0"
    assert result["beats"] == [0, 500, 1000, 1500, 2000, 2500, 3000]
    assert result["durationMs"] == 4000
    assert result["drops"] == []


def test_bpm_is_the_median_interval_so_a_missed_beat_does_not_skew_it() -> None:
    beats_s = [0.0, 0.5, 1.0, 1.5, 2.5, 3.0]
    result = shape_result(beats_s, [0.0], loud(4000), HOP_MS, 4000)
    assert result["bpm"] == 120.0


def test_bpm_is_rounded_to_two_decimals() -> None:
    beats_s = [0.0, 0.4, 0.8, 1.2, 1.6]
    result = shape_result(beats_s, [0.0], loud(4000), HOP_MS, 4000)
    assert result["bpm"] == 150.0
    odd = shape_result([0.0, 0.7, 1.4, 2.1, 2.8], [0.0], loud(4000), HOP_MS, 4000)
    assert odd["bpm"] == 85.71


def test_downbeats_are_rebuilt_when_bars_are_one_beat_long() -> None:
    beats_s = [i * 0.5 for i in range(9)]
    result = shape_result(beats_s, beats_s, loud(4000), HOP_MS, 4000)
    assert result["downbeats"] == [0, 2000, 4000]


def test_a_drop_is_found_from_the_rms_frames() -> None:
    beats_s = [i * 0.5 for i in range(21)]
    downbeats_s = [0.0, 2.0, 4.0, 6.0, 8.0]
    rms = loud(8000, 0.1) + loud(2000, 0.5)
    result = shape_result(beats_s, downbeats_s, rms, HOP_MS, 10_000)
    assert result["downbeats"] == [0, 2000, 4000, 6000, 8000]
    assert result["drops"] == [{"atMs": 8000, "score": 5.0}]


def test_digital_silence_with_hallucinated_beats_is_refused() -> None:
    beats_s = [i * 0.5 for i in range(8)]
    with pytest.raises(NoBeats):
        shape_result(beats_s, [0.0], [0.0] * 400, HOP_MS, 4000)


def test_too_few_beats_is_refused() -> None:
    with pytest.raises(NoBeats):
        shape_result([0.0, 0.5, 1.0], [0.0], loud(4000), HOP_MS, 4000)


def test_beats_outside_the_file_do_not_count_towards_the_minimum() -> None:
    with pytest.raises(NoBeats):
        shape_result([0.0, 0.5, 1.0, 11.0], [0.0], loud(4000), HOP_MS, 4000)


def test_audio_shorter_than_two_seconds_is_refused() -> None:
    beats_s = [0.0, 0.3, 0.6, 0.9, 1.2]
    with pytest.raises(NoBeats):
        shape_result(beats_s, [0.0], loud(1999), HOP_MS, 1999)
