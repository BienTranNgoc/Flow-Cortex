"""Rules of the grade_match capability, called with numbers: no ffmpeg."""

from __future__ import annotations

from pathlib import Path
from typing import Any

import pytest

from flowcortex_worker.errors import PermanentError
from flowcortex_worker.video_analysis.streams import VideoStream
from flowcortex_worker.video_ops import grade
from flowcortex_worker.video_ops.media import Clip

ZERO = {"Y": 0.0, "U": 0.0, "V": 0.0}


def refused(call: Any) -> PermanentError:
    with pytest.raises(PermanentError) as caught:
        call()
    return caught.value


def clip(*, audio: bool = True, tagged: bool = True, width: int = 1280, height: int = 720) -> Clip:
    return Clip(2000, VideoStream(0, width, height, (25, 1)), audio, tagged)


# --- targets -------------------------------------------------------------------------------------


def test_one_side_given_is_both_sides_and_a_null_channel_is_left_alone() -> None:
    target_in, target_out = grade.read_targets({"targetIn": {"y": 120, "u": None, "v": 130}})

    assert target_in == {"Y": 120.0, "U": None, "V": 130.0}
    assert target_out == target_in


def test_each_channel_takes_the_other_sides_value_when_it_is_null() -> None:
    target_in, target_out = grade.read_targets(
        {"targetIn": {"y": 100}, "targetOut": {"y": None, "u": 90}}
    )

    assert target_in == {"Y": 100.0, "U": 90.0, "V": None}
    assert target_out == {"Y": 100.0, "U": 90.0, "V": None}


def test_no_target_leaves_every_channel_alone() -> None:
    target_in, target_out = grade.read_targets({})

    assert set(target_in.values()) == {None}
    assert set(target_out.values()) == {None}
    assert grade.read_targets({"targetIn": None, "targetOut": None}) == (target_in, target_out)


def test_the_bounds_of_the_range_are_accepted() -> None:
    target_in, _ = grade.read_targets({"targetIn": {"y": 0, "u": 255.0}})

    assert target_in["Y"] == 0.0 and target_in["U"] == 255.0


@pytest.mark.parametrize(
    ("params", "field"),
    [
        ({"targetOut": {"y": 300}}, "targetOut"),
        ({"targetIn": {"u": -1}}, "targetIn"),
        ({"targetOut": {"y": "120"}}, "targetOut"),
        ({"targetIn": {"v": True}}, "targetIn"),
        ({"targetIn": {"y": float("nan")}}, "targetIn"),
        ({"targetIn": {"y": float("inf")}}, "targetIn"),
        ({"targetIn": "bright"}, "targetIn"),
        ({"targetOut": [120]}, "targetOut"),
    ],
)
def test_a_target_outside_the_domain_is_a_contract_violation_naming_the_field(
    params: dict[str, Any], field: str
) -> None:
    error = refused(lambda: grade.read_targets(params))

    assert error.code == "contract_violation"
    assert field in error.message


# --- shortfall -----------------------------------------------------------------------------------


def test_shots_longer_than_the_trim_need_no_padding() -> None:
    assert grade.shortfall(2000, 1500) == 0
    assert grade.shortfall(2000, 2000) == 0


def test_a_shortfall_up_to_500_ms_is_padded() -> None:
    assert grade.shortfall(2000, 2300) == 300
    assert grade.shortfall(2000, 2500) == 500


def test_a_shortfall_over_500_ms_is_input_rejected_naming_both_lengths() -> None:
    error = refused(lambda: grade.shortfall(2000, 2600))

    assert error.code == "input_rejected"
    assert "2600" in error.message and "2000" in error.message


# --- measuring -----------------------------------------------------------------------------------


def test_the_means_are_the_values_of_the_channels_lines() -> None:
    text = (
        "frame:0 pts:0 pts_time:0\nlavfi.signalstats.YAVG=100.5\n"
        "frame:1 pts:1 pts_time:0.04\nlavfi.signalstats.YAVG=101\n"
    )

    assert grade.parse_signal_means(text, "Y") == [100.5, 101.0]
    assert grade.parse_signal_means(text.replace("YAVG", "UAVG"), "U") == [100.5, 101.0]


