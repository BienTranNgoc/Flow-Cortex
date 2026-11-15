"""libass stderr audit: substituted families and missing glyphs."""

from __future__ import annotations

import pytest

from flowcortex_worker.captions.fonts import audit_libass

PATH = "/usr/share/fonts/truetype/flowcortex/anton/Anton-Regular.ttf"
SELECT = f"fontselect: (Anton, 400, 0) -> {PATH}, 0, Anton-Regular"


def anton(path: str) -> str:
    return "Anton"


def test_clean_log_returns_no_warnings() -> None:
    assert audit_libass(SELECT, "Anton", anton) == []


def test_substituted_family_is_refused() -> None:
    with pytest.raises(ValueError, match="substituted") as excinfo:
        audit_libass(SELECT, "Anton", lambda path: "DejaVu Sans")
    assert "DejaVu Sans" in str(excinfo.value)


def test_family_comparison_ignores_case_and_an_empty_lookup() -> None:
    assert audit_libass(SELECT, "Anton", lambda path: "ANTON") == []
    assert audit_libass(SELECT, "Anton", lambda path: "") == []


def test_vietnamese_glyph_is_refused() -> None:
    log = f"{SELECT}\nGlyph 0x1EAB not found, selecting one more font for (Anton, 400, 0)"
    with pytest.raises(ValueError, match=r"U\+1EAB"):
        audit_libass(log, "Anton", anton)


def test_emoji_is_a_warning() -> None:
    log = f"{SELECT}\nGlyph 0x1F525 not found, selecting one more font for (Anton, 400, 0)"
    warnings = audit_libass(log, "Anton", anton)
    assert len(warnings) == 1
    assert "U+1F525" in warnings[0]


def test_glyph_fallback_font_is_not_a_substitution() -> None:
    fallback = "/usr/share/fonts/truetype/flowcortex/kablammo/kablammo-400.ttf"
    log = (
        f"{SELECT}\n"
        "Glyph 0x1F525 not found, selecting one more font for (Anton, 400, 0)\n"
        f"fontselect: (Anton, 400, 0) -> {fallback}, 0, KablammoRegular-Zoink"
    )

    def family_of(path: str) -> str:
        return "Kablammo" if path == fallback else "Anton"

    warnings = audit_libass(log, "Anton", family_of)
    assert len(warnings) == 1
    assert "U+1F525" in warnings[0]


def test_failed_fallback_line_is_ignored() -> None:
    def never(path: str) -> str:
        raise AssertionError("lookup must not run")

    log = "fontselect: failed to find any fallback with glyph 0x1F525 for font Anton"
    assert audit_libass(log, "Anton", never) == []
