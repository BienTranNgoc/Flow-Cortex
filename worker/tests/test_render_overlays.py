"""Overlay printing: ASS styles and events of text overlays, filters of image overlays."""

from __future__ import annotations

import dataclasses
from typing import Any

import pytest
from test_render_document import image_overlay, scene, with_overlays
from test_render_timeline import plan

from flowcortex_worker.render.overlays import (
    ACCENT_HEX,
    AN_COL,
    AN_ROW,
    BLUR_SIGMA_TO_ASS,
    OVERLAY_BOX_PAD,
    OVERLAY_ENTER_MS,
    OVERLAY_FADE_MS,
    OVERLAY_SHADOW_PCT,
    OVERLAY_SLIDE_PCT,
    OVERLAY_ZOOM_FROM_PCT,
    ImageWindow,
    image_overlay_filters,
    image_window,
    image_windows,
    overlay_width_px,
    sticker_events,
    sticker_frame,
    sticker_style_lines,
    text_overlays_in_draw_order,
    thin_strip_warnings,
)
from flowcortex_worker.render.timeline import (
    Chunk,
    PlannedImageOverlay,
    PlannedTextOverlay,
)

H, W = 1920, 1080
WINDOW = "0:00:00.00,0:00:02.00"


def text(style: dict[str, Any] | None = None, **over: Any) -> PlannedTextOverlay:
    base: dict[str, Any] = {
        "id": "ov1",
        "text": "Mua ngay",
        "x": 0.5,
        "y": 0.5,
        "size_pct": 5,
        "color": "#FFFFFF",
        "bg": "none",
        "anim": "none",
        "start_ms": 0,
        "end_ms": 2000,
        "row_index": None,
    }
    base.update(over)
    return PlannedTextOverlay(style=style or {}, **base)


def image(**over: Any) -> PlannedImageOverlay:
    base: dict[str, Any] = {
        "id": "ov2",
        "asset_id": "a2",
        "x": 0.5,
        "y": 0.5,
        "width_pct": 20,
        "opacity": 1.0,
        "anim": "none",
        "start_ms": 0,
        "end_ms": 2000,
        "animation": None,
        "row_index": None,
    }
    base.update(over)
    return PlannedImageOverlay(**base)


def styles_of(overlay: PlannedTextOverlay, height: int = H) -> list[str]:
    return sticker_style_lines(overlay, height, "DejaVu Sans", name="Ov0")


def events_of(
    overlay: PlannedTextOverlay,
    *,
    starts: bool = True,
    ends: bool = True,
    width: int = W,
    height: int = H,
) -> list[str]:
    return sticker_events(overlay, width, height, "DejaVu Sans", WINDOW, starts, ends, name="Ov0")


def test_constants_match_the_reference() -> None:
    assert ACCENT_HEX == "#FF6B35"
    assert (OVERLAY_ENTER_MS, OVERLAY_SLIDE_PCT, OVERLAY_ZOOM_FROM_PCT) == (400, 8, 60)
    assert (OVERLAY_SHADOW_PCT, OVERLAY_BOX_PAD, OVERLAY_FADE_MS) == (4, 6, 200)
    assert BLUR_SIGMA_TO_ASS == 1.1774
    assert AN_ROW == {"top": 7, "middle": 4, "bottom": 1}
    assert AN_COL == {"left": 0, "center": 1, "right": 2}


def test_default_edges_without_a_backdrop() -> None:
    (line,) = styles_of(text())
    # size 5 % of 1920 = 96, two black outline pixels, no shadow, BorderStyle 1.
    assert line == (
        "Style: Ov0,DejaVu Sans,96,&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,-1,2,0,5,0,0,0,1"
    )


def test_outline_null_draws_no_edge_and_a_width_scales_with_the_size() -> None:
    assert ",&H00000000,&H00000000,-1,0,0,5," in styles_of(text({"outline": None}))[0]
    wide = text({"outline": {"widthPct": 5, "color": "#112233"}}, size_pct=100 * 100 / H)
    line = styles_of(wide)[0]
    assert ",&H00332211,&H00000000,-1,5,0,5," in line


