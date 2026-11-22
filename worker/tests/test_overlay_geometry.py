"""Overlay masks: ratio boxes, face guard, text strokes, repeated marks, residue, suspects."""

from __future__ import annotations

import cv2
import numpy as np
from numpy.typing import NDArray

from flowcortex_worker.overlay.geometry import (
    apply_face_guard,
    mask_from_ratio_boxes,
    repeated_watermark_mask,
    residual_mask,
    suspect_watermark,
    text_box_mask,
    tighten_text_mask,
)
from flowcortex_worker.overlay.params import RatioBox

Image = NDArray[np.uint8]


def grey_image(height: int, width: int, value: int = 100) -> Image:
    return np.full((height, width, 3), value, np.uint8)


def test_box_over_the_right_half_of_the_top_tenth() -> None:
    mask = mask_from_ratio_boxes((100, 200), [RatioBox(0.5, 0, 0.5, 0.1)], 0)
    expected = np.zeros((100, 200), np.uint8)
    expected[:10, 100:] = 1
    assert np.array_equal(mask, expected)


def test_same_ratios_mask_the_same_fraction_on_a_larger_image() -> None:
    box = [RatioBox(0.5, 0, 0.5, 0.1)]
    small = mask_from_ratio_boxes((100, 200), box, 0)
    large = mask_from_ratio_boxes((200, 400), box, 0)
    assert small.mean() == large.mean() == 0.05


def test_box_past_the_edge_is_clipped_and_masks_the_corner() -> None:
    mask = mask_from_ratio_boxes((100, 200), [RatioBox(0.9, 0.9, 0.5, 0.5)], 0)
    assert mask[99, 199] == 1
    assert mask.shape == (100, 200)
    assert mask[:90].sum() == 0


def test_dilation_grows_the_box() -> None:
    plain = mask_from_ratio_boxes((100, 200), [RatioBox(0.4, 0.4, 0.2, 0.2)], 0)
    grown = mask_from_ratio_boxes((100, 200), [RatioBox(0.4, 0.4, 0.2, 0.2)], 3)
    assert grown.sum() > plain.sum()
    assert (grown >= plain).all()


def test_face_guard_zeroes_the_face_box_and_leaves_the_rest() -> None:
    mask = np.ones((100, 100), np.uint8)
    guarded = apply_face_guard(mask, [(40, 40, 20, 20)], pad_ratio=0.0)
    assert guarded[40:60, 40:60].sum() == 0
    assert guarded.sum() == 100 * 100 - 20 * 20


def test_face_guard_without_faces_returns_the_mask_unchanged() -> None:
    mask = np.ones((50, 50), np.uint8)
    assert np.array_equal(apply_face_guard(mask, []), mask)


def test_face_guard_padding_removes_more() -> None:
    mask = np.ones((100, 100), np.uint8)
    plain = apply_face_guard(mask, [(40, 40, 20, 20)], pad_ratio=0.0)
    padded = apply_face_guard(mask, [(40, 40, 20, 20)])
    assert padded.sum() < plain.sum()
    assert padded[37:63, 37:63].sum() == 0


def test_tighten_keeps_stroke_pixels_inside_the_rough_box_only() -> None:
    bgr = grey_image(100, 100, 20)
    bgr[25:35, 30:34] = 255
    bgr[65:75, 30:34] = 255
    rough = text_box_mask(bgr.shape, [(20, 80, 20, 40)], pad=0)
    tight = tighten_text_mask(bgr, rough, dilate_px=1)
    assert tight[30, 31] == 1
    assert tight[70, 31] == 0
    assert tight[30, 70] == 0


def noise_background(seed: int, size: int = 800) -> Image:
    rng = np.random.default_rng(seed)
    noise = cv2.GaussianBlur(rng.standard_normal((size, size, 3)).astype(np.float32), (0, 0), 6)
    unit = noise / noise.std()
    background: Image = np.clip(110 + 30 * unit, 0, 255).astype(np.uint8)
    return background


LOGO_SIZE = (60, 140)
LOGO_PLACES = [(60, 80), (500, 120), (200, 450), (560, 600)]


def logo_ink() -> NDArray[np.bool_]:
    logo = np.zeros((*LOGO_SIZE, 3), np.uint8)
    cv2.circle(logo, (30, 30), 22, (255, 255, 255), -1)
    cv2.putText(logo, "LOGO", (60, 42), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (255, 255, 255), 3)
    ink: NDArray[np.bool_] = logo.any(axis=2)
    return ink


def test_a_logo_pasted_four_times_is_found_and_covered() -> None:
    bgr = noise_background(1)
    ink = logo_ink()
    for x, y in LOGO_PLACES:
        bgr[y : y + LOGO_SIZE[0], x : x + LOGO_SIZE[1]][ink] = 255
    mask, count = repeated_watermark_mask(bgr)
    assert count >= 4
    for x, y in LOGO_PLACES:
        assert mask[y : y + LOGO_SIZE[0], x : x + LOGO_SIZE[1]][ink].all()


def test_random_texture_is_not_a_repeated_watermark() -> None:
    mask, count = repeated_watermark_mask(noise_background(1))
    assert count == 0
    assert not mask.any()


def test_residual_keeps_a_large_component_inside_the_footprint_only() -> None:
    after = grey_image(120, 200)
    after[20:25, 20:26] = 220
    after[20:24, 60:65] = 220
    after[80:85, 150:156] = 220
    footprint = np.zeros((120, 200), np.uint8)
    footprint[10:60, 10:100] = 1
    residue = residual_mask(after, footprint, dilate_px=1)
    assert residue[22, 22] == 1
    assert residue[21, 62] == 0
    assert residue[82, 152] == 0
    assert (residue <= footprint).all()


def faint_stripes(width: int, height: int) -> Image:
    bgr = grey_image(400, 400)
    for column in range(5, 5 + width):
        if (column - 5) % 6 < 3:
            bgr[5 : 5 + height, column] = 120
    return bgr


def test_a_faint_corner_mark_over_half_a_percent_is_suspected() -> None:
    bgr = faint_stripes(40, 40)
    assert suspect_watermark(bgr, np.zeros((400, 400), np.uint8))


def test_a_faint_corner_mark_under_the_threshold_is_not_suspected() -> None:
    bgr = faint_stripes(30, 32)
    assert not suspect_watermark(bgr, np.zeros((400, 400), np.uint8))


def test_a_masked_corner_mark_is_not_suspected() -> None:
    bgr = faint_stripes(40, 40)
    mask = np.zeros((400, 400), np.uint8)
    mask[:50, :50] = 1
    assert not suspect_watermark(bgr, mask)
