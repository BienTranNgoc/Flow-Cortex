"""ASS generation: word cleaning, validation, line grouping and Dialogue timing."""

from __future__ import annotations

from typing import Any

import pytest

from flowcortex_worker.captions.ass import (
    CaptionWord,
    build_ass,
    clean_word,
    group_words,
    validate_words,
)
from flowcortex_worker.captions.style import resolve_style

STYLE_LINE = "Style: K,DejaVu Sans,84,&H0000E0FF,&H00FFFFFF,&H00000000,&H00000000,-1,1,5,1,2,300"


def w(word: str, start: float, end: float, line_break: bool = False) -> CaptionWord:
    return CaptionWord(word=word, start=start, end=end, line_break=line_break)


def seq(*texts: str, line_break_on: str = "") -> list[CaptionWord]:
    return [w(t, i, i + 1, t == line_break_on) for i, t in enumerate(texts)]


def texts(groups: list[list[CaptionWord]]) -> list[list[str]]:
    return [[x.word for x in g] for g in groups]


def dialogues(ass: str) -> list[str]:
    return [line for line in ass.splitlines() if line.startswith("Dialogue:")]


def burn(words: list[CaptionWord], spec: dict[str, Any] | None = None) -> tuple[str, int]:
    return build_ass(words, 0.0, resolve_style(spec, 1920), 1080, 1920)


def test_clean_word_removes_braces_backslash_and_newline() -> None:
    assert clean_word("a{b}\\c\n") == "abc"


def test_clean_word_collapses_whitespace() -> None:
    assert clean_word("  a \t b\n c ") == "a b c"


def test_clean_word_composes_nfd_to_nfc() -> None:
    assert clean_word("xỉn") == "xỉn"


@pytest.mark.parametrize("words", [None, "xin", {"word": "a"}, []])
def test_validate_words_refuses_non_list_or_empty(words: Any) -> None:
    with pytest.raises(ValueError, match="words"):
        validate_words(words, 0)


def test_validate_words_refuses_when_no_entry_is_timed() -> None:
    with pytest.raises(ValueError, match="timestamps"):
        validate_words([{"word": "a", "start": 0.0}, {"word": "b", "start": 1.0}], 0)


def test_validate_words_refuses_a_non_object_entry() -> None:
    with pytest.raises(ValueError, match="words"):
        validate_words([{"word": "a", "start": 0, "end": 1}, "b"], 0)


def test_validate_words_drops_untimed_entries() -> None:
    words, offset = validate_words(
        [
            {"word": "a", "start": 0, "end": 1},
            {"word": "b", "start": 1},
            {"word": "c", "start": 2, "end": 3, "lineBreak": True},
            {"word": "d", "start": True, "end": 4},
            {"word": "e", "start": 4, "end": float("nan")},
            {"word": "f", "start": 5, "end": 6},
        ],
        None,
    )
    assert [x.word for x in words] == ["a", "c", "f"]
    assert [x.line_break for x in words] == [False, True, False]
    assert offset == 0.0


@pytest.mark.parametrize("offset", ["1", float("inf"), float("nan"), True, []])
def test_validate_words_refuses_a_bad_offset(offset: Any) -> None:
    with pytest.raises(ValueError, match="startOffset"):
        validate_words([{"word": "a", "start": 0, "end": 1}], offset)


def test_validate_words_returns_a_float_offset() -> None:
    _, offset = validate_words([{"word": "a", "start": 0, "end": 1}], 2)
    assert offset == 2.0
    assert isinstance(offset, float)


def test_group_words_caps_the_line() -> None:
    assert texts(group_words(seq(*"abcdef"), 4)) == [list("abcd"), list("ef")]


def test_group_words_keeps_a_compound_pair_at_the_cap() -> None:
    groups = group_words(seq("a", "b", "c", "hiệu", "quả"), 4)
    assert texts(groups) == [["a", "b", "c", "hiệu", "quả"]]


def test_group_words_cuts_between_words_that_are_not_a_pair() -> None:
    assert texts(group_words(seq("a", "b", "c", "d", "e"), 4)) == [list("abcd"), ["e"]]


