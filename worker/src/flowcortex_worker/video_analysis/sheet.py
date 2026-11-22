"""Contact sheet layout and composition."""

from __future__ import annotations

import math
from collections.abc import Sequence

from PIL import Image, ImageDraw, ImageFont

TILE_LONG_SIDE = 360
LABEL_HEIGHT = 28
LABEL_FONT_PX = 16
JPEG_QUALITY = 85
_LABEL_PAD = 6


def clock(ms: int) -> str:
    """`m:ss.t`, rounded to 100 ms."""
    tenths = round(ms / 100)
    minutes, rest = divmod(tenths, 600)
    return f"{minutes}:{rest // 10:02d}.{rest % 10}"


def tile_size(width: int, height: int) -> tuple[int, int]:
    """Long side `TILE_LONG_SIDE`, short side scaled and rounded down (at least 1)."""
    if width >= height:
        return TILE_LONG_SIDE, max(1, height * TILE_LONG_SIDE // width)
    return max(1, width * TILE_LONG_SIDE // height), TILE_LONG_SIDE


def grid(n: int, tile_w: int, tile_h: int) -> tuple[int, int]:
    """Columns and rows that keep the sheet near square for `n` tiles."""
    cols = math.ceil(math.sqrt(n * tile_h / tile_w))
    return cols, math.ceil(n / cols)


def compose_sheet(
    tiles: Sequence[Image.Image], labels: Sequence[str], tile_w: int, tile_h: int
) -> Image.Image:
    """Row-major sheet of same-sized tiles, each labelled on a black box at its top left."""
    cols, rows = grid(len(tiles), tile_w, tile_h)
    canvas = Image.new("RGB", (cols * tile_w, rows * tile_h), (0, 0, 0))
    draw = ImageDraw.Draw(canvas)
    font = ImageFont.load_default(size=LABEL_FONT_PX)
    for k, (tile, label) in enumerate(zip(tiles, labels, strict=True)):
        row, col = divmod(k, cols)
        x, y = col * tile_w, row * tile_h
        canvas.paste(tile, (x, y))
        left, top, right, bottom = draw.textbbox((0, 0), label, font=font)
        box_w = min(right - left + 2 * _LABEL_PAD, tile_w)
        box_h = min(LABEL_HEIGHT, tile_h)
        draw.rectangle((x, y, x + box_w - 1, y + box_h - 1), fill=(0, 0, 0))
        draw.text(
            (x + _LABEL_PAD - left, y + (box_h - (bottom - top)) // 2 - top),
            label,
            font=font,
            fill=(255, 255, 255),
        )
    return canvas
