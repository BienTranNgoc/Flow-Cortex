"""Caption printing: style mapping, line breaking, karaoke tags, stacking and the ASS document."""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

import pytest
from test_render_document import scene, style
from test_render_timeline import plan

from flowcortex_worker.captions.line_breaks import FontMetrics, GlyphTable
from flowcortex_worker.render.subtitles import (
    FONT_SIZE_PCT,
    ass_document,
    ass_escape,
    caption_styles,
    caption_text,
    line_breaks_by_syllables,
    timecode,
)
from flowcortex_worker.render.timeline import TimelinePlan

Mutate = Callable[[dict[str, Any]], None]


def metrics_table() -> GlyphTable:
    # ascent + descent = 1 and every glyph 1 unit wide: a character is `size` pixels wide.
    face: FontMetrics = {
        "winAscent": 1,
        "winDescent": 0,
        "advances": {str(ord("a")): 1, str(ord(" ")): 1},
    }
    return {"fonts": {"DejaVu Sans:700": face}}


def no_table() -> GlyphTable:
    raise AssertionError("the glyph table must not be loaded")


def cue(**over: Any) -> dict[str, Any]:
    base: dict[str, Any] = {
        "id": "cu1",
        "startMs": 0,
        "endMs": 1000,
        "text": "Xin chào!",
        "styleId": None,
        "row": "rw1",
        "words": [],
    }
    base.update(over)
    return base


def captions(
    cues: list[dict[str, Any]],
    *,
    base_style: dict[str, Any] | None = None,
    styles: dict[str, dict[str, Any]] | None = None,
    rows: list[dict[str, Any]] | None = None,
    lang: str = "vi",
) -> Mutate:
    def apply(raw: dict[str, Any]) -> None:
        block = raw["timeline"]["captions"]
        block["style"] = base_style if base_style is not None else style()
        block["styles"] = styles if styles is not None else {}
        track = block["tracks"]["ct_main"]
        track["lang"] = lang
        track["rows"] = rows if rows is not None else [{"id": "rw1"}]
        track["cues"] = cues

    return apply


def planned(mutate: Mutate, profile: str = "final") -> TimelinePlan:
    return plan([scene(0, durMs=9000, srcOutMs=9000)], mutate=mutate, profile=profile)


def render(
    mutate: Mutate,
    *,
    start_ms: float = 0,
    dur_ms: float = 9000,
    loader: Callable[[], GlyphTable] = no_table,
    profile: str = "final",
) -> str:
    p = planned(mutate, profile)
    styles = caption_styles(p, p.canvas.width, p.canvas.height, loader)
    return ass_document(p, styles, start_ms, dur_ms)


def dialogues(text: str) -> list[str]:
    return [line for line in text.splitlines() if line.startswith("Dialogue:")]


def style_line(text: str, name: str = "Cap") -> str:
    return next(line for line in text.splitlines() if line.startswith(f"Style: {name},"))


def test_font_size_tiers() -> None:
    assert FONT_SIZE_PCT == {"small": 3.2, "medium": 4.375, "large": 5.4, "xlarge": 6.5}


def test_large_scaled_font_size() -> None:
    mutate = captions([cue()], base_style=style(fontSize="large", scale=1.5))
    p = planned(mutate)
    styles = caption_styles(p, 1080, 1920, no_table)
    assert styles.base.size == 156
    assert ",156," in style_line(ass_document(p, styles, 0, 9000))


def test_box_backdrop_draws_no_shadow() -> None:
    p = planned(captions([cue()], base_style=style(backdrop="dark_solid")))
    styles = caption_styles(p, 1080, 1920, no_table)
    assert styles.base.shadow == 0
    assert styles.base.caption.border_style == 3


def test_font_outside_the_library_is_refused() -> None:
    p = planned(captions([cue()], base_style=style(fontFamily="Lora")))
    with pytest.raises(ValueError, match=r"fontFamily is invalid.*Allowed"):
        caption_styles(p, 1080, 1920, no_table)


def test_unused_project_style_is_still_checked() -> None:
    mutate = captions([cue()], styles={"bad": style(fontFamily="Lora")})
    p = planned(mutate)
    with pytest.raises(ValueError, match="Lora"):
        caption_styles(p, 1080, 1920, no_table)


def test_free_position_prints_an5_pos() -> None:
    text = render(captions([cue()], base_style=style(x=0.5, y=0.5)))
    assert "{\\an5\\pos(540,960)}" in dialogues(text)[0]


