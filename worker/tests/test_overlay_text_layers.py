"""Text layers: filtering, line merging, font size, alignment, colour and order."""

from __future__ import annotations

from typing import Any

import numpy as np
import pytest
from numpy.typing import NDArray

from flowcortex_worker.overlay.text_layers import text_layers_from_items

Image = NDArray[np.uint8]


def blank(height: int = 1000, width: int = 1000, value: int = 128) -> Image:
    return np.full((height, width, 3), value, np.uint8)


def item(box: tuple[int, int, int, int], text: str = "word", confidence: float = 0.9) -> Any:
    return {"box": box, "text": text, "confidence": confidence}


def test_low_confidence_item_is_dropped() -> None:
    assert text_layers_from_items(blank(), [item((100, 300, 100, 150), confidence=0.3)]) == []


def test_box_narrower_than_one_percent_of_the_width_is_dropped() -> None:
    assert text_layers_from_items(blank(), [item((100, 105, 100, 150))]) == []


def test_fragments_of_one_line_merge_left_to_right_whatever_the_input_order() -> None:
    right = item((420, 600, 100, 150), "world", 0.8)
    left = item((100, 400, 100, 150), "hello", 0.95)
    layers = text_layers_from_items(blank(), [right, left])
    assert len(layers) == 1
    assert layers[0]["text"] == "hello world"
    assert layers[0]["confidence"] == 0.8
    assert layers[0]["x"] == pytest.approx(0.1)
    assert layers[0]["w"] == pytest.approx(0.5)


def test_fragments_overlapping_by_seven_tenths_with_a_half_height_gap_merge() -> None:
    first = item((100, 300, 100, 150), "one")
    second = item((325, 500, 115, 165), "two")
    layers = text_layers_from_items(blank(), [first, second])
    assert [layer["text"] for layer in layers] == ["one two"]


def test_labels_a_line_and_a_half_apart_stay_separate() -> None:
    left = item((100, 300, 100, 150), "left")
    right = item((375, 600, 100, 150), "right")
    layers = text_layers_from_items(blank(), [left, right])
    assert [layer["text"] for layer in layers] == ["left", "right"]


def test_font_size_is_a_fixed_share_of_the_box_height() -> None:
    layers = text_layers_from_items(blank(), [item((100, 300, 100, 191))])
    assert layers[0]["fontSizeRatio"] == pytest.approx(0.06188)


@pytest.mark.parametrize(
    ("box", "align"),
    [
        ((200, 400, 100, 150), "left"),
        ((400, 600, 100, 150), "center"),
        ((600, 800, 100, 150), "right"),
    ],
)
def test_alignment_follows_the_box_centre(box: tuple[int, int, int, int], align: str) -> None:
    assert text_layers_from_items(blank(), [item(box)])[0]["align"] == align


def test_white_text_on_black_reads_as_white() -> None:
    bgr = blank(value=0)
    bgr[110:140, 110:290:6] = 255
    bgr[110:140, 111:290:6] = 255
    layers = text_layers_from_items(bgr, [item((100, 300, 100, 150))])
    assert layers[0]["color"] == "#ffffff"


def test_one_colour_box_returns_that_colour() -> None:
    bgr = blank()
    bgr[100:150, 100:300] = (0, 128, 255)
    layers = text_layers_from_items(bgr, [item((100, 300, 100, 150))])
    assert layers[0]["color"] == "#ff8000"


def test_large_junk_box_does_not_swallow_real_lines() -> None:
    junk = item((50, 900, 50, 400), "junk", 0.2)
    first = item((100, 400, 100, 150), "first")
    second = item((100, 400, 250, 300), "second")
    layers = text_layers_from_items(blank(), [junk, first, second])
    assert [layer["text"] for layer in layers] == ["first", "second"]


def test_layers_come_out_by_row_then_x() -> None:
    items = [
        item((500, 700, 300, 350), "c"),
        item((100, 300, 100, 150), "a"),
        item((700, 900, 100, 150), "b"),
    ]
    layers = text_layers_from_items(blank(), items)
    assert [layer["text"] for layer in layers] == ["a", "b", "c"]


def test_layer_carries_every_field() -> None:
    layer = text_layers_from_items(blank(), [item((100, 300, 100, 150), "hi")])[0]
    assert set(layer) == {
        "text",
        "x",
        "y",
        "w",
        "h",
        "fontSizeRatio",
        "color",
        "align",
        "confidence",
    }
