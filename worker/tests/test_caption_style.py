"""Caption style resolution: colours, defaults, bounds, closed lists, backdrop and shadow."""

from __future__ import annotations

from typing import Any

import pytest

from flowcortex_worker.captions.style import ass_color, resolve_style


def test_ass_color_swaps_to_bgr_and_maps_transparency_to_alpha() -> None:
    assert ass_color("#FFE000") == "&H0000E0FF"
    assert ass_color("#000000", 60) == "&H99000000"


@pytest.mark.parametrize("value", ["FFE000", "#FFE00", "#GGGGGG", "#FFE0000", "", None])
def test_ass_color_refuses_invalid_hex(value: Any) -> None:
    with pytest.raises(ValueError, match="#RRGGBB"):
        ass_color(value)


@pytest.mark.parametrize("pct", [-1, 101, "50", None])
def test_ass_color_refuses_out_of_range_transparency(pct: Any) -> None:
    with pytest.raises(ValueError, match="0-100"):
        ass_color("#000000", pct)


def test_empty_style_at_1920_matches_the_default_style_line() -> None:
    style = resolve_style({}, 1920)
    assert (style.font, style.size, style.primary, style.secondary) == (
        "DejaVu Sans",
        84,
        "&H0000E0FF",
        "&H00FFFFFF",
    )
    assert (style.outline_colour, style.back_colour, style.bold) == (
        "&H00000000",
        "&H00000000",
        -1,
    )
    assert (style.outline, style.shadow, style.align, style.margin_v) == (5, 1, 2, 300)
    assert (style.mode, style.max_syllables, style.border_style) == ("sweep", 4, 1)


def test_none_style_equals_empty_style() -> None:
    assert resolve_style(None, 1080) == resolve_style({}, 1080)


def test_size_and_margin_scale_with_height() -> None:
    style = resolve_style({}, 1080)
    assert (style.size, style.margin_v) == (round(4.375 * 1080 / 100), round(15.625 * 1080 / 100))


def test_font_size_pct_bounds_are_inclusive() -> None:
    assert resolve_style({"fontSizePct": 20}, 1000).size == 200
    assert resolve_style({"fontSizePct": 1}, 1000).size == 10
    for bad in (20.01, 0.99, "20", True):
        with pytest.raises(ValueError, match="1-20"):
            resolve_style({"fontSizePct": bad}, 1000)


def test_margin_pct_bounds_are_inclusive() -> None:
    assert resolve_style({"marginPct": 0}, 1000).margin_v == 0
    assert resolve_style({"marginPct": 45}, 1000).margin_v == 450
    with pytest.raises(ValueError, match="0-45"):
        resolve_style({"marginPct": 45.5}, 1000)


def test_max_syllables_accepts_8_and_refuses_true() -> None:
    assert resolve_style({"maxSyllablesPerLine": 8}, 1000).max_syllables == 8
    assert resolve_style({"maxSyllablesPerLine": 2}, 1000).max_syllables == 2
    for bad in (True, 1, 9, 4.0):
        with pytest.raises(ValueError, match="2-8"):
            resolve_style({"maxSyllablesPerLine": bad}, 1000)


def test_unknown_font_lists_the_allowed_families() -> None:
    with pytest.raises(ValueError, match="Comic Sans") as excinfo:
        resolve_style({"fontFamily": "Comic Sans"}, 1000)
    assert "DejaVu Sans" in str(excinfo.value)
    assert "Anton" in str(excinfo.value)


def test_held_back_family_is_refused() -> None:
    with pytest.raises(ValueError, match="Lora"):
        resolve_style({"fontFamily": "Lora"}, 1000)


@pytest.mark.parametrize(("position", "align"), [("top", 8), ("middle", 5), ("bottom", 2)])
def test_position_maps_to_alignment(position: str, align: int) -> None:
    assert resolve_style({"position": position}, 1000).align == align


def test_unknown_position_is_refused() -> None:
    with pytest.raises(ValueError, match="top, middle, bottom"):
        resolve_style({"position": "left"}, 1000)