def test_a_channel_with_no_frame_value_is_probe_failed_naming_it() -> None:
    error = refused(lambda: grade.parse_signal_means("lavfi.signalstats.YAVG=1\n", "V"))

    assert error.code == "probe_failed"
    assert "V" in error.message


def test_a_two_frame_stream_averages_one_frame_at_each_edge() -> None:
    assert grade.edge_window(2, 25.0) == 1
    series = {"Y": [10.0, 30.0], "U": [100.0, 110.0], "V": [1.0, 2.0]}

    start, end = grade.edge_means(series, 25.0)

    assert start == {"Y": 10.0, "U": 100.0, "V": 1.0}
    assert end == {"Y": 30.0, "U": 110.0, "V": 2.0}


def test_the_edge_window_is_a_third_of_a_second_of_frames_and_at_most_half_the_stream() -> None:
    assert grade.edge_window(30, 25.0) == 8
    assert grade.edge_window(100, 25.0) == 8
    assert grade.edge_window(10, 25.0) == 5
    assert grade.edge_window(1, 25.0) == 1
    assert grade.edge_window(100, 2.0) == 1


def test_each_edge_averages_its_window() -> None:
    values = [float(v) for v in range(30)]

    start, end = grade.edge_means({"Y": values, "U": values, "V": values}, 25.0)

    assert start["Y"] == pytest.approx(sum(range(8)) / 8)
    assert end["Y"] == pytest.approx(sum(range(22, 30)) / 8)


def test_an_offset_is_the_target_minus_the_mean_rounded_to_two_places() -> None:
    target_in = {"Y": 110.0, "U": None, "V": 130.0}
    target_out = {"Y": 120.0, "U": None, "V": None}
    start = {"Y": 100.004, "U": 90.0, "V": 128.333}
    end = {"Y": 100.0, "U": 90.0, "V": 1.0}

    d_in, d_out = grade.offsets(target_in, target_out, start, end)

    assert d_in == {"Y": 10.0, "U": 0.0, "V": 1.67}
    assert d_out == {"Y": 20.0, "U": 0.0, "V": 0.0}


# --- the grade filter ----------------------------------------------------------------------------


def test_a_constant_offset_is_one_lut_without_a_time_window() -> None:
    graded = grade.grade_filter({"Y": 5.0, "U": 0.0, "V": 0.0}, {"Y": 5.0, "U": 0.0, "V": 0.0}, 2.0)

    assert graded == "lutyuv=y='clip(val+(5.000)+0.5\\,16\\,235)'"
    assert "enable" not in graded


def test_the_lut_clips_each_plane_to_its_legal_range_and_skips_a_plane_at_zero() -> None:
    text = grade.lut({"Y": 0.0, "U": -2.5, "V": 3.0})

    assert (
        text == "lutyuv=u='clip(val+(-2.500)+0.5\\,16\\,240)':v='clip(val+(3.000)+0.5\\,16\\,240)'"
    )


def test_the_lut_of_a_ramp_step_carries_its_time_window() -> None:
    text = grade.lut({"Y": 1.0, "U": 0.0, "V": 0.0}, "lt(t\\,0.5)")

    assert text.endswith(":enable='lt(t\\,0.5)'")


def test_all_zero_offsets_need_no_filter() -> None:
    assert grade.grade_filter(ZERO, dict(ZERO), 2.0) is None


def test_a_ramp_of_ten_levels_over_two_seconds_has_23_steps() -> None:
    graded = grade.grade_filter(ZERO, {"Y": 10.0, "U": 0.0, "V": 0.0}, 2.0)

    assert graded is not None
    steps = graded.split(",lutyuv=")
    assert len(steps) == 23
    assert steps[0].startswith("lutyuv=")
    assert "enable='lt(t\\,0.0870)'" in steps[0]
    assert "y='clip(val+(0.217)+0.5\\,16\\,235)'" in steps[0]
    assert "enable='gte(t\\,1.9130)'" in steps[-1]
    assert "enable='gte(t\\,0.0870)*lt(t\\,0.1739)'" in steps[1]


def test_a_small_spread_still_ramps_in_two_steps() -> None:
    graded = grade.grade_filter(ZERO, {"Y": 0.1, "U": 0.0, "V": 0.0}, 1.0)

    assert graded is not None
    assert len(graded.split(",lutyuv=")) == 2