def test_dark_backdrop_alone_is_one_box_style_at_35_percent_black() -> None:
    (line,) = styles_of(text(bg="dark"))
    assert line == (
        "Style: Ov0,DejaVu Sans,96,&H00FFFFFF,&H00FFFFFF,&H59000000,&H00000000,-1,6,0,5,0,0,0,3"
    )


def test_accent_and_custom_backdrops() -> None:
    assert "&H00356BFF" in styles_of(text(bg="accent"))[0]
    assert "&H00FFAA00" in styles_of(text({"bgColor": "#00AAFF"}, bg="custom"))[0]


def test_box_under_an_outlined_text_is_two_styles() -> None:
    box, body = styles_of(text({"outline": {"widthPct": 3, "color": "#000000"}}, bg="dark"))
    assert box.startswith("Style: Ov0_box,") and box.endswith(",0,5,0,0,0,3")
    assert "&HFFFFFFFF,&HFFFFFFFF,&H59000000" in box
    assert body.startswith("Style: Ov0,") and body.endswith(",1")


def test_plain_shadow_is_four_percent_of_the_size() -> None:
    overlay = text({"shadow": {"color": "#FF0000"}}, size_pct=100 * 100 / H)
    line = styles_of(overlay)[0]
    assert line.endswith("&H000000FF,-1,2,4,5,0,0,0,1")


def test_extended_shadow_and_glow_add_their_own_styles() -> None:
    overlay = text(
        {
            "shadow": {"color": "#000000", "distancePct": 10},
            "glow": {"color": "#FFFF00", "spreadPct": 5, "blurPct": 5, "strength": 2},
        }
    )
    lines = styles_of(overlay)
    assert [line.split(",")[0] for line in lines] == [
        "Style: Ov0_shadow",
        "Style: Ov0_glow",
        "Style: Ov0",
    ]
    # No outline or shadow in the layer styles: the real values are inline tags.
    assert lines[1].endswith(",-1,0,0,5,0,0,0,1")
    # The extended shadow does not use the ASS shadow column of the text style.
    assert lines[2].endswith("&H00000000,&H00000000,-1,2,0,5,0,0,0,1")


def test_font_and_bold_overrides() -> None:
    line = styles_of(text({"font": "Lora", "bold": False}))[0]
    assert line.startswith("Style: Ov0,Lora,") and ",&H00000000,0,2,0,5," in line


def test_layered_box_sits_on_layer_one_hidden_and_text_on_layer_two() -> None:
    overlay = text({"outline": {"widthPct": 3, "color": "#000000"}}, bg="dark")
    box, body = events_of(overlay)
    assert box.startswith(f"Dialogue: 1,{WINDOW},Ov0_box,{{\\an5\\pos(540,960)\\1a&HFF&}}")
    assert body.startswith(f"Dialogue: 2,{WINDOW},Ov0,{{\\an5\\pos(540,960)}}")
    assert box.endswith("Mua ngay") and body.endswith("Mua ngay")


def test_letter_spacing_moves_the_anchor_by_half_a_spacing_per_column() -> None:
    centre = text({"letterSpacingPct": 10, "font": "Lora"}, size_pct=100 * 100 / H)
    (event,) = events_of(centre)
    assert "\\fsp10" in event and "\\pos(545,960)" in event
    left = text({"letterSpacingPct": 10, "align": "left"}, size_pct=100 * 100 / H)
    assert "\\pos(540,960)" in events_of(left)[0]
    right = text({"letterSpacingPct": 10, "align": "right"}, size_pct=100 * 100 / H)
    assert "\\pos(550,960)" in events_of(right)[0]


def test_rotation_italic_underline_and_opacity_tags() -> None:
    overlay = text({"rotate": 15, "italic": True, "underline": False, "opacity": 0.5})
    (event,) = events_of(overlay)
    assert "\\frz-15" in event and "\\i1" in event and "\\u0" in event
    assert "\\alpha&H80&" in event


