"""Overlay job parameters, checked before the image is read. Standard library only."""

from __future__ import annotations

import math
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

from ..errors import PermanentError

DILATE_BY_STRENGTH = (1, 2, 3, 5, 7)
REGIONS = ("auto", "manual")
ACCEPTED_KEYS = ("region", "customRegions", "strength", "extractText")
BOX_KEYS = ("x", "y", "w", "h")


@dataclass(frozen=True)
class RatioBox:
    x: float
    y: float
    w: float
    h: float


@dataclass(frozen=True)
class OverlayParams:
    region: str
    boxes: tuple[RatioBox, ...]
    strength: int
    dilate_px: int
    extract_text: bool


def _refuse(message: str) -> PermanentError:
    return PermanentError("contract_violation", message)


def _ratio(value: Any) -> float:
    if isinstance(value, bool) or not isinstance(value, int | float):
        raise _refuse("customRegions boxes need numeric x, y, w, h between 0-1")
    number = float(value)
    if not math.isfinite(number) or not 0.0 <= number <= 1.0:
        raise _refuse("customRegions boxes need numeric x, y, w, h between 0-1")
    return number


def _box(raw: Any) -> RatioBox:
    if not isinstance(raw, Mapping) or set(raw) != set(BOX_KEYS):
        raise _refuse("customRegions boxes need exactly x, y, w, h as numbers between 0-1")
    return RatioBox(*(_ratio(raw[key]) for key in BOX_KEYS))


def parse_params(raw: Mapping[str, Any]) -> OverlayParams:
    if not isinstance(raw, Mapping):
        raise _refuse("params must be an object")
    unknown = sorted(str(key) for key in raw if key not in ACCEPTED_KEYS)
    if unknown:
        raise _refuse(
            f"unknown params {', '.join(unknown)}; accepted keys: {', '.join(ACCEPTED_KEYS)}"
        )

    region = raw.get("region", "auto")
    if region not in REGIONS:
        raise _refuse(f"region must be one of {', '.join(REGIONS)}")

    strength = raw.get("strength", 3)
    if isinstance(strength, bool) or not isinstance(strength, int) or not 1 <= strength <= 5:
        raise _refuse("strength must be an integer 1-5")

    extract_text = raw.get("extractText", False)
    if not isinstance(extract_text, bool):
        raise _refuse("extractText must be true or false")

    regions = raw.get("customRegions")
    if regions is None:
        regions = []
    if not isinstance(regions, list | tuple):
        raise _refuse("customRegions must be a list of boxes")
    boxes = tuple(_box(item) for item in regions)
    if region == "manual" and not boxes:
        raise _refuse("manual region needs at least one box in customRegions")

    return OverlayParams(
        region=region,
        boxes=boxes,
        strength=strength,
        dilate_px=DILATE_BY_STRENGTH[strength - 1],
        extract_text=extract_text,
    )
