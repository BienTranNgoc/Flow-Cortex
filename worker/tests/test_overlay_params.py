"""Overlay parameters: defaults, strength mapping, closed lists and refusals."""

from __future__ import annotations

import math
from typing import Any

import pytest

from flowcortex_worker.errors import PermanentError
from flowcortex_worker.overlay.params import OverlayParams, RatioBox, parse_params


def test_empty_params_are_the_defaults() -> None:
    assert parse_params({}) == OverlayParams(
        region="auto", boxes=(), strength=3, dilate_px=3, extract_text=False
    )


@pytest.mark.parametrize(("strength", "dilate"), [(1, 1), (2, 2), (3, 3), (4, 5), (5, 7)])
def test_strength_maps_to_dilation_pixels(strength: int, dilate: int) -> None:
    assert parse_params({"strength": strength}).dilate_px == dilate


@pytest.mark.parametrize("strength", [6, 0, True, 2.5, "3"])
def test_out_of_range_strength_is_refused_with_the_range(strength: Any) -> None:
    with pytest.raises(PermanentError, match="1-5") as caught:
        parse_params({"strength": strength})
    assert caught.value.code == "contract_violation"


def test_unknown_region_names_the_allowed_values() -> None:
    with pytest.raises(PermanentError, match="auto") as caught:
        parse_params({"region": "corners"})
    assert "manual" in caught.value.message
    assert caught.value.code == "contract_violation"


@pytest.mark.parametrize("regions", [None, []])
def test_manual_without_boxes_is_refused(regions: Any) -> None:
    raw: dict[str, Any] = {"region": "manual"}
    if regions is not None:
        raw["customRegions"] = regions
    with pytest.raises(PermanentError, match="at least one box"):
        parse_params(raw)


@pytest.mark.parametrize(
    "box",
    [
        {"x": 1.2, "y": 0, "w": 0.1, "h": 0.1},
        {"x": 0, "y": 0, "w": -0.1, "h": 0.1},
        {"x": 0, "y": math.nan, "w": 0.1, "h": 0.1},
        {"x": 0, "y": math.inf, "w": 0.1, "h": 0.1},
        {"x": 0, "y": 0, "w": 0.1},
        {"x": "0", "y": 0, "w": 0.1, "h": 0.1},
        {"x": True, "y": 0, "w": 0.1, "h": 0.1},
        {"x": 0, "y": 0, "w": 0.1, "h": 0.1, "z": 1},
        "box",
    ],
)
def test_bad_box_is_refused(box: Any) -> None:
    with pytest.raises(PermanentError, match="0-1") as caught:
        parse_params({"region": "manual", "customRegions": [box]})
    assert caught.value.code == "contract_violation"


def test_valid_boxes_are_kept_as_ratio_boxes() -> None:
    parsed = parse_params(
        {"region": "manual", "customRegions": [{"x": 0.5, "y": 0, "w": 0.5, "h": 1}]}
    )
    assert parsed.boxes == (RatioBox(0.5, 0.0, 0.5, 1.0),)
    assert parsed.region == "manual"


def test_auto_keeps_boxes_unused() -> None:
    parsed = parse_params({"customRegions": [{"x": 0, "y": 0, "w": 0.1, "h": 0.1}]})
    assert parsed.region == "auto"
    assert len(parsed.boxes) == 1


def test_extract_text_must_be_boolean() -> None:
    assert parse_params({"extractText": True}).extract_text is True
    with pytest.raises(PermanentError, match="extractText"):
        parse_params({"extractText": "true"})


def test_unknown_key_names_the_accepted_keys() -> None:
    with pytest.raises(PermanentError) as caught:
        parse_params({"strenght": 3})
    for key in ("region", "customRegions", "strength", "extractText"):
        assert key in caught.value.message
    assert caught.value.code == "contract_violation"


def test_params_that_are_not_an_object_are_refused() -> None:
    with pytest.raises(PermanentError, match="object"):
        parse_params([])  # type: ignore[arg-type]