def test_anchor_and_alignment_make_the_an_tag() -> None:
    (event,) = events_of(text({"anchor": "top", "align": "right"}))
    assert event.startswith(f"Dialogue: 1,{WINDOW},Ov0,{{\\an9\\pos(")


def test_soft_shadow_is_a_lower_layer_offset_and_blurred() -> None:
    overlay = text(
        {"shadow": {"color": "#000000", "distancePct": 10, "angle": 90, "blurPct": 5}},
        size_pct=100 * 100 / H,
    )
    shadow, body = events_of(overlay)
    assert shadow.startswith(f"Dialogue: -2,{WINDOW},Ov0_shadow,{{\\an5\\pos(540,970)")
    assert "\\bord0\\shad0\\blur5.887" in shadow
    assert body.startswith(f"Dialogue: 1,{WINDOW},Ov0,")


def test_shadow_opacity_multiplies_the_text_opacity() -> None:
    overlay = text({"opacity": 0.5, "shadow": {"color": "#000000", "opacity": 0.5, "angle": 0}})
    shadow = events_of(overlay)[0]
    assert shadow.count("\\alpha&HBF&") == 1


def test_three_glow_layers_blur_wider_by_half_a_sigma_each() -> None:
    glow = {"color": "#FFFF00", "spreadPct": 10, "blurPct": 5, "strength": 3}
    layers = events_of(text({"glow": glow}, size_pct=100 * 100 / H))[:-1]
    assert len(layers) == 3
    sigma = 5.0
    for i, layer in enumerate(layers):
        assert layer.startswith(f"Dialogue: -1,{WINDOW},Ov0_glow,")
        assert f"\\blur{sigma * (1 + 0.5 * i) * BLUR_SIGMA_TO_ASS:g}" in layer
        assert "\\bord10\\1c&H00FFFF&\\3c&H00FFFF&" in layer


def test_accent_segment_is_coloured_then_base_colour_and_outline_are_restored() -> None:
    overlay = text(
        {"accent": {"color": "#FF0000"}, "outline": {"widthPct": 2, "color": "#0000FF"}},
        text="Mua [[ngay]] đi",
    )
    (event,) = events_of(overlay)
    assert event.endswith("Mua {\\1c&H0000FF&\\3c&HFF0000&}ngay{\\1c&HFFFFFF&\\3c&HFF0000&} đi")
    assert "[[" not in event


def test_accent_outline_colour_overrides_the_outline_of_the_run() -> None:
    overlay = text({"accent": {"color": "#FF0000", "outlineColor": "#00FF00"}}, text="[[Hot]]")
    (event,) = events_of(overlay)
    assert "{\\1c&H0000FF&\\3c&H00FF00&}Hot{\\1c&HFFFFFF&\\3c&H000000&}" in event


def test_markers_are_stripped_without_an_accent() -> None:
    (event,) = events_of(text(text="Mua [[ngay]]"))
    assert event.endswith("Mua ngay")


def test_braces_and_newlines_in_the_text_are_escaped() -> None:
    (event,) = events_of(text(text="a{b}\\c\nd"))
    assert event.endswith("a\\{b\\}\\\\c\\Nd")


def test_per_line_style_sets_listed_lines_and_resets_the_rest_to_the_base() -> None:
    overlay = text(
        {"lines": [{"font": "Lora", "sizeScale": 2, "color": "#FF0000"}, {"sizeScale": 0.5}]},
        text="one\ntwo\nthree",
    )
    (event,) = events_of(overlay)
    # `\fs` persists across `\N`, so the unlisted third line states the base values again.
    assert event.endswith(
        "{\\fnLora\\fs192\\1c&H0000FF&}one\\N{\\fnDejaVu Sans\\fs48\\1c&HFFFFFF&}two"
        "\\N{\\fnDejaVu Sans\\fs96\\1c&HFFFFFF&}three"
    )


