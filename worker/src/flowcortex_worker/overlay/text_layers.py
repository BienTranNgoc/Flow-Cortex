"""Recognised text items to editable layers, all coordinates as ratios of the image."""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from typing import Any

import numpy as np
from numpy.typing import NDArray

Image = NDArray[np.uint8]
Box = tuple[int, int, int, int]

# Measured: a 62 px caption came back from the detector as a 91 px box (62 / 91).
FONT_SIZE_OF_BOX = 0.68
# Below this the recogniser's text is usually junk, and a wrong layer costs more than none.
MIN_TEXT_CONFIDENCE = 0.4
MIN_BOX_RATIO = 0.01
SAME_LINE_OVERLAP = 0.6
# A word space is about 0.3 of the text height; 1.0 merges sparse spacing, not distant labels.
SAME_LINE_GAP_OF_HEIGHT = 1.0
_DEFAULT_COLOR = "#111111"


def _hex(blue: float, green: float, red: float) -> str:
    return f"#{int(red):02x}{int(green):02x}{int(blue):02x}"


def _ink_color(bgr: Image, box: Box) -> str:
    """Colour of the pixels farthest from the box's median (background) colour."""
    x0, x1, y0, y1 = box
    patch = bgr[y0:y1, x0:x1]
    if patch.size == 0:
        return _DEFAULT_COLOR
    flat = patch.reshape(-1, 3).astype(np.float32)
    background = np.median(flat, axis=0)
    distance = np.linalg.norm(flat - background, axis=1)
    if not distance.any():
        return _hex(*background)
    ink = flat[distance >= max(float(distance.max()) * 0.6, 1.0)]
    blue, green, red = np.median(ink, axis=0) if len(ink) else background
    return _hex(blue, green, red)


def _align_of(x0: int, x1: int, width: int) -> str:
    centre = (x0 + x1) / 2 / max(width, 1)
    if centre < 0.38:
        return "left"
    if centre > 0.62:
        return "right"
    return "center"


def _merge_same_line(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for entry in sorted(items, key=lambda it: (it["box"][2], it["box"][0])):
        x0, x1, y0, y1 = entry["box"]
        height = max(y1 - y0, 1)
        for row in rows:
            row_x0, row_x1, row_y0, row_y1 = row["box"]
            overlap = min(y1, row_y1) - max(y0, row_y0)
            # Distance between the two spans whichever comes first: a one-sided subtraction
            # goes very negative when a taller fragment sorts earlier on the vertical axis.
            gap = max(x0 - row_x1, row_x0 - x1)
            if (
                overlap > SAME_LINE_OVERLAP * min(height, max(row_y1 - row_y0, 1))
                and gap <= SAME_LINE_GAP_OF_HEIGHT * height
            ):
                row["box"] = (
                    min(row_x0, x0),
                    max(row_x1, x1),
                    min(row_y0, y0),
                    max(row_y1, y1),
                )
                left, right = (row, entry) if row_x0 <= x0 else (entry, row)
                row["text"] = f"{left['text']} {right['text']}".strip()
                row["confidence"] = min(row["confidence"], entry["confidence"])
                break
        else:
            rows.append(dict(entry))
    return rows


def text_layers_from_items(bgr: Image, items: Iterable[Mapping[str, Any]]) -> list[dict[str, Any]]:
    """Filter junk, merge fragments of one line, return layers in reading order.

    Filtering comes first: a large junk box would otherwise swallow the real lines inside it
    and drag the merged confidence under the threshold.
    """
    height, width = bgr.shape[:2]
    kept: list[dict[str, Any]] = []
    for entry in items:
        text = str(entry.get("text") or "").strip()
        confidence = float(entry.get("confidence") or 0.0)
        raw_x0, raw_x1, raw_y0, raw_y1 = (int(v) for v in entry["box"])
        x0, x1 = max(0, min(raw_x0, raw_x1)), min(width, max(raw_x0, raw_x1))
        y0, y1 = max(0, min(raw_y0, raw_y1)), min(height, max(raw_y0, raw_y1))
        if not text or confidence < MIN_TEXT_CONFIDENCE:
            continue
        if (x1 - x0) / max(width, 1) < MIN_BOX_RATIO or (y1 - y0) / max(height, 1) < MIN_BOX_RATIO:
            continue
        kept.append({"box": (x0, x1, y0, y1), "text": text, "confidence": confidence})

    layers: list[dict[str, Any]] = []
    for row in _merge_same_line(kept):
        x0, x1, y0, y1 = row["box"]
        box_w, box_h = x1 - x0, y1 - y0
        layers.append(
            {
                "text": row["text"],
                "x": x0 / width,
                "y": y0 / height,
                "w": box_w / width,
                "h": box_h / height,
                "fontSizeRatio": box_h * FONT_SIZE_OF_BOX / height,
                "color": _ink_color(bgr, (x0, x1, y0, y1)),
                "align": _align_of(x0, x1, width),
                "confidence": row["confidence"],
            }
        )
    # Boxes less than half a height apart vertically count as one row.
    layers.sort(key=lambda layer: (round(layer["y"] / max(layer["h"], 1e-6)), layer["x"]))
    return layers
