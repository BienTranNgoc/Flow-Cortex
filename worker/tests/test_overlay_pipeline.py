"""The clean pipeline with fake engines: masks, face guard, passes, alpha and result data."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

import numpy as np
from numpy.typing import NDArray

from flowcortex_worker.overlay.params import OverlayParams, parse_params
from flowcortex_worker.overlay.pipeline import clean

Image = NDArray[np.uint8]
Mask = NDArray[np.uint8]

RESULT_KEYS = {
    "width",
    "height",
    "regionMode",
    "regionsRemoved",
    "textRegionsFound",
    "maskRatio",
    "faceGuarded",
    "watermarkRemaining",
    "inpaintPasses",
    "textLayers",
}


def photo(height: int = 100, width: int = 200) -> Image:
    rng = np.random.default_rng(7)
    return rng.integers(60, 140, (height, width, 3)).astype(np.uint8)


def whiten(bgr: Image, _mask: Mask) -> Image:
    return np.full_like(bgr, 255)


@dataclass
class FakeEngines:
    boxes: list[tuple[int, int, int, int]] = field(default_factory=list)
    items: list[dict[str, Any]] = field(default_factory=list)
    faces_found: list[tuple[int, int, int, int]] = field(default_factory=list)
    inpainter: Callable[[Image, Mask], Image] = whiten
    inpaint_masks: list[Mask] = field(default_factory=list)
    inpaint_inputs: list[Image] = field(default_factory=list)
    item_calls: int = 0

    def text_boxes(self, bgr: Image) -> list[tuple[int, int, int, int]]:
        return self.boxes

    def text_items(self, bgr: Image) -> list[dict[str, Any]]:
        self.item_calls += 1
        return self.items

    def faces(self, bgr: Image) -> list[tuple[int, int, int, int]]:
        return self.faces_found

    def inpaint(self, bgr: Image, mask: Mask) -> Image:
        self.inpaint_inputs.append(bgr)
        self.inpaint_masks.append(mask.copy())
        return self.inpainter(bgr, mask)


def manual(
    boxes: list[dict[str, float]], strength: int = 1, extract: bool = False
) -> OverlayParams:
    return parse_params(
        {
            "region": "manual",
            "customRegions": boxes,
            "strength": strength,
            "extractText": extract,
        }
    )


TOP_BAND = [{"x": 0.0, "y": 0.0, "w": 1.0, "h": 0.2}]


def test_manual_box_is_the_mask_and_counts_its_boxes() -> None:
    bgr = photo()
    engines = FakeEngines()
    out, data = clean(bgr, manual(TOP_BAND), engines)
    assert data["regionsRemoved"] == 1
    assert data["regionMode"] == "manual"
    assert data["watermarkRemaining"] is False
    assert (out[:20] == 255).all()
    assert np.array_equal(out[25:], bgr[25:])


def test_auto_with_ocr_boxes_counts_them() -> None:
    bgr = np.full((200, 200, 3), 20, np.uint8)
    bgr[30:40, 30:60] = 255
    bgr[130:140, 100:140] = 255
    engines = FakeEngines(boxes=[(25, 65, 25, 45), (95, 145, 125, 145)])
    _, data = clean(bgr, parse_params({}), engines)
    assert data["regionsRemoved"] == 2
    assert data["textRegionsFound"] == 2
    assert data["regionMode"] == "auto"
    assert engines.inpaint_masks[0][35, 45] == 1


def test_confirmed_repeated_watermark_joins_the_mask_and_the_count() -> None:
    bgr = np.full((200, 200, 3), 20, np.uint8)
    bgr[30:40, 30:60] = 255
    repeated = np.zeros((200, 200), np.uint8)
    repeated[150:170, 20:60] = 1
    engines = FakeEngines(boxes=[(25, 65, 25, 45)])
    _, data = clean(bgr, parse_params({}), engines, find_repeated=lambda _b, _d: (repeated, 3))
    assert data["regionsRemoved"] == 3
    assert data["watermarkRemaining"] is False
    assert engines.inpaint_masks[0][160, 40] == 1
    assert engines.inpaint_masks[0][35, 45] == 1


def test_a_faint_corner_mark_with_no_confirmed_repeat_is_reported_as_remaining() -> None:
    bgr = np.full((400, 400, 3), 100, np.uint8)
    for column in range(5, 45):
        if (column - 5) % 6 < 3:
            bgr[5:45, column] = 120
    engines = FakeEngines()
    no_repeat = np.zeros((400, 400), np.uint8)
    out, data = clean(bgr, parse_params({}), engines, find_repeated=lambda _b, _d: (no_repeat, 0))
    assert data["watermarkRemaining"] is True
    assert data["regionsRemoved"] == 0
    assert np.array_equal(out, bgr)
    assert engines.inpaint_masks == []


def test_a_caption_crossing_a_face_leaves_the_widened_face_box_alone() -> None:
    bgr = photo()
    engines = FakeEngines(faces_found=[(80, 0, 40, 40)])
    out, data = clean(bgr, manual(TOP_BAND), engines)
    assert data["faceGuarded"] is True
    assert np.array_equal(out[0:47, 73:127], bgr[0:47, 73:127])
    assert (out[0:20, 0:70] == 255).all()


def test_no_face_means_not_guarded() -> None:
    _, data = clean(photo(), manual(TOP_BAND), FakeEngines())
    assert data["faceGuarded"] is False


def test_nothing_to_remove_returns_the_input_and_never_inpaints() -> None:
    bgr = photo()
    engines = FakeEngines()
    out, data = clean(bgr, parse_params({}), engines)
    assert np.array_equal(out, bgr)
    assert engines.inpaint_masks == []
    assert (data["regionsRemoved"], data["textRegionsFound"]) == (0, 0)
    assert (data["maskRatio"], data["inpaintPasses"]) == (0.0, 0)


def test_a_mask_fully_inside_a_face_is_the_empty_result() -> None:
    bgr = photo()
    engines = FakeEngines(faces_found=[(0, 0, 200, 100)])
    out, data = clean(bgr, manual(TOP_BAND), engines)
    assert np.array_equal(out, bgr)
    assert engines.inpaint_masks == []
    assert data["faceGuarded"] is True
    assert data["inpaintPasses"] == 0


def test_an_inpainter_that_alters_the_whole_frame_changes_only_the_mask() -> None:
    bgr = photo()
    engines = FakeEngines()
    out, _ = clean(bgr, manual(TOP_BAND), engines)
    first_mask = engines.inpaint_masks[0] > 0
    assert np.array_equal(out[~first_mask], bgr[~first_mask])
    assert (out[first_mask] == 255).all()


def spotted(bgr: Image, _mask: Mask) -> Image:
    out = np.full_like(bgr, 100)
    out[5:11, 20:26] = 230
    return out


def flat(bgr: Image, _mask: Mask) -> Image:
    return np.full_like(bgr, 100)


def test_residue_inside_the_first_mask_is_inpainted_once_more() -> None:
    results = iter([spotted, flat])
    engines = FakeEngines(inpainter=lambda b, m: next(results)(b, m))
    _, data = clean(photo(), manual(TOP_BAND), engines)
    assert data["inpaintPasses"] == 2
    first, second = engines.inpaint_masks
    assert second.any()
    assert (second <= first).all()


def test_clean_first_pass_reports_one_pass() -> None:
    engines = FakeEngines(inpainter=flat)
    _, data = clean(photo(), manual(TOP_BAND), engines)
    assert data["inpaintPasses"] == 1
    assert len(engines.inpaint_masks) == 1


def test_text_layers_are_empty_and_unread_unless_asked() -> None:
    engines = FakeEngines(items=[{"box": (10, 100, 10, 40), "text": "hi", "confidence": 0.9}])
    _, data = clean(photo(), manual(TOP_BAND), engines)
    assert data["textLayers"] == []
    assert engines.item_calls == 0


def test_text_layers_are_returned_when_asked() -> None:
    engines = FakeEngines(items=[{"box": (10, 100, 10, 40), "text": "hi", "confidence": 0.9}])
    _, data = clean(photo(), manual(TOP_BAND, extract=True), engines)
    assert [layer["text"] for layer in data["textLayers"]] == ["hi"]
    assert engines.item_calls == 1


def test_text_layers_are_returned_even_when_nothing_is_masked() -> None:
    engines = FakeEngines(items=[{"box": (10, 100, 10, 40), "text": "hi", "confidence": 0.9}])
    _, data = clean(photo(), parse_params({"extractText": True}), engines)
    assert [layer["text"] for layer in data["textLayers"]] == ["hi"]


def test_mask_ratio_is_rounded_to_four_decimals_and_data_has_exactly_the_listed_keys() -> None:
    bgr = photo(30, 70)
    boxes = [{"x": 0.0, "y": 0.0, "w": 1 / 3, "h": 1 / 3}]
    engines = FakeEngines(inpainter=flat)
    _, data = clean(bgr, manual(boxes, strength=1), engines)
    mask = engines.inpaint_masks[0]
    assert data["maskRatio"] == round(float(mask.mean()), 4)
    assert len(str(data["maskRatio"]).split(".")[1]) <= 4
    assert set(data) == RESULT_KEYS
    assert (data["width"], data["height"]) == (70, 30)


def test_alpha_is_kept_byte_for_byte_and_hidden_from_the_inpainter() -> None:
    rng = np.random.default_rng(3)
    bgr = photo()
    alpha = rng.integers(0, 256, bgr.shape[:2]).astype(np.uint8)
    bgra = np.dstack([bgr, alpha])
    engines = FakeEngines()
    out, _ = clean(bgra, manual(TOP_BAND), engines)
    assert out.shape == (100, 200, 4)
    assert np.array_equal(out[:, :, 3], alpha)
    assert all(image.shape[2] == 3 for image in engines.inpaint_inputs)
    assert np.array_equal(out[25:, :, :3], bgr[25:])
