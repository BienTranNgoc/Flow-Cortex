"""Detect, guard faces, inpaint in up to two passes, and describe what happened."""

from __future__ import annotations

from collections.abc import Callable
from typing import Any, Protocol

import numpy as np
from numpy.typing import NDArray

from .geometry import (
    apply_face_guard,
    mask_from_ratio_boxes,
    repeated_watermark_mask,
    residual_mask,
    suspect_watermark,
    text_box_mask,
    tighten_text_mask,
)
from .params import OverlayParams
from .text_layers import text_layers_from_items

Image = NDArray[np.uint8]
Mask = NDArray[np.uint8]
RepeatedSearch = Callable[[Image, int], tuple[Mask, int]]

# Fewer matches than this and the repeated-mark branch contributes nothing.
MIN_REPEATED_MATCHES = 2


class Engines(Protocol):
    """The model-backed steps. Boxes are (x0, x1, y0, y1) for text and (x, y, w, h) for faces;
    masks are 0/1 uint8 and every image is three-channel BGR."""

    def text_boxes(self, bgr: Image) -> list[tuple[int, int, int, int]]: ...

    def text_items(self, bgr: Image) -> list[dict[str, Any]]: ...

    def faces(self, bgr: Image) -> list[tuple[int, int, int, int]]: ...

    def inpaint(self, bgr: Image, mask: Mask) -> Image: ...


def _detect_auto(
    bgr: Image, params: OverlayParams, engines: Engines, find_repeated: RepeatedSearch
) -> tuple[Mask, int, bool]:
    """Mask and region count of the automatic mode, and whether a faint mark may remain."""
    repeated, matches = find_repeated(bgr, params.dilate_px)
    boxes = engines.text_boxes(bgr)
    rough = text_box_mask(bgr.shape, boxes)
    mask: Mask = (
        tighten_text_mask(bgr, rough, params.dilate_px)
        if rough.any()
        else np.zeros(bgr.shape[:2], np.uint8)
    )
    regions = len(boxes) if rough.any() else 0
    if matches >= MIN_REPEATED_MATCHES:
        mask = ((mask > 0) | (repeated > 0)).astype(np.uint8)
        regions = max(regions, matches)
    return mask, regions, suspect_watermark(bgr, mask) and matches < MIN_REPEATED_MATCHES


def _split_alpha(image: Image) -> tuple[Image, Image | None]:
    if image.ndim != 3 or image.shape[2] not in (3, 4):
        raise ValueError("clean expects a 3- or 4-channel image")
    if image.shape[2] == 4:
        return np.ascontiguousarray(image[:, :, :3]), np.ascontiguousarray(image[:, :, 3])
    return image, None


def _with_alpha(bgr: Image, alpha: Image | None) -> Image:
    if alpha is None:
        return bgr
    stacked: Image = np.dstack([bgr, alpha])
    return stacked


def clean(
    image: Image,
    params: OverlayParams,
    engines: Engines,
    find_repeated: RepeatedSearch = repeated_watermark_mask,
) -> tuple[Image, dict[str, Any]]:
    """Return the cleaned image (alpha plane, when present, unchanged) and the result data."""
    bgr, alpha = _split_alpha(image)
    height, width = bgr.shape[:2]

    if params.region == "manual":
        mask = mask_from_ratio_boxes(bgr.shape, params.boxes, params.dilate_px)
        regions = len(params.boxes)
        watermark_remaining = False
    else:
        mask, regions, watermark_remaining = _detect_auto(bgr, params, engines, find_repeated)

    layers = text_layers_from_items(bgr, engines.text_items(bgr)) if params.extract_text else []

    faces = engines.faces(bgr)
    mask = apply_face_guard(mask, faces)

    data: dict[str, Any] = {
        "width": width,
        "height": height,
        "regionMode": params.region,
        "regionsRemoved": 0,
        "textRegionsFound": 0,
        "maskRatio": 0.0,
        "faceGuarded": len(faces) > 0,
        "watermarkRemaining": watermark_remaining,
        "inpaintPasses": 0,
        "textLayers": layers,
    }
    if not mask.any():
        return image, data

    # The model may repaint the whole frame; only the masked pixels are taken from it.
    first = engines.inpaint(bgr, mask)
    fixed = np.where(mask[:, :, None] > 0, first, bgr).astype(np.uint8)
    passes = 1
    residue = residual_mask(fixed, mask, params.dilate_px)
    if residue.any():
        second = engines.inpaint(fixed, residue)
        fixed = np.where(residue[:, :, None] > 0, second, fixed).astype(np.uint8)
        passes = 2

    data.update(
        regionsRemoved=int(regions),
        textRegionsFound=int(regions),
        maskRatio=round(float((mask > 0).mean()), 4),
        inpaintPasses=passes,
    )
    return _with_alpha(fixed, alpha), data