def test_italic_and_uppercase_before_escaping() -> None:
    text = render(
        captions([cue(text="xin\nchào")], base_style=style(italic=True, uppercase=True)),
    )
    assert dialogues(text)[0].endswith("{\\i1}{\\1c&HFFFFFF&}XIN\\NCHÀO")


def test_pop_only_in_the_window_where_the_cue_starts() -> None:
    mutate = captions([cue(startMs=4800, endMs=5600)], base_style=style(animation="pop"))
    starts_here = dialogues(render(mutate, start_ms=4000, dur_ms=1000))[0]
    continues = dialogues(render(mutate, start_ms=5000, dur_ms=1000))[0]
    assert "\\fscx70\\fscy70\\t(0,150,\\fscx100\\fscy100)" in starts_here
    assert "\\fscx" not in continues


def test_fade_enters_only_where_the_cue_starts() -> None:
    mutate = captions([cue(startMs=4800, endMs=5600)], base_style=style(animation="fade"))
    assert "\\fad(150,0)" in dialogues(render(mutate, start_ms=4000, dur_ms=1000))[0]
    assert "\\fad" not in dialogues(render(mutate, start_ms=5000, dur_ms=1000))[0]


def test_syllable_breaks_balance_with_the_extra_on_the_lower_line() -> None:
    assert caption_text_of("a b c d e f g", max_syllables=4) == "a b c\\Nd e f g"
    assert caption_text_of("a b c d e f g", max_syllables=2) == "a\\Nb c\\Nd e\\Nf g"


def test_typed_newline_is_kept_and_counted_per_paragraph() -> None:
    assert caption_text_of("a b\nc d e f g", max_syllables=4) == "a b\\Nc d\\Ne f g"


def test_punctuation_alone_is_not_a_syllable() -> None:
    assert line_breaks_by_syllables("a b — c d", 2) == [(5, 6)]


def caption_text_of(text: str, max_syllables: int) -> str:
    p = planned(captions([cue(text=text)], base_style=style(maxSyllablesPerLine=max_syllables)))
    return caption_text(text, caption_styles(p, 1080, 1920, no_table).base)


def test_box_width_narrows_margins_breaks_by_width_and_sets_q2_on_that_cue_only() -> None:
    mutate = captions(
        [cue(text="aaa aaa"), cue(id="cu2", startMs=2000, endMs=3000, text="aaa aaa", styleId="s")],
        base_style=style(boxWidth=0.5),
        styles={"s": style()},
    )
    calls: list[int] = []

    def loader() -> GlyphTable:
        calls.append(1)
        return metrics_table()

    p = planned(mutate)
    styles = caption_styles(p, 1080, 1920, loader)
    text = ass_document(p, styles, 0, 9000)
    base_line = style_line(text, "Cap")
    assert ",270,270," in base_line
    assert ",0,0," in style_line(text, "Cap1")
    first, second = dialogues(text)
    assert first.endswith("{\\q2}{\\1c&HFFFFFF&}aaa\\Naaa")
    assert "\\q2" not in second
    assert calls == [1]


def test_glyph_table_is_not_loaded_without_a_box_width() -> None:
    render(captions([cue()]))


def test_box_width_is_ignored_for_a_japanese_track() -> None:
    mutate = captions([cue(text="a b c d e")], base_style=style(boxWidth=0.5), lang="ja-JP")
    text = render(mutate)
    assert ",0,0," in style_line(text)
    line = dialogues(text)[0]
    assert "\\q2" not in line
    assert line.endswith("a b\\Nc d e")


def test_karaoke_tags_follow_the_words() -> None:
    words = [
        {"t": "xin", "startMs": 0, "endMs": 400},
        {"t": "chào", "startMs": 500, "endMs": 900},
    ]
    line = dialogues(render(captions([cue(words=words)])))[0]
    assert line.endswith("{\\kf40}Xin {\\k10}{\\kf40}chào!")


def test_jump_mode_writes_k_not_kf() -> None:
    words = [{"t": "xin", "startMs": 0, "endMs": 400}, {"t": "chào", "startMs": 400, "endMs": 900}]
    mutate = captions([cue(words=words)], base_style=style(highlightMode="jump"))
    assert dialogues(render(mutate))[0].endswith("{\\k40}Xin {\\k50}chào!")


def test_cue_crossing_the_window_start_restarts_with_zero_length_first_word() -> None:
    words = [
        {"t": "xin", "startMs": 4800, "endMs": 4900},
        {"t": "chào", "startMs": 5000, "endMs": 5400},
    ]
    mutate = captions([cue(startMs=4800, endMs=5600, words=words)])
    line = dialogues(render(mutate, start_ms=5000, dur_ms=4000))[0]
    assert line.startswith("Dialogue: 0,0:00:00.00,0:00:00.60,")
    assert line.endswith("{\\kf0}Xin {\\kf40}chào!")