def test_group_words_matches_a_pair_written_in_nfd() -> None:
    groups = group_words(seq("a", "b", "xỉn", "màu"), 3)
    assert len(groups) == 1


def test_group_words_line_break_wins_over_the_cap() -> None:
    groups = group_words(seq(*"abcdef", line_break_on="c"), 2)
    assert texts(groups) == [list("abc"), list("def")]


def test_sweep_highlight_of_two_words() -> None:
    ass, lines = burn([w("xin", 0.0, 0.4), w("chào", 0.5, 0.9)])
    assert lines == 1
    assert dialogues(ass) == ["Dialogue: 0,0:00:00.00,0:00:00.90,K,{\\kf40}xin{\\k10} {\\kf40}chào"]
    assert STYLE_LINE in ass


def test_jump_uses_k_tags() -> None:
    ass, _ = burn([w("a", 0, 0.4)], {"highlightMode": "jump"})
    assert dialogues(ass)[0].endswith(",K,{\\k40}a")


def test_off_mode_has_no_tags() -> None:
    ass, _ = burn(
        [w("a", 0, 0.4), w("b", 0.4, 0.8)], {"highlightMode": "off", "textColor": "#112233"}
    )
    assert dialogues(ass)[0].endswith(",K,a b")
    assert "\\k" not in dialogues(ass)[0]
    assert "&H00332211,&H00332211" in ass


def test_silence_between_words_adds_a_pause_tag() -> None:
    ass, _ = burn([w("a", 0.0, 0.4), w("b", 1.0, 1.4)])
    assert dialogues(ass)[0].endswith(",K,{\\kf40}a{\\k60} {\\kf40}b")


def test_overlapping_words_start_where_the_cursor_stands() -> None:
    ass, _ = burn([w("a", 0.0, 0.4), w("b", 0.3, 0.6)])
    assert dialogues(ass)[0].endswith(",K,{\\kf40}a {\\kf20}b")


def test_negative_start_is_clamped_in_the_timecode() -> None:
    ass, _ = build_ass([w("a", 0.5, 1.0)], 1.0, resolve_style({}, 1920), 1080, 1920)
    assert dialogues(ass)[0].startswith("Dialogue: 0,0:00:00.00,0:00:00.00,K,")


def test_timecode_carries_into_minutes_and_hours() -> None:
    ass, _ = burn([w("a", 3661.5, 3662.0)])
    assert dialogues(ass)[0].startswith("Dialogue: 0,1:01:01.50,1:01:02.00,K,")


def test_timecode_never_prints_sixty_seconds() -> None:
    ass, _ = burn([w("a", 59.999, 60.0)])
    assert dialogues(ass)[0].startswith("Dialogue: 0,0:01:00.00,0:01:00.00,K,")


def test_landscape_input_writes_play_res_and_font_from_height() -> None:
    ass, _ = build_ass([w("a", 0, 1)], 0.0, resolve_style({}, 1080), 1920, 1080)
    assert "PlayResX: 1920\nPlayResY: 1080\n" in ass
    assert f"Style: K,DejaVu Sans,{round(4.375 * 1080 / 100)}," in ass


def test_border_style_is_a_style_line_field_after_bold() -> None:
    ass, _ = burn([w("a", 0, 1)], {"backdrop": "dark_solid"})
    assert "Bold,BorderStyle,Outline," in ass
    style_line = next(x for x in ass.splitlines() if x.startswith("Style: K,"))
    assert ",-1,3,6," in style_line
    assert "BorderStyle:" not in ass
    plain, _ = burn([w("a", 0, 1)])
    plain_style = next(x for x in plain.splitlines() if x.startswith("Style: K,"))
    assert ",-1,1," in plain_style
    assert "BorderStyle:" not in plain


def test_dialogue_text_is_cleaned() -> None:
    ass, lines = burn([w("a{b}\\c\n", 0, 1)])
    assert lines == 1
    assert dialogues(ass)[0].endswith("}abc")


def test_each_group_is_one_dialogue_event() -> None:
    ass, lines = burn(seq(*"abcdef"))
    assert lines == 2
    assert len(dialogues(ass)) == 2