def test_per_line_style_reaches_the_shape_layers_without_colour() -> None:
    overlay = text(
        {
            "glow": {"color": "#FFFF00", "spreadPct": 5, "blurPct": 5, "strength": 1},
            "lines": [{"sizeScale": 2, "color": "#FF0000"}],
        },
        text="one\ntwo",
    )
    glow, body = events_of(overlay)
    assert glow.endswith("{\\fnDejaVu Sans\\fs192}one\\N{\\fnDejaVu Sans\\fs96}two")
    assert "\\1c&H0000FF&" in body


def test_fade_motion() -> None:
    (event,) = events_of(text(anim="fade"))
    assert "{\\fad(200,200)}Mua ngay" in event


def test_slide_up_starting_in_the_chunk_moves_from_eight_percent_below() -> None:
    (event,) = events_of(text(anim="slide_up"), ends=False)
    assert "\\move(540,1114,540,960,0,400)\\fad(400,0)" in event


def test_slide_up_continuing_across_a_chunk_boundary_stays_put() -> None:
    (event,) = events_of(text(anim="slide_up"), starts=False)
    assert "\\pos(540,960)\\fad(0,200)" in event
    assert "\\move" not in event


def test_zoom_in_starts_at_sixty_percent_and_grows() -> None:
    (event,) = events_of(text(anim="zoom_in"), ends=False)
    assert "\\fscx60\\fscy60\\t(0,400,\\fscx100\\fscy100)\\fad(400,0)" in event


def test_motion_with_no_anim_has_no_fade() -> None:
    (event,) = events_of(text())
    assert "\\fad" not in event


def test_overlay_width_is_even_and_never_below_two() -> None:
    assert overlay_width_px(image(width_pct=2), 360) == 8
    assert overlay_width_px(image(width_pct=1), 100) == 2
    assert overlay_width_px(image(width_pct=20), 1080) == 216


CHUNK = Chunk(scene_indexes=(0,), start_ms=1000, dur_ms=2000)


def test_window_of_an_overlay_crossing_the_chunk() -> None:
    # Starts 500 ms before the chunk (15 frames at 30 fps) and ends 1 s into it.
    window = image_window(image(start_ms=500, end_ms=2000), CHUNK, 30)
    assert window == ImageWindow(k0=0, k1=30, j0=15, frames=45)


def test_window_of_an_overlay_starting_inside_the_chunk() -> None:
    window = image_window(image(start_ms=1500, end_ms=2500), CHUNK, 30)
    assert window == ImageWindow(k0=15, k1=45, j0=0, frames=30)


@pytest.mark.parametrize("marks", [(0, 1000), (3000, 4000), (2000, 2000)])
def test_no_window_outside_the_chunk(marks: tuple[int, int]) -> None:
    assert image_window(image(start_ms=marks[0], end_ms=marks[1]), CHUNK, 30) is None


def test_windows_sort_lower_rows_first_and_skip_text() -> None:
    base = plan([scene(0, durMs=3000, srcOutMs=3000)])
    low, high = image(id="hi", row_index=0), image(id="lo", row_index=2)
    mixed = dataclasses.replace(base, overlays=(low, text(), high))
    found = image_windows(mixed, mixed.chunks[0])
    assert [o.id for o, _ in found] == ["lo", "hi"]


def test_draw_order_puts_row_zero_last() -> None:
    items: list[PlannedTextOverlay | PlannedImageOverlay] = [
        text(id="a", row_index=0),
        text(id="b", row_index=1),
        image(),
        text(id="c"),
    ]
    ordered = text_overlays_in_draw_order(items)
    assert [(position, o.id) for position, o in ordered] == [(1, "b"), (0, "a"), (3, "c")]


def filters_of(overlay: PlannedImageOverlay, window: ImageWindow, width: int = 360) -> list[str]:
    pieces, label = image_overlay_filters(overlay, window, 1, "[vx1]", 0, width, 30)
    assert label == "[vim0]"
    return pieces


