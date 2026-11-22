"""Speaker turns: whole milliseconds, labels numbered by first speech."""

from __future__ import annotations

from flowcortex_worker.audio.speaker_turns import to_ms, turns


def test_raw_labels_are_renumbered_by_first_speech() -> None:
    segments = [(2.0, 3.5, 7), (0.0, 1.2, 4), (1.2001, 1.2003, 9)]
    assert turns(segments) == [
        {"fromMs": 0, "toMs": 1200, "label": 1},
        {"fromMs": 2000, "toMs": 3500, "label": 2},
    ]


def test_halves_round_up() -> None:
    assert to_ms(0.0005) == 1
    assert to_ms(1.2005) == 1201
    assert to_ms(0.0004) == 0
    assert to_ms(0.0) == 0


def test_a_sub_millisecond_segment_is_dropped() -> None:
    assert turns([(1.0, 1.0004, 3)]) == []


def test_a_dropped_turn_does_not_consume_a_label() -> None:
    result = turns([(0.0, 0.0001, 5), (1.0, 2.0, 8)])
    assert result == [{"fromMs": 1000, "toMs": 2000, "label": 1}]


def test_overlapping_turns_are_kept() -> None:
    result = turns([(0.0, 2.0, 1), (1.0, 3.0, 2)])
    assert result == [
        {"fromMs": 0, "toMs": 2000, "label": 1},
        {"fromMs": 1000, "toMs": 3000, "label": 2},
    ]


def test_a_returning_speaker_keeps_the_first_label() -> None:
    result = turns([(0.0, 1.0, 9), (1.0, 2.0, 3), (2.0, 3.0, 9)])
    assert [t["label"] for t in result] == [1, 2, 1]


def test_nobody_speaks() -> None:
    assert turns([]) == []