def test_highlight_mode_colour_pairs() -> None:
    colours = {"textColor": "#112233", "highlightColor": "#AABBCC"}
    sweep = resolve_style(colours, 1000)
    assert (sweep.mode, sweep.primary, sweep.secondary) == ("sweep", "&H00CCBBAA", "&H00332211")
    jump = resolve_style({**colours, "highlightMode": "jump"}, 1000)
    assert (jump.mode, jump.primary, jump.secondary) == ("jump", "&H00CCBBAA", "&H00332211")
    off = resolve_style({**colours, "highlightMode": "off"}, 1000)
    assert (off.mode, off.primary, off.secondary) == ("off", "&H00332211", "&H00332211")


def test_unknown_highlight_mode_is_refused() -> None:
    with pytest.raises(ValueError, match="sweep, jump, off"):
        resolve_style({"highlightMode": "blink"}, 1000)


def test_preset_backdrop_draws_a_box_instead_of_the_text_outline() -> None:
    style = resolve_style({"backdrop": "dark_soft", "outlineColor": "#FF0000"}, 1000)
    assert (style.border_style, style.outline, style.outline_colour) == (3, 6, "&H99000000")
    assert (style.shadow, style.back_colour) == (1, "&H00000000")


def test_custom_backdrop_uses_its_colour() -> None:
    style = resolve_style({"backdrop": "custom", "backdropColor": "#102030"}, 1000)
    assert (style.border_style, style.outline, style.outline_colour) == (3, 6, "&H00302010")


def test_custom_backdrop_without_colour_is_refused() -> None:
    with pytest.raises(ValueError, match="backdropColor"):
        resolve_style({"backdrop": "custom"}, 1000)


def test_backdrop_colour_without_custom_backdrop_is_refused() -> None:
    with pytest.raises(ValueError, match="backdropColor"):
        resolve_style({"backdrop": "none", "backdropColor": "#102030"}, 1000)


def test_unknown_backdrop_is_refused() -> None:
    with pytest.raises(ValueError, match="dark_soft"):
        resolve_style({"backdrop": "glass"}, 1000)


@pytest.mark.parametrize(("name", "width"), [("none", 0), ("thin", 2), ("medium", 5), ("thick", 9)])
def test_outline_widths(name: str, width: int) -> None:
    style = resolve_style({"outlineWidth": name, "outlineColor": "#FF0000"}, 1000)
    assert (style.outline, style.outline_colour, style.border_style) == (width, "&H000000FF", 1)


def test_unknown_outline_width_is_refused() -> None:
    with pytest.raises(ValueError, match="none, thin, medium, thick"):
        resolve_style({"outlineWidth": "huge"}, 1000)


def test_unknown_outline_width_is_refused_under_a_backdrop() -> None:
    with pytest.raises(ValueError, match="none, thin, medium, thick"):
        resolve_style({"backdrop": "dark_soft", "outlineWidth": "huge"}, 1000)


def test_bad_highlight_colour_is_refused_when_highlight_is_off() -> None:
    with pytest.raises(ValueError, match="#RRGGBB"):
        resolve_style({"highlightMode": "off", "highlightColor": "red"}, 1920)


def test_shadow_absent_is_one_black_pixel() -> None:
    style = resolve_style({}, 1920)
    assert (style.shadow, style.back_colour) == (1, "&H00000000")


def test_shadow_null_is_none() -> None:
    style = resolve_style({"shadow": None}, 1920)
    assert (style.shadow, style.back_colour) == (0, "&H00000000")


def test_shadow_colour_is_four_percent_of_the_font_size() -> None:
    style = resolve_style({"shadow": {"color": "#112233"}}, 1920)
    assert (style.shadow, style.back_colour) == (3, "&H00332211")


@pytest.mark.parametrize(
    "shadow", [{"colour": "#112233"}, {"color": "#112233", "x": 1}, "dark", []]
)
def test_malformed_shadow_is_refused(shadow: Any) -> None:
    with pytest.raises(ValueError, match="shadow"):
        resolve_style({"shadow": shadow}, 1920)


def test_non_object_style_is_refused() -> None:
    with pytest.raises(ValueError, match="object"):
        resolve_style(["bold"], 1920)  # type: ignore[arg-type]
