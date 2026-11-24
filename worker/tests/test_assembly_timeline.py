"""Timeline and dub-segment placement: the filter graphs and the track length are pure rules."""

from __future__ import annotations

import pytest

from flowcortex_worker.assembly.timeline import Slot, place_segments, segment_graph, timeline_graph

# --- timeline_audio: mono 48 kHz track of an exact length --------------------------------


def test_two_lines_on_a_ten_second_track_are_delayed_summed_and_trimmed() -> None:
    graph = timeline_graph([1.0, 6.25], 10.0)

    chains = graph.split(";")
    assert chains[0] == (
        "[0:a]aresample=48000,aformat=channel_layouts=mono,adelay=delays=1000:all=1[a0]"
    )
    assert chains[1] == (
        "[1:a]aresample=48000,aformat=channel_layouts=mono,adelay=delays=6250:all=1[a1]"
    )
    assert chains[2] == (
        "[a0][a1]amix=inputs=2:normalize=0:dropout_transition=0,"
        "asetpts=N/SR/TB,apad=whole_dur=10.000,atrim=end=10.000[out]"
    )


def test_a_delay_is_the_start_rounded_to_whole_milliseconds() -> None:
    assert "adelay=delays=1235:all=1" in timeline_graph([1.2346], 10.0)


def test_only_a_sped_up_clip_is_retimed() -> None:
    chains = timeline_graph([8.0, 20.6], 24.0, [1.25, 1.0]).split(";")

    assert chains[0] == (
        "[0:a]aresample=48000,aformat=channel_layouts=mono,"
        "atempo=1.250,adelay=delays=8000:all=1,asetpts=N/SR/TB[a0]"
    )
    assert "atempo" not in chains[1]
    assert "asetpts" not in chains[1]


def test_a_graph_without_speed_ups_is_the_graph_with_unit_speeds() -> None:
    plain = timeline_graph([8.0, 20.6], 24.0)

    assert timeline_graph([8.0, 20.6], 24.0, [1, 1]) == plain
    assert "atempo" not in plain


# --- segment_dub: stereo 44.1 kHz, the track ends with the last clip ----------------------


def test_segment_graph_delays_every_channel_and_mixes_without_normalising() -> None:
    graph = segment_graph([0, 3000])

    assert graph == (
        "[0:a]aresample=44100,aformat=channel_layouts=stereo,adelay=0|0[a0];"
        "[1:a]aresample=44100,aformat=channel_layouts=stereo,adelay=3000|3000[a1];"
        "[a0][a1]amix=inputs=2:duration=longest:normalize=0[out]"
    )


def test_segment_graph_needs_a_clip() -> None:
    with pytest.raises(ValueError, match="at least one"):
        segment_graph([])


def test_the_last_line_longer_than_its_slot_is_not_cut() -> None:
    slots = [Slot(0, 0, 2000), Slot(1, 3000, 4000)]

    placement = place_segments(slots, {0: 1.8, 1: 1.6})

    assert placement.track_seconds == pytest.approx(4.6)
    assert placement.adjustments[1] == {
        "segIdx": 1,
        "startSec": 3.0,
        "targetSec": 1.0,
        "actualSec": 1.6,
        "overlap": 0.6,
    }
    assert placement.adjustments[0]["overlap"] == 0


def test_the_track_ends_at_the_latest_start_plus_clip_not_the_last_listed() -> None:
    slots = [Slot(0, 5000, 6000), Slot(1, 0, 1000)]

    placement = place_segments(slots, {0: 1.0, 1: 4.0})

    assert placement.track_seconds == pytest.approx(6.0)


def test_clips_are_placed_in_start_order_and_adjustments_keep_the_listed_order() -> None:
    slots = [Slot(7, 5000, 6000), Slot(3, 1000, 2000)]

    placement = place_segments(slots, {7: 1.0, 3: 1.0})

    assert [slot.index for slot in placement.order] == [3, 7]
    assert [row["segIdx"] for row in placement.adjustments] == [7, 3]


def test_a_slot_without_a_clip_is_left_out() -> None:
    slots = [Slot(0, 0, 1000), Slot(1, 2000, 3000)]

    placement = place_segments(slots, {1: 0.9})

    assert [slot.index for slot in placement.order] == [1]
    assert placement.track_seconds == pytest.approx(2.9)
