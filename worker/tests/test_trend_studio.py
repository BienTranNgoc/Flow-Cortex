"""Pure rules of role suggestion: box filtering and the scene list checks."""

from __future__ import annotations

from typing import Any

import pytest

from flowcortex_worker.errors import PermanentError
from flowcortex_worker.trend_studio.boxes import person_boxes
from flowcortex_worker.trend_studio.scenes import (
    MAX_END_FRAME,
    MAX_SCENES,
    Scene,
    key_frames,
    validate_scenes,
)


def contiguous(count: int, length: int) -> list[dict[str, Any]]:
    return [
        {"index": i, "startFrame": i * length, "endFrame": (i + 1) * length} for i in range(count)
    ]


def rejection(raw: Any) -> PermanentError:
    with pytest.raises(PermanentError) as caught:
        validate_scenes(raw)
    assert caught.value.code == "contract_violation"
    return caught.value


def test_boxes_are_filtered_clamped_and_ordered() -> None:
    raw = [
        (16, 9, 80, 90, 0.49),
        (16, 9, 80, 90, 0.5),
        (-10, -5, 160, 45, 0.9),
        (50, 10, 50, 40, 0.99),
    ]

    assert person_boxes(raw, 160, 90) == [[0.0, 0.0, 1.0, 0.5, 0.9], [0.1, 0.1, 0.5, 1.0, 0.5]]


def test_scene_frames_output_is_accepted_with_extra_keys() -> None:
    raw = [
        {"index": 0, "startFrame": 0, "endFrame": 24, "frame": "frame_000.jpg"},
        {"index": 1, "startFrame": 24, "endFrame": 72, "frame": "frame_001.jpg"},
    ]

    assert validate_scenes(raw) == [Scene(0, 0, 24), Scene(1, 24, 72)]


@pytest.mark.parametrize(
    ("raw", "position"),
    [
        ([], None),
        ("0,24", None),
        (None, None),
        ([{"index": 1, "startFrame": 0, "endFrame": 24}], 0),
        (
            [
                {"index": 0, "startFrame": 0, "endFrame": 24},
                {"index": 1, "startFrame": 30, "endFrame": 72},
            ],
            1,
        ),
        ([{"index": 0, "startFrame": 0, "endFrame": 0}], 0),
        ([{"index": 0, "startFrame": True, "endFrame": 24}], 0),
        ([{"index": 0, "startFrame": 0, "endFrame": 24.0}], 0),
        (["not a dict"], 0),
        ([{"index": 0, "startFrame": 0}], 0),
    ],
    ids=[
        "empty",
        "string",
        "none",
        "index_off",
        "gap",
        "empty_scene",
        "bool_start",
        "float_end",
        "not_an_object",
        "missing_end",
    ],
)
def test_a_malformed_scene_list_is_a_contract_violation(raw: Any, position: int | None) -> None:
    error = rejection(raw)

    if position is not None:
        assert f"scene {position}" in error.message


def test_two_hundred_and_eighty_nine_scenes_are_refused() -> None:
    error = rejection(contiguous(MAX_SCENES + 1, 4))

    assert "288" in error.message


def test_two_hundred_and_eighty_eight_scenes_ending_at_the_ceiling_pass() -> None:
    scenes = validate_scenes(contiguous(288, 5))

    assert len(scenes) == MAX_SCENES
    assert scenes[-1].end_frame == MAX_END_FRAME == 1440


def test_a_scene_past_sixty_seconds_is_refused() -> None:
    error = rejection([{"index": 0, "startFrame": 0, "endFrame": 1441}])

    assert "1440" in error.message


def test_key_frames_are_scene_middles() -> None:
    scenes = [Scene(0, 0, 24), Scene(1, 24, 72), Scene(2, 72, 96)]

    assert key_frames(scenes) == [12, 48, 84]
