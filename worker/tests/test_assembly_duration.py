"""The duration plan decides the output length and how the picture is extended, for both
`compose_dub` (the voice sets the length) and `concat_video` (the longer of film and narration)."""

from __future__ import annotations

import pytest

from flowcortex_worker.assembly.duration import (
    DEFAULT_POLICY,
    EPS_SEC,
    POLICIES,
    WARN_THRESHOLD_SEC,
    plan,
    resolve_policy,
)
from flowcortex_worker.errors import PermanentError


def test_constants_are_xoods() -> None:
    assert POLICIES == ("match_audio", "match_video", "pad", "warn")
    assert DEFAULT_POLICY == "match_audio"
    assert (EPS_SEC, WARN_THRESHOLD_SEC) == (0.05, 0.5)


def test_a_voice_longer_than_the_clip_extends_it_by_cloning_the_last_frame() -> None:
    result = plan(5.87, 9.14, "match_audio", keep_longer=False)

    assert result.target == pytest.approx(9.14)
    assert result.pad_mode == "clone"
    assert result.pad_seconds == pytest.approx(3.27)
    assert result.tpad == "tpad=stop_mode=clone:stop_duration=3.270"


def test_a_difference_within_the_tolerance_extends_nothing() -> None:
    result = plan(10.00, 10.04, "match_audio", keep_longer=False)

    assert result.pad_mode is None
    assert result.pad_seconds == 0
    assert result.tpad is None


def test_a_difference_just_above_the_tolerance_extends() -> None:
    assert plan(10.0, 10.06, "match_audio", keep_longer=False).pad_mode == "clone"


def test_a_voice_shorter_than_the_clip_trims_the_clip_for_compose() -> None:
    result = plan(10.0, 6.0, "match_audio", keep_longer=False)

    assert result.target == 6.0
    assert result.pad_mode is None


def test_a_narration_shorter_than_the_film_keeps_the_film() -> None:
    result = plan(18.0, 12.0, "match_audio", keep_longer=True)

    assert result.target == 18.0
    assert result.pad_mode is None


def test_a_narration_longer_than_the_film_extends_it() -> None:
    result = plan(18.0, 25.0, "match_audio", keep_longer=True)

    assert result.target == 25.0
    assert result.pad_mode == "clone"
    assert result.pad_seconds == pytest.approx(7.0)
    assert result.tpad == "tpad=stop_mode=clone:stop_duration=7.000"


def test_pad_adds_a_black_tail() -> None:
    result = plan(6.0, 8.0, "pad", keep_longer=False)

    assert result.target == 8.0
    assert result.pad_mode == "add"
    assert result.pad_seconds == pytest.approx(2.0)
    assert result.tpad == "tpad=stop_mode=add:stop_duration=2.000"


@pytest.mark.parametrize("keep_longer", [False, True])
def test_match_video_keeps_the_picture_length(keep_longer: bool) -> None:
    result = plan(6.0, 8.0, "match_video", keep_longer=keep_longer)

    assert result.target == 6.0
    assert result.pad_mode is None
    assert not result.mismatch


def test_warn_keeps_the_picture_and_flags_a_mismatch_above_half_a_second() -> None:
    flagged = plan(6.0, 6.6, "warn", keep_longer=False)
    quiet = plan(6.0, 6.5, "warn", keep_longer=False)

    assert flagged.target == 6.0
    assert flagged.pad_mode is None
    assert flagged.mismatch
    assert not quiet.mismatch


def test_only_warn_flags_a_mismatch() -> None:
    assert not plan(6.0, 9.0, "match_audio", keep_longer=False).mismatch


def test_an_unknown_policy_names_the_parameter() -> None:
    with pytest.raises(PermanentError) as failure:
        plan(6.0, 8.0, "stretch", keep_longer=False)

    assert failure.value.code == "contract_violation"
    assert "durationPolicy" in failure.value.message


def test_a_missing_policy_resolves_to_the_default() -> None:
    assert resolve_policy(None) == "match_audio"
    assert resolve_policy("pad") == "pad"


@pytest.mark.parametrize("raw", ["stretch", "", 3, True, ["pad"]])
def test_a_policy_outside_the_set_is_refused(raw: object) -> None:
    with pytest.raises(PermanentError) as failure:
        resolve_policy(raw)

    assert failure.value.code == "contract_violation"
    assert "durationPolicy" in failure.value.message
