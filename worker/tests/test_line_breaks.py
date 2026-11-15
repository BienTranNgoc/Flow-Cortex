"""Measured line breaking over a hand-built glyph table."""

from __future__ import annotations

import pytest

from flowcortex_worker.captions.line_breaks import (
    FontMetrics,
    GlyphTable,
    break_lines,
    font_metrics,
    resolve_font_key,
)

# ascent + descent = 1, so design units equal pixels at size 1.
ADVANCES = {str(ord("a")): 10, str(ord(" ")): 10, str(ord("W")): 50}


def table() -> GlyphTable:
    face: FontMetrics = {"winAscent": 1, "winDescent": 0, "advances": ADVANCES}
    return {"fonts": {"Test:400": face, "Test:700": face, "Single:400": face}}


def lines(text: str, size: float, max_width: float) -> list[tuple[int, int]]:
    return break_lines(text, font_metrics(table(), "Test:400"), size, max_width)


def test_greedy_split_at_a_space() -> None:
    # 40 + 10 + 10 fits 97; the third word would add 10 + 40 and does not.
    assert lines("aaaa a aaaa", 1, 100) == [(6, 7)]
    assert lines("aaaa a aaaa", 1, 1000) == []


def test_over_wide_word_stays_whole() -> None:
    assert lines("a" * 20, 1, 50) == []
    assert lines("a " + "a" * 20, 1, 50) == [(1, 2)]


def test_margin_is_97_percent() -> None:
    # "aaaa aaaa" is 90 px; 0.97 * 92.8 = 90.016 fits, 0.97 * 92 = 89.24 does not.
    assert lines("aaaa aaaa", 1, 92.8) == []
    assert lines("aaaa aaaa", 1, 92) == [(4, 5)]


def test_text_is_normalised_to_nfc_first() -> None:
    assert lines("á á", 1, 1000) == lines("á á", 1, 1000)
    assert lines("á á", 1, 25) == lines("á á", 1, 25)


def test_typed_newline_is_kept_and_paragraphs_measure_separately() -> None:
    assert lines("aaaa\naaaa", 1, 45) == []
    assert lines("aaaa\naaaa aaaa", 1, 45) == [(9, 10)]


def test_code_point_outside_the_table_uses_the_widest_advance() -> None:
    assert lines("# #", 1, 100) == [(1, 2)]
    assert lines("a a", 1, 100) == []


def test_size_scales_the_widths() -> None:
    assert lines("aaaa aaaa", 2, 100) == [(4, 5)]
    assert lines("aaaa aaaa", 1, 100) == []


def test_unknown_font_key_raises() -> None:
    with pytest.raises(ValueError, match=r"Nope:400"):
        font_metrics(table(), "Nope:400")


def test_resolve_font_key_prefers_the_bold_face() -> None:
    assert resolve_font_key(table(), "Test", True) == "Test:700"
    assert resolve_font_key(table(), "Test", False) == "Test:400"


def test_single_weight_family_falls_back_to_400() -> None:
    assert resolve_font_key(table(), "Single", True) == "Single:400"
