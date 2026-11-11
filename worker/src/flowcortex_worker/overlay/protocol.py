"""What the handler and the overlay child agree on. Standard library only."""

from __future__ import annotations

from typing import Any

from .params import OverlayParams, RatioBox

# Largest benchmarked size (2048x2048) that stays within 6 GiB RSS and 900 s on the 4-CPU CI
# runner; 3840x2160 peaked at 6.11 GiB and 5000x5000 was OOM-killed. The child hands it to
# OpenCV's decoder limit.
MAX_PIXELS = 2048 * 2048

OK = 0
INPUT_REJECTED = 3
FACE_MODEL_MISSING = 4
RUNTIME_MISSING = 5

# File names inside the child's working directory; the child takes no path arguments.
INPUT_FILE = "input"
PARAMS_FILE = "params.json"
CLEAN_FILE = "clean.png"
RESULT_FILE = "result.json"


def params_to_json(params: OverlayParams) -> dict[str, Any]:
    return {
        "region": params.region,
        "boxes": [[box.x, box.y, box.w, box.h] for box in params.boxes],
        "strength": params.strength,
        "dilatePx": params.dilate_px,
        "extractText": params.extract_text,
    }


def params_from_json(raw: dict[str, Any]) -> OverlayParams:
    return OverlayParams(
        region=str(raw["region"]),
        boxes=tuple(RatioBox(*(float(v) for v in box)) for box in raw["boxes"]),
        strength=int(raw["strength"]),
        dilate_px=int(raw["dilatePx"]),
        extract_text=bool(raw["extractText"]),
    )
