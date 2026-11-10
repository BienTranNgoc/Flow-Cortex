"""Masks for the clean: ratio boxes, text strokes, repeated watermarks, residue, suspects.

Every mask is a uint8 array of 0 and 1 the size of the image. Constants are the set measured on
Xood's stock-photo and caption samples; none is exposed as a parameter.
"""

from __future__ import annotations

from collections.abc import Sequence
from typing import Any

import cv2
import numpy as np
from numpy.typing import NDArray

from .params import RatioBox

Mask = NDArray[np.uint8]
Image = NDArray[np.uint8]
Faces = Sequence[tuple[int, int, int, int]]
TextBoxes = Sequence[tuple[int, int, int, int]]

FACE_PAD = 0.18
TEXT_BOX_PAD = 4

_K3 = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
_RECT13 = cv2.getStructuringElement(cv2.MORPH_RECT, (13, 13))

# Matching on raw grey finds the background, not a faint mark, so templates run on a high-pass.
_STRONG_MATCH = 0.55
_MIN_STRONG_COPIES = 3
# A weak copy still gets masked once the pattern is confirmed by strong ones.
_WEAK_MATCH = 0.22
_ALPHA_MIN = 0.04
# A real overlay covers a modest part of its template: less is noise, more breaks the variance.
_COVERAGE_LOW, _COVERAGE_HIGH = 0.03, 0.45
_MAX_TEMPLATE_FILL = 0.92
_MAX_COPIES = 24


def _u8(array: NDArray[Any]) -> Mask:
    return np.asarray(array, dtype=np.uint8)


def mask_from_ratio_boxes(
    shape: tuple[int, ...], boxes: Sequence[RatioBox], dilate_px: int = 0
) -> Mask:
    height, width = int(shape[0]), int(shape[1])
    mask: Mask = np.zeros((height, width), np.uint8)
    for box in boxes:
        x0 = max(0, round(box.x * width))
        y0 = max(0, round(box.y * height))
        x1 = min(width, round((box.x + box.w) * width))
        y1 = min(height, round((box.y + box.h) * height))
        if x1 > x0 and y1 > y0:
            mask[y0:y1, x0:x1] = 1
    if dilate_px > 0 and mask.any():
        mask = _u8(cv2.dilate(mask, _K3, iterations=dilate_px))
    return mask


def apply_face_guard(mask: Mask, faces: Faces, pad_ratio: float = FACE_PAD) -> Mask:
    """Zero every face box, widened by `pad_ratio` of its size on each side."""
    if len(faces) == 0:
        return mask
    out = mask.copy()
    height, width = mask.shape[:2]
    for fx, fy, fw, fh in faces:
        pad_x, pad_y = int(fw * pad_ratio), int(fh * pad_ratio)
        x0, y0 = max(0, int(fx) - pad_x), max(0, int(fy) - pad_y)
        x1, y1 = min(width, int(fx + fw) + pad_x), min(height, int(fy + fh) + pad_y)
        out[y0:y1, x0:x1] = 0
    return out


def text_box_mask(shape: tuple[int, ...], boxes: TextBoxes, pad: int = TEXT_BOX_PAD) -> Mask:
    """Rough mask of detector boxes given as (x0, x1, y0, y1) pixels."""
    height, width = int(shape[0]), int(shape[1])
    rough: Mask = np.zeros((height, width), np.uint8)
    for x0, x1, y0, y1 in boxes:
        rough[
            max(0, int(y0) - pad) : min(height, int(y1) + pad),
            max(0, int(x0) - pad) : min(width, int(x1) + pad),
        ] = 1
    return rough


def tighten_text_mask(bgr: Image, rough: Mask, dilate_px: int) -> Mask:
    """Keep only stroke pixels (high local contrast or bright) inside the rough boxes."""
    grey = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    contrast = np.maximum(
        cv2.morphologyEx(grey, cv2.MORPH_BLACKHAT, _RECT13),
        cv2.morphologyEx(grey, cv2.MORPH_TOPHAT, _RECT13),
    )
    tight = (((contrast > 35) | (grey > 150)) & (rough > 0)).astype(np.uint8)
    tight = _u8(cv2.morphologyEx(tight, cv2.MORPH_CLOSE, _K3, iterations=2))
    return _u8(cv2.dilate(tight, _K3, iterations=max(1, dilate_px)))


def _highpass(grey: NDArray[Any], sigma: float = 9.0) -> Image:
    """Positive detail above the local blur, scaled to 0..255."""
    floats = grey.astype(np.float32)
    detail = np.clip(floats - cv2.GaussianBlur(floats, (0, 0), sigma), 0, None)
    scaled: Image = (detail / max(float(detail.max()), 1e-6) * 255).astype(np.uint8)
    return scaled


