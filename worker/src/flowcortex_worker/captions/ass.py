"""Builds the ASS karaoke script: word cleaning, validation, line grouping and timing."""

from __future__ import annotations

import math
import re
import unicodedata
from dataclasses import dataclass
from typing import Any

from .style import CaptionStyle

# Vietnamese compounds that a line must not be cut through.
VN_COMPOUND_PAIRS = {
    ("xỉn", "màu"),
    ("thâm", "mụn"),
    ("tự", "tin"),
    ("phép", "màu"),
    ("vitamin", "c"),
    ("làm", "sáng"),
    ("mờ", "thâm"),
    ("hiệu", "quả"),
    ("căng", "bóng"),
    ("mịn", "màng"),
    ("tì", "vết"),
    ("link", "ở"),
    ("ở", "bio"),
    ("rất", "tốt"),
    ("nhanh", "chóng"),
    ("dễ", "dàng"),
    ("đáng", "tin"),
    ("chất", "lượng"),
    ("công", "nghệ"),
    ("sản", "phẩm"),
}
# Everything except word characters and the Latin / Latin Extended Additional letters.
_PUNCT_RE = re.compile(f"[^\\w{chr(0xC0)}-{chr(0x24F)}{chr(0x1E00)}-{chr(0x1EFF)}]+", re.UNICODE)


@dataclass(frozen=True)
class CaptionWord:
    word: str
    start: float
    end: float
    line_break: bool = False


def clean_word(text: str) -> str:
    """Make a word safe inside a Dialogue line.

    NFC first, so a decomposed "xỉn" still matches its compound pair (the punctuation strip would
    otherwise remove the loose tone mark). Whitespace runs become one space because a newline
    breaks the Dialogue event while ffmpeg still exits 0. Braces open ASS override blocks and a
    backslash starts \\N / \\h, so both are removed.
    """
    text = unicodedata.normalize("NFC", str(text or ""))
    text = re.sub(r"\s+", " ", text).strip()
    return text.replace("{", "").replace("}", "").replace("\\", "")


def _norm(text: str) -> str:
    return _PUNCT_RE.sub("", unicodedata.normalize("NFC", str(text or "")).strip()).lower()


def _is_number(value: object) -> bool:
    return isinstance(value, int | float) and not isinstance(value, bool) and math.isfinite(value)


def validate_words(words: Any, offset: Any) -> tuple[list[CaptionWord], float]:
    """Return the timed words and the float start offset. Raises `ValueError` before any ffmpeg
    run when `words` is not a non-empty list of objects, none carries numeric `start` and `end`,
    or the offset is not a finite number."""
    if offset is None:
        offset = 0.0
    if not _is_number(offset):
        raise ValueError(f"startOffset must be a finite number, got: {offset!r}")
    if not isinstance(words, list) or not words:
        raise ValueError("words must be a non-empty list of objects")
    if not all(isinstance(entry, dict) for entry in words):
        raise ValueError("words must be a non-empty list of objects")
    timed = [
        CaptionWord(
            word=str(entry.get("word") or ""),
            start=float(entry["start"]),
            end=float(entry["end"]),
            line_break=bool(entry.get("lineBreak")),
        )
        for entry in words
        if _is_number(entry.get("start")) and _is_number(entry.get("end"))
    ]
    if not timed:
        raise ValueError("words carry no timestamps: no entry has a numeric start and end")
    return timed, float(offset)


def group_words(words: list[CaptionWord], max_syllables: int) -> list[list[CaptionWord]]:
    """Split into lines: a `line_break` word ends its line; with none marked, lines hold at most
    `max_syllables` words and are not cut inside a compound pair."""
    lines: list[list[CaptionWord]] = []
    line: list[CaptionWord] = []
    if any(w.line_break for w in words):
        for w in words:
            line.append(w)
            if w.line_break:
                lines.append(line)
                line = []
    else:
        for w in words:
            if line and len(line) >= max_syllables:
                if (_norm(line[-1].word), _norm(w.word)) in VN_COMPOUND_PAIRS:
                    line.append(w)
                    continue
                lines.append(line)
                line = []
            line.append(w)
    if line:
        lines.append(line)
    return lines


def _timecode(seconds: float) -> str:
    centis = round(max(0.0, seconds) * 100)
    hours, rest = divmod(centis, 360000)
    minutes, rest = divmod(rest, 6000)
    return f"{hours}:{minutes:02d}:{rest // 100:02d}.{rest % 100:02d}"


def build_ass(
    words: list[CaptionWord],
    offset: float,
    style: CaptionStyle,
    width: int,
    height: int,
) -> tuple[str, int]:
    """Return the ASS script for `width` x `height` and its number of Dialogue lines."""
    box = f"BorderStyle: {style.border_style}\n" if style.border_style != 1 else ""
    header = (
        f"[Script Info]\nScriptType: v4.00+\nPlayResX: {width}\nPlayResY: {height}\n{box}\n"
        "[V4+ Styles]\nFormat: Name,Fontname,Fontsize,PrimaryColour,SecondaryColour,"
        "OutlineColour,BackColour,Bold,Outline,Shadow,Alignment,MarginV\n"
        f"Style: K,{style.font},{style.size},{style.primary},{style.secondary},"
        f"{style.outline_colour},{style.back_colour},{style.bold},{style.outline},"
        f"{style.shadow},{style.align},{style.margin_v}\n\n"
        "[Events]\nFormat: Layer,Start,End,Style,Text\n"
    )
    tag = "kf" if style.mode == "sweep" else "k"
    events = []
    for line in group_words(words, style.max_syllables):
        first = line[0].start
        text = ""
        # \k and \kf accumulate from the start of the line, so a silence between two words is
        # inserted as a pause tag; absolute centisecond marks avoid compounding rounding, and a
        # word overlapping the previous one starts where the cursor stands.
        cursor = 0
        for i, w in enumerate(line):
            start_cs = round((w.start - first) * 100)
            end_cs = round((w.end - first) * 100)
            duration = max(1, end_cs - max(cursor, start_cs))
            cursor = max(cursor, start_cs) + duration
            gap = round((line[i + 1].start - first) * 100) - cursor if i + 1 < len(line) else 0
            word = clean_word(w.word)
            if style.mode == "off":
                text += f"{word} "
            else:
                pause = f"{{\\k{gap}}}" if gap > 0 else ""
                text += f"{{\\{tag}{duration}}}{word}{pause} "
        start = _timecode(first - offset)
        end = _timecode(line[-1].end - offset)
        events.append(f"Dialogue: 0,{start},{end},K,{text.strip()}")
    return header + "\n".join(events), len(events)
