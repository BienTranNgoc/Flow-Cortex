"""Line breaking by measured width, over the glyph-advance table the preview uses too.

libass scales a face so that `winAscent + winDescent` equals the ASS font size, so a code point
with design-unit advance `a` is drawn `a * size_px / (winAscent + winDescent)` pixels wide. Rules
(the front end must apply them identically): NFC first; typed newlines are kept and each paragraph
is measured alone; greedy by word, never inside a word, so a word wider than the box stays whole;
no kerning; usable width is 97 % of the box.
"""

from __future__ import annotations

import json
import re
import unicodedata
from pathlib import Path
from typing import TypedDict

from .fonts import FONT_LIBRARY_DIR

WIDTH_SAFETY_MARGIN = 0.97


class FontMetrics(TypedDict):
    winAscent: int
    winDescent: int
    advances: dict[str, int]


class GlyphTable(TypedDict):
    fonts: dict[str, FontMetrics]


def load_glyph_advances(path: Path = FONT_LIBRARY_DIR / "glyph-advances.json") -> GlyphTable:
    with path.open(encoding="utf-8") as handle:
        table: GlyphTable = json.load(handle)
    return table


def font_metrics(table: GlyphTable, key: str) -> FontMetrics:
    """Metrics of a `"Family:weight"` key; raises `ValueError` rather than guess a zero width."""
    metrics = table["fonts"].get(key)
    if metrics is None:
        raise ValueError(f"break_lines: no glyph advances for font {key!r}")
    return metrics


def resolve_font_key(table: GlyphTable, family: str, bold: bool) -> str:
    """`"Family:700"` when the table has a real bold face, else `"Family:400"`: libass fakes bold
    for single-weight families without changing glyph widths."""
    key = f"{family}:{700 if bold else 400}"
    if bold and key not in table["fonts"]:
        return f"{family}:400"
    return key


def break_lines(
    text: str, metrics: FontMetrics, size_px: float, max_width_px: float
) -> list[tuple[int, int]]:
    """Spans `(start, end)` of the whitespace of `NFC(text)` to replace with a line break; empty
    when the text fits."""
    text = unicodedata.normalize("NFC", text)
    px_per_unit = size_px / (metrics["winAscent"] + metrics["winDescent"])
    advances = metrics["advances"]
    # An unknown code point is assumed as wide as the font's widest glyph: a narrow guess breaks
    # late and overflows the box, a wide guess only breaks early.
    fallback = max(advances.values(), default=0)
    usable = max_width_px * WIDTH_SAFETY_MARGIN

    def width(span: str) -> float:
        return sum(advances.get(str(ord(ch)), fallback) for ch in span) * px_per_unit

    breaks: list[tuple[int, int]] = []
    for paragraph in re.finditer(r"[^\n]+", text):
        body, base = paragraph.group(), paragraph.start()
        words = [(m.start(), m.end()) for m in re.finditer(r"\S+", body)]
        if not words:
            continue
        current = width(body[words[0][0] : words[0][1]])
        for i in range(1, len(words)):
            start, end = words[i]
            previous_end = words[i - 1][1]
            added = width(body[previous_end:start]) + width(body[start:end])
            if current + added > usable:
                breaks.append((base + previous_end, base + start))
                current = width(body[start:end])
            else:
                current += added
    return breaks