def _seed_window(detail: Image) -> tuple[int, int, int, int] | None:
    """One template window around the strongest stroke cluster, as (x0, y0, x1, y1)."""
    height, width = detail.shape
    closed = cv2.morphologyEx(
        (detail > 40).astype(np.uint8),
        cv2.MORPH_CLOSE,
        cv2.getStructuringElement(cv2.MORPH_RECT, (25, 25)),
    )
    count, _labels, stats, _ = cv2.connectedComponentsWithStats(closed, connectivity=8)
    best: tuple[float, int, int, int, int] | None = None
    for i in range(1, count):
        x, y, box_w, box_h, area = (int(v) for v in stats[i])
        if area < 200 or box_w > width * 0.6 or box_h > height * 0.6:
            continue
        energy = float(detail[y : y + box_h, x : x + box_w].sum())
        if best is None or energy > best[0]:
            best = (energy, x, y, box_w, box_h)
    if best is None:
        return None
    _, x, y, box_w, box_h = best
    # Widening past ~8 % dropped the match score and found 4 of 7 copies on the stock sample.
    pad_x, pad_y = int(box_w * 0.08) + 4, int(box_h * 0.08) + 4
    x0, y0 = max(0, x - pad_x), max(0, y - pad_y)
    x1, y1 = min(width, x + box_w + pad_x), min(height, y + box_h + pad_y)
    if (x1 - x0) < 24 or (y1 - y0) < 24:
        return None
    return x0, y0, x1, y1


def repeated_watermark_mask(bgr: Image, dilate_px: int = 3) -> tuple[Mask, int]:
    """Mask every copy of one mark pasted several times, and the number of copies found.

    Returns an empty mask and 0 when fewer than three strong copies confirm a repeat.
    """
    height, width = bgr.shape[:2]
    empty: Mask = np.zeros((height, width), np.uint8)
    detail = _highpass(cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY))
    seed = _seed_window(detail)
    if seed is None:
        return empty, 0
    sx0, sy0, sx1, sy1 = seed
    template = detail[sy0:sy1, sx0:sx1]
    t_h, t_w = template.shape
    if t_h >= height or t_w >= width:
        return empty, 0

    scores = cv2.matchTemplate(detail, template, cv2.TM_CCOEFF_NORMED)
    work = scores.copy()
    found: list[tuple[int, int, float]] = []
    while len(found) < _MAX_COPIES:
        _, best, _, loc = cv2.minMaxLoc(work)
        if best < _WEAK_MATCH:
            break
        found.append((int(loc[0]), int(loc[1]), float(best)))
        # Clearing a whole template around the peak removes its side lobes.
        work[max(0, loc[1] - t_h) : loc[1] + t_h, max(0, loc[0] - t_w) : loc[0] + t_w] = 0
    found = [(x, y, s) for x, y, s in found if x + t_w <= width and y + t_h <= height]
    strong = [(x, y) for x, y, s in found if s >= _STRONG_MATCH]
    if len(strong) < _MIN_STRONG_COPIES:
        return empty, 0

    # The overlay is where strong copies agree with each other while the background varies.
    crops = np.stack([bgr[y : y + t_h, x : x + t_w].astype(np.float32) for x, y in strong])
    spread = crops.std(axis=0).mean(axis=2)
    ring = np.zeros((t_h, t_w), bool)
    ring_w = max(4, min(t_h, t_w) // 12)
    ring[:ring_w, :] = ring[-ring_w:, :] = True
    ring[:, :ring_w] = ring[:, -ring_w:] = True
    spread_ref = float(np.median(spread[ring]))
    if spread_ref < 1.0:
        return empty, 0
    alpha = cv2.GaussianBlur(np.clip(1.0 - spread / spread_ref, 0, 0.95), (0, 0), 1.2)

    coverage = float((alpha > 0.15).mean())
    if not _COVERAGE_LOW <= coverage <= _COVERAGE_HIGH:
        return empty, 0

    footprint = (alpha > _ALPHA_MIN).astype(np.uint8)
    footprint = cv2.morphologyEx(
        footprint, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (17, 17))
    )
    footprint = cv2.dilate(footprint, _K3, iterations=max(1, dilate_px + 1))
    if footprint.mean() > _MAX_TEMPLATE_FILL:
        return empty, 0

    mask = empty.copy()
    for x, y, _score in found:
        mask[y : y + t_h, x : x + t_w] |= _u8(footprint)
    return mask, len(found)


def residual_mask(after: Image, footprint: Mask, dilate_px: int = 3) -> Mask:
    """Structure left inside the first mask after inpainting; always a subset of `footprint`."""
    detail = _highpass(cv2.cvtColor(after, cv2.COLOR_BGR2GRAY), sigma=7.0)
    residue = ((detail > 20) & (footprint > 0)).astype(np.uint8)
    residue = _u8(
        cv2.morphologyEx(
            residue, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (9, 9))
        )
    )
    count, labels, stats, _ = cv2.connectedComponentsWithStats(residue, connectivity=8)
    keep: Mask = np.zeros_like(residue)
    for i in range(1, count):
        if stats[i, cv2.CC_STAT_AREA] >= 25:
            keep[labels == i] = 1
    grown = _u8(cv2.dilate(keep, _K3, iterations=max(1, dilate_px)))
    return (grown * (footprint > 0)).astype(np.uint8)


def suspect_watermark(bgr: Image, mask: Mask) -> bool:
    """A faint, unmasked mark in a corner band: reported, never masked (inpainting it hurt)."""
    height, width = bgr.shape[:2]
    grey = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    contrast = cv2.morphologyEx(
        grey, cv2.MORPH_TOPHAT, cv2.getStructuringElement(cv2.MORPH_RECT, (9, 9))
    )
    band_h, band_w = max(1, int(height * 0.16)), max(1, int(width * 0.30))
    corners = np.zeros((height, width), bool)
    corners[:band_h, :band_w] = corners[:band_h, -band_w:] = True
    corners[-band_h:, :band_w] = corners[-band_h:, -band_w:] = True
    suspect = corners & (contrast > 12) & (contrast < 45) & (mask == 0)
    return bool(suspect.sum() > 0.004 * height * width)