def test_static_image_filters() -> None:
    window = ImageWindow(k0=0, k1=60, j0=0, frames=60)
    layer, chain = filters_of(image(width_pct=20, opacity=0.8), window)
    assert layer == (
        "[1:v]scale=72:'max(2,2*round(72*ih/iw/2))',format=rgba,colorchannelmixer=aa=0.8000,"
        "loop=loop=-1:size=1,trim=end_frame=60,settb=1/30000,setpts=(N+0)*1000,"
        "settb=1/30,setpts=N+0[im0]"
    )
    assert chain == (
        "[vx1][im0]overlay=x=0.500000*W-w/2:y=0.500000*H-h/2:"
        "enable='between(t,-0.0167,1.9833)'[vim0]"
    )


def test_fade_ramps_and_scales_opacity_for_a_short_overlay() -> None:
    # 300 ms = 9 frames: each ramp is 150 ms and the peak is 0.75 of the opacity.
    window = ImageWindow(k0=0, k1=9, j0=0, frames=9)
    layer = filters_of(image(anim="fade", opacity=1.0, end_ms=300), window)[0]
    assert "colorchannelmixer=aa=0.7500" in layer
    assert "fade=t=in:st=0:d=0.150000:alpha=1,fade=t=out:st=0.150000:d=0.150000:alpha=1" in layer


def test_long_overlay_fades_for_two_hundred_milliseconds() -> None:
    window = ImageWindow(k0=0, k1=60, j0=0, frames=60)
    layer = filters_of(image(anim="fade"), window)[0]
    assert "colorchannelmixer=aa=1.0000" in layer
    assert "fade=t=in:st=0:d=0.200000:alpha=1,fade=t=out:st=1.800000:d=0.200000:alpha=1" in layer


def test_animated_sheet_crops_a_cell_per_frame_before_scaling() -> None:
    sheet = {"frameW": 100, "frameH": 50, "cols": 4, "frameCount": 8, "frameMs": 100}
    window = ImageWindow(k0=5, k1=65, j0=12, frames=90)
    layer, _ = filters_of(image(animation=sheet), window)
    frame = "mod(floor((12+n)*1000/(30*100)),8)"
    assert f"crop=100:50:x='mod({frame},4)*100':y='floor({frame}/4)*50'" in layer
    assert layer.index("crop=") < layer.index("scale=")
    assert "scale=72:'max(2,2*round(72*50/100/2))'" in layer
    assert "setpts=(N+12)*1000" in layer and layer.endswith("settb=1/30,setpts=N+5[im0]")


def test_window_frame_marks_bound_the_enable_expression_by_half_a_frame() -> None:
    window = ImageWindow(k0=15, k1=45, j0=0, frames=30)
    _, chain = filters_of(image(), window)
    assert "enable='between(t,0.4833,1.4833)'" in chain


def test_sticker_frame_follows_the_member_app_formula() -> None:
    sheet = {"frameMs": 100, "frameCount": 8}
    assert sticker_frame(30, 0, 30, sheet) == 2
    assert sticker_frame(0, 0, 30, sheet) == 0
    assert sticker_frame(240, 0, 30, sheet) == 0
    assert sticker_frame(40, 10, 30, sheet) == 2


def test_thin_strip_is_warned_about_and_a_taller_image_is_not() -> None:
    def overlays(raw: dict[str, Any]) -> None:
        with_overlays(raw, image_overlay(id="strip", assetId="a2", widthPct=2))

    planned = plan([scene(0)], profile="draft", mutate=overlays)
    assert thin_strip_warnings(planned, {"a2": {"width": 2000, "height": 10}}) == [
        "image overlay strip: the 2000x10 image drawn 10 px wide is under 1 px high; "
        "it is drawn 2 px high",
    ]
    assert thin_strip_warnings(planned, {"a2": {"width": 2000, "height": 400}}) == []