def test_a_huge_spread_is_capped_at_64_steps() -> None:
    graded = grade.grade_filter(ZERO, {"Y": 200.0, "U": 0.0, "V": 0.0}, 4.0)

    assert graded is not None
    assert len(graded.split(",lutyuv=")) == 64


def test_a_ramp_moves_every_plane_from_the_opening_to_the_ending_offset() -> None:
    graded = grade.grade_filter(
        {"Y": 0.0, "U": -4.0, "V": 0.0}, {"Y": 0.0, "U": 4.0, "V": 2.0}, 2.0
    )

    assert graded is not None
    first, last = graded.split(",lutyuv=")[0], graded.split(",lutyuv=")[-1]
    assert "u='clip(val+(-3.7" in first
    assert "u='clip(val+(3.7" in last
    assert "v='clip(val+(" in first


# --- the join graph ------------------------------------------------------------------------------


def test_a_silent_second_shot_gets_a_silence_input_and_one_audio_stream_joins() -> None:
    joined = grade.join_graph(
        [(Path("/job/shot_1"), clip(audio=True)), (Path("/job/shot_2"), clip(audio=False))],
        trim_s=4.0,
        pad_s=0.0,
    )

    assert joined.any_audio is True
    assert joined.inputs.count("-i") == 3
    assert "anullsrc=r=48000:cl=stereo" in joined.inputs
    graph = ";".join(joined.parts)
    assert "concat=n=2:v=1:a=1" in graph
    assert "[2:a]aresample=48000,aformat=channel_layouts=stereo[a1]" in graph
    assert "[0:a]aresample=48000,aformat=channel_layouts=stereo[a0]" in graph


def test_without_any_audio_no_silence_is_made_and_the_join_is_video_only() -> None:
    joined = grade.join_graph(
        [(Path("/job/shot_1"), clip(audio=False)), (Path("/job/shot_2"), clip(audio=False))],
        trim_s=4.0,
        pad_s=0.0,
    )

    assert joined.any_audio is False
    assert "anullsrc=r=48000:cl=stereo" not in joined.inputs
    assert "concat=n=2:v=1:a=0" in ";".join(joined.parts)


def test_every_file_input_is_opened_through_the_file_protocol_only() -> None:
    joined = grade.join_graph(
        [(Path("/job/shot_1"), clip()), (Path("/job/shot_2"), clip())], trim_s=4.0, pad_s=0.0
    )

    for index, arg in enumerate(joined.inputs):
        if arg == "-i":
            assert joined.inputs[index - 2 : index] == ["-protocol_whitelist", "file"]


def test_an_untagged_shot_is_read_as_bt709() -> None:
    joined = grade.join_graph(
        [(Path("/job/shot_1"), clip(tagged=False)), (Path("/job/shot_2"), clip(tagged=True))],
        trim_s=4.0,
        pad_s=0.0,
    )

    first, second = joined.parts[0], joined.parts[2]
    assert "in_color_matrix=bt709" in first
    assert "in_color_matrix" not in second
    assert "out_color_matrix=bt709:out_range=tv" in first


def test_a_later_shot_is_scaled_to_cover_shot_one_and_centre_cropped_at_its_rate() -> None:
    joined = grade.join_graph(
        [
            (Path("/job/shot_1"), clip(width=1280, height=720)),
            (Path("/job/shot_2"), clip(width=720, height=1280)),
        ],
        trim_s=4.0,
        pad_s=0.0,
    )

    graph = ";".join(joined.parts)
    assert graph.count("scale=1280:720:force_original_aspect_ratio=increase") == 2
    assert graph.count("crop=1280:720,setsar=1,fps=25/1,format=yuv420p") == 2


def test_a_pad_holds_the_last_frame_and_pads_the_audio_to_the_trim() -> None:
    padded = grade.join_graph([(Path("/job/shot_1"), clip())], trim_s=2.3, pad_s=0.3)
    plain = grade.join_graph([(Path("/job/shot_1"), clip())], trim_s=2.0, pad_s=0.0)

    assert "tpad=stop_mode=clone:stop_duration=0.300[v]" in ";".join(padded.parts)
    assert "apad=whole_dur=2.300[a]" in ";".join(padded.parts)
    assert "tpad" not in ";".join(plain.parts)
    assert "[vc]null[v]" in ";".join(plain.parts)
    assert "[ac]anull[a]" in ";".join(plain.parts)
