"""Window plan, blend weights and the whole-audio mean/std accumulator."""

from __future__ import annotations

import statistics
from itertools import pairwise

import pytest

from flowcortex_worker.audio.windows import (
    MeanStd,
    NoSamples,
    Window,
    blend_weights,
    window_plan,
)

RATE = 44100


def test_window_plan_of_a_150_second_clip() -> None:
    plan = window_plan(150 * RATE, RATE)
    assert plan == [
        Window(start=0, length=60 * RATE, shared=4 * RATE),
        Window(start=56 * RATE, length=60 * RATE, shared=4 * RATE),
        Window(start=112 * RATE, length=38 * RATE, shared=0),
    ]


def test_clip_shorter_than_one_window_is_a_single_window() -> None:
    assert window_plan(20 * RATE, RATE) == [Window(start=0, length=20 * RATE, shared=0)]


def test_clip_of_exactly_one_window_has_no_blend() -> None:
    assert window_plan(60 * RATE, RATE) == [Window(start=0, length=60 * RATE, shared=0)]


def test_clip_just_over_one_window_shares_the_overlap() -> None:
    plan = window_plan(61 * RATE, RATE)
    assert plan == [
        Window(start=0, length=60 * RATE, shared=4 * RATE),
        Window(start=56 * RATE, length=5 * RATE, shared=0),
    ]


def test_windows_cover_the_audio_end_to_end() -> None:
    frames = 333 * RATE + 17
    plan = window_plan(frames, RATE)
    assert plan[0].start == 0
    assert plan[-1].start + plan[-1].length == frames
    for here, nxt in pairwise(plan):
        assert here.start + here.length - nxt.start == here.shared


def test_no_frames_is_refused() -> None:
    with pytest.raises(NoSamples):
        window_plan(0, RATE)


def test_blend_weights_of_four_samples() -> None:
    assert blend_weights(4) == [0.125, 0.375, 0.625, 0.875]


@pytest.mark.parametrize("count", [1, 2, 7, 176400])
def test_blend_weights_pair_with_their_complement_to_one(count: int) -> None:
    weights = blend_weights(count)
    assert len(weights) == count
    assert all(0 < w < 1 for w in weights)
    assert weights == sorted(weights)
    assert all(abs(weights[i] + weights[-1 - i] - 1) < 1e-12 for i in range(count))


def test_mean_std_over_two_chunks_equals_the_one_pass_value() -> None:
    values = [0.1, -0.4, 0.25, 0.9, -0.7, 0.05, 0.33]
    acc = MeanStd()
    acc.update(values[:3])
    acc.update(values[3:])
    assert acc.mean == pytest.approx(statistics.fmean(values))
    assert acc.std == pytest.approx(statistics.stdev(values))


def test_constant_input_has_zero_std() -> None:
    acc = MeanStd()
    acc.update([0.5] * 10)
    assert acc.std == 0.0


def test_a_single_sample_has_zero_std() -> None:
    acc = MeanStd()
    acc.update([0.5])
    assert acc.mean == 0.5
    assert acc.std == 0.0


def test_empty_accumulator_is_refused() -> None:
    acc = MeanStd()
    acc.update([])
    with pytest.raises(NoSamples):
        _ = acc.mean
    with pytest.raises(NoSamples):
        _ = acc.std
