"""Numbered inputs are complete, ordered by their number and bounded; run-bound keys are ignored."""

from __future__ import annotations

from pathlib import Path

import pytest

from flowcortex_worker.assembly.inputs import numbered
from flowcortex_worker.errors import PermanentError


def files(*keys: str) -> dict[str, Path]:
    return {key: Path(f"/work/{key}") for key in keys}


def refusal(
    inputs: dict[str, Path], prefix: str = "audio", extra: frozenset[str] = frozenset()
) -> str:
    with pytest.raises(PermanentError) as failure:
        numbered(inputs, prefix, 2, 50, extra)
    assert failure.value.code == "contract_violation"
    return failure.value.message


def test_order_follows_the_number_not_the_key_text() -> None:
    keys = [f"audio_{n}" for n in (10, 2, 1, 9, 3, 4, 5, 6, 7, 8)]

    ordered, extras = numbered(files(*keys), "audio", 2, 50)

    assert [path.name for path in ordered] == [f"audio_{n}" for n in range(1, 11)]
    assert extras == {}


def test_a_gap_names_the_missing_key() -> None:
    assert "audio_2" in refusal(files("audio_1", "audio_3"))


def test_a_zero_padded_key_is_refused_by_name() -> None:
    assert "audio_01" in refusal(files("audio_01", "audio_1", "audio_2"))


def test_a_foreign_key_is_refused_by_name() -> None:
    assert "music" in refusal(files("audio_1", "audio_2", "music"))


def test_the_bound_upload_and_product_images_are_ignored() -> None:
    ordered, extras = numbered(files("media", "image_1", "audio_2", "audio_1"), "audio", 2, 50)

    assert [path.name for path in ordered] == ["audio_1", "audio_2"]
    assert extras == {}


def test_a_mistyped_role_is_refused_even_when_roles_are_allowed() -> None:
    message = refusal(files("clip_1", "clip_2", "narraton"), "clip", frozenset({"narration"}))

    assert "narraton" in message


def test_an_allowed_role_is_returned_apart() -> None:
    inputs = files("clip_2", "narration", "clip_1")

    ordered, extras = numbered(inputs, "clip", 2, 20, extra=frozenset({"narration"}))

    assert [path.name for path in ordered] == ["clip_1", "clip_2"]
    assert extras == {"narration": Path("/work/narration")}


def test_too_few_inputs_name_the_bounds() -> None:
    message = refusal(files("audio_1"))

    assert "2" in message
    assert "50" in message


def test_too_many_inputs_name_the_bounds() -> None:
    message = refusal(files(*(f"audio_{n}" for n in range(1, 52))))

    assert "2" in message
    assert "50" in message


def test_no_numbered_input_at_all_names_the_bounds() -> None:
    assert "2 to 50" in refusal(files("media"))


def test_the_upper_bound_is_accepted() -> None:
    ordered, _ = numbered(files(*(f"audio_{n}" for n in range(1, 51))), "audio", 2, 50)

    assert len(ordered) == 50