def test_cue_without_words_is_drawn_in_the_text_colour() -> None:
    mutate = captions([cue()], base_style=style(textColor="#112233"))
    assert dialogues(render(mutate))[0].endswith("{\\1c&H332211&}Xin chào!")


def test_highlight_off_prints_plain_text() -> None:
    words = [{"t": "xin", "startMs": 0, "endMs": 400}, {"t": "chào", "startMs": 400, "endMs": 900}]
    mutate = captions([cue(words=words)], base_style=style(highlightMode="off"))
    assert dialogues(render(mutate))[0].endswith(",Cap,Xin chào!")


def two_speakers(**row_style: Any) -> Mutate:
    cues = [
        cue(text="a b c d e", row="rw1", id="top"),
        cue(text="aaa", row="rw2", id="bottom"),
    ]
    return captions(cues, base_style=style(**row_style), rows=[{"id": "rw1"}, {"id": "rw2"}])


def test_overlapping_cues_on_two_rows_stack_from_the_bottom_margin() -> None:
    lines = dialogues(render(two_speakers()))
    top, bottom = lines
    # margin 300 of 1920, block of one line = 84 * 1.2 = 100.8
    assert "{\\an2\\pos(540,1620)}" in bottom
    assert "{\\an2\\pos(540,1519)}" in top


def test_top_anchored_rows_stack_downwards() -> None:
    top, bottom = dialogues(render(two_speakers(position="top")))
    # top margin 300; row 0 has two lines (201.6)
    assert "{\\an8\\pos(540,300)}" in top
    assert "{\\an8\\pos(540,502)}" in bottom


@pytest.mark.parametrize("ids", [("a", "b"), ("x", "x"), (None, None)])
def test_stacked_slots_do_not_depend_on_cue_ids(ids: tuple[str | None, str | None]) -> None:
    cues = []
    for cue_id, row in zip(ids, ("rw1", "rw2"), strict=True):
        item = cue(row=row, endMs=2000)
        if cue_id is None:
            del item["id"]
        else:
            item["id"] = cue_id
        cues.append(item)
    mutate = captions(cues, rows=[{"id": "rw1"}, {"id": "rw2"}])
    first, second = dialogues(render(mutate))
    assert "{\\an2\\pos(540,1519)}" in first
    assert "{\\an2\\pos(540,1620)}" in second


def test_crlf_in_cue_text_is_one_typed_newline() -> None:
    text = render(captions([cue(text="A\r\nB")]))
    (line,) = dialogues(text)
    assert line.endswith("A\\NB")
    assert "\r" not in text
    assert ass_escape("A\rB\r\nC") == "A\\NB\\NC"


def test_single_row_overlap_is_left_to_libass() -> None:
    cues = [cue(id="one"), cue(id="two", row="rw1")]
    for line in dialogues(render(captions(cues))):
        assert "\\pos" not in line


def test_touching_cues_do_not_stack() -> None:
    cues = [
        cue(id="one", row="rw1", endMs=1000),
        cue(id="two", row="rw2", startMs=1000, endMs=2000),
    ]
    mutate = captions(cues, rows=[{"id": "rw1"}, {"id": "rw2"}])
    for line in dialogues(render(mutate)):
        assert "\\pos" not in line


def test_window_clipping_drops_cues_outside_and_shifts_the_rest() -> None:
    cues = [cue(id="in", startMs=5500, endMs=6500), cue(id="out", startMs=100, endMs=900)]
    lines = dialogues(render(captions(cues), start_ms=5000, dur_ms=3000))
    assert len(lines) == 1
    assert lines[0].startswith("Dialogue: 0,0:00:00.50,0:00:01.50,")


def test_braces_and_backslash_in_cue_text_are_escaped() -> None:
    assert ass_escape("{bold} a\\b") == "\\{bold\\} a\\\\b"
    line = dialogues(render(captions([cue(text="{bold} a\\b")])))[0]
    assert line.endswith("\\{bold\\} a\\\\b")


def test_draft_keeps_canvas_coordinates() -> None:
    text = render(captions([cue()]), profile="draft")
    assert "PlayResX: 1080" in text
    assert "PlayResY: 1920" in text
    assert "WrapStyle: 0" in text
    assert "ScaledBorderAndShadow: yes" in text


def test_timecode_never_reads_sixty_seconds() -> None:
    assert timecode(59999.9) == "0:01:00.00"
    assert timecode(3_723_450) == "1:02:03.45"
    assert timecode(-5) == "0:00:00.00"
