"""Pure rules of the video analysis handlers: streams, frame metadata, sheet, scenes."""

from __future__ import annotations

import subprocess
from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest
from PIL import Image

from flowcortex_worker.errors import PermanentError
from flowcortex_worker.video_analysis import frame_metadata, scenes, sheet, streams

MP4 = "mov,mp4,m4a,3gp,3g2,mj2"


def report(
    *stream_list: dict[str, Any], format_name: Any = MP4, duration: Any = "3.0"
) -> dict[str, Any]:
    fmt: dict[str, Any] = {"duration": duration}
    if format_name is not None:
        fmt["format_name"] = format_name
    return {"format": fmt, "streams": list(stream_list)}


def video(**change: Any) -> dict[str, Any]:
    base: dict[str, Any] = {
        "index": 0,
        "codec_type": "video",
        "width": 1920,
        "height": 1080,
        "avg_frame_rate": "25/1",
    }
    return {**base, **change}


def refused(call: Callable[[], object]) -> PermanentError:
    with pytest.raises(PermanentError) as caught:
        call()
    return caught.value


# --- streams -----------------------------------------------------------------------------


@pytest.mark.parametrize("name", ["hls", "concat", "mp3", "wav"])
def test_container_outside_allowlist_is_probe_failed(name: str) -> None:
    error = refused(lambda: streams.read_streams(report(video(), format_name=name)))

    assert error.code == "probe_failed"
    assert name in error.message


def test_missing_container_format_is_probe_failed() -> None:
    assert (
        refused(lambda: streams.read_streams(report(video(), format_name=None))).code
        == "probe_failed"
    )


@pytest.mark.parametrize("name", [MP4, "matroska,webm"])
def test_video_containers_are_accepted(name: str) -> None:
    duration_ms, stream = streams.read_streams(report(video(), format_name=name))

    assert duration_ms == 3000
    assert stream is not None


def test_moving_picture_wins_over_cover_art() -> None:
    cover = video(index=0, disposition={"attached_pic": 1})
    moving = video(index=1, width=640, height=360, avg_frame_rate="30000/1001")

    _, stream = streams.read_streams(report(cover, moving), require_rate=True)

    assert stream is not None
    assert (stream.index, stream.width, stream.height) == (1, 640, 360)
    assert stream.fps == pytest.approx(29.97, abs=0.01)
    assert stream.one_frame_expr == "1001/30000"
    assert stream.one_frame_ms_floor == 33


def test_cover_art_alone_is_no_picture() -> None:
    cover = video(disposition={"attached_pic": 1})

    assert streams.read_streams(report(cover)) == (3000, None)


@pytest.mark.parametrize(
    ("rotation", "swapped"), [(-90, True), (270, True), (90, True), (180, False), (0, False)]
)
def test_quarter_turn_swaps_display_size(rotation: int, swapped: bool) -> None:
    rotated = video(width=1920, height=1080, side_data_list=[{"rotation": rotation}])

    _, stream = streams.read_streams(report(rotated))

    assert stream is not None
    assert (stream.width, stream.height) == ((1080, 1920) if swapped else (1920, 1080))


def test_zero_frame_rate_is_probe_failed() -> None:
    error = refused(
        lambda: streams.read_streams(report(video(avg_frame_rate="0/0")), require_rate=True)
    )

    assert error.code == "probe_failed"
    assert "avg_frame_rate" in error.message


def test_zero_frame_rate_is_accepted_when_the_rate_is_not_needed() -> None:
    duration_ms, stream = streams.read_streams(report(video(avg_frame_rate="0/0")))

    assert duration_ms == 3000
    assert stream is not None
    assert (stream.width, stream.height) == (1920, 1080)
    with pytest.raises(ValueError):
        _ = stream.fps


@pytest.mark.parametrize("duration", [None, "N/A", "0", "0.000000", "-1", "abc"])
def test_unusable_duration_is_probe_failed(duration: Any) -> None:
    assert (
        refused(lambda: streams.read_streams(report(video(), duration=duration))).code
        == "probe_failed"
    )


def test_audio_only_has_no_stream() -> None:
    audio = {"index": 0, "codec_type": "audio"}

    assert streams.read_streams(report(audio, duration="2.5")) == (2500, None)


@pytest.mark.parametrize(("value", "number"), [(0, 0.0), (3, 3.0), (0.25, 0.25), (-1.5, -1.5)])
def test_finite_param_reads_a_number(value: Any, number: float) -> None:
    assert streams.finite_param({"k": value}, "k") == number


@pytest.mark.parametrize(
    "value", [True, "0.5", None, [1], float("nan"), float("inf"), -float("inf"), 10**400]
)
def test_finite_param_refuses_anything_else_naming_the_key(value: Any) -> None:
    error = refused(lambda: streams.finite_param({"k": value}, "k"))

    assert error.code == "contract_violation"
    assert "'k'" in error.message


def test_finite_param_refuses_a_missing_key() -> None:
    assert refused(lambda: streams.finite_param({}, "k")).code == "contract_violation"


def test_ms_rounds_half_up() -> None:
    assert streams.ms(2.0005) == 2001
    assert streams.ms(2.0) == 2000


class FakeRunner:
    def __init__(self, result: subprocess.CompletedProcess[bytes]) -> None:
        self.result = result
        self.argv: list[list[str]] = []

    def run(self, argv: list[str]) -> subprocess.CompletedProcess[bytes]:
        self.argv.append(argv)
        return self.result


def finished(
    code: int, stdout: bytes = b"", stderr: bytes = b""
) -> subprocess.CompletedProcess[bytes]:
    return subprocess.CompletedProcess([], code, stdout, stderr)


def test_probe_video_reads_the_report_and_whitelists_files() -> None:
    body = b'{"format":{"format_name":"matroska,webm","duration":"1.5"},"streams":[]}'
    runner = FakeRunner(finished(0, body))

    assert streams.probe_video(Path("/tmp/x/input"), runner) == (1500, None)  # type: ignore[arg-type]
    assert runner.argv[0][:5] == ["ffprobe", "-v", "error", "-protocol_whitelist", "file"]
    assert runner.argv[0][-1] == "/tmp/x/input"


def test_probe_video_failure_names_ffprobe_and_last_stderr_line() -> None:
    stderr = b"first\n\nInvalid data found when processing input " + b"x" * 400 + b"\n"
    runner = FakeRunner(finished(1, b"", stderr))

    error = refused(lambda: streams.probe_video(Path("/tmp/x/input"), runner))  # type: ignore[arg-type]

    assert error.code == "probe_failed"
    assert error.message.startswith("ffprobe")
    assert "first" not in error.message
    assert len(error.message) < 400


def test_probe_video_unparsable_json_is_probe_failed() -> None:
    runner = FakeRunner(finished(0, b"not json"))

    assert refused(lambda: streams.probe_video(Path("/x"), runner)).code == "probe_failed"  # type: ignore[arg-type]


# --- frame metadata ----------------------------------------------------------------------

BLACK = (frame_metadata.BLACK_START, frame_metadata.BLACK_END)
FREEZE = (frame_metadata.FREEZE_START, frame_metadata.FREEZE_END)


def test_frame_events_count_frames_and_keep_read_keys() -> None:
    text = (
        "frame:0    pts:0       pts_time:0\n"
        "lavfi.scd.score=1.000\n"
        "frame:1    pts:1024    pts_time:0.04\n"
        "lavfi.black_start=2\n"
        "lavfi.other=7\n"
    )

    assert frame_metadata.frame_events(text) == (2, [("lavfi.black_start", 2.0)])


def test_black_run_of_one_frame_at_25_fps_is_kept_and_a_shorter_one_dropped() -> None:
    floor = 40
    kept = [(frame_metadata.BLACK_START, 1.0), (frame_metadata.BLACK_END, 1.040)]
    dropped = [(frame_metadata.BLACK_START, 1.0), (frame_metadata.BLACK_END, 1.039)]

    assert frame_metadata.black_runs(kept, 3000, floor) == [{"fromMs": 1000, "toMs": 1040}]
    assert frame_metadata.black_runs(dropped, 3000, floor) == []


def test_black_tail_closes_at_duration() -> None:
    events = [(frame_metadata.BLACK_START, 2.0)]

    assert frame_metadata.runs(events, *BLACK, 3000) == [{"fromMs": 2000, "toMs": 3000}]


def test_freeze_start_only_covers_the_whole_clip() -> None:
    events = [(frame_metadata.FREEZE_START, 0.0)]

    assert frame_metadata.runs(events, *FREEZE, 3000) == [{"fromMs": 0, "toMs": 3000}]


def test_closed_run_is_clamped_to_the_clip() -> None:
    events = [(frame_metadata.BLACK_START, -0.2), (frame_metadata.BLACK_END, 3.4)]

    assert frame_metadata.runs(events, *BLACK, 3000) == [{"fromMs": 0, "toMs": 3000}]


def test_start_twice_is_probe_failed() -> None:
    events = [(frame_metadata.BLACK_START, 1.0), (frame_metadata.BLACK_START, 2.0)]

    error = refused(lambda: frame_metadata.runs(events, *BLACK, 3000))

    assert error.code == "probe_failed"
    assert frame_metadata.BLACK_START in error.message


def test_end_without_start_is_probe_failed() -> None:
    error = refused(lambda: frame_metadata.runs([(frame_metadata.BLACK_END, 1.0)], *BLACK, 3000))

    assert error.code == "probe_failed"
    assert frame_metadata.BLACK_END in error.message


def test_mark_without_timestamp_is_probe_failed() -> None:
    error = refused(lambda: frame_metadata.frame_events("lavfi.black_start=N/A\n"))

    assert error.code == "probe_failed"
    assert frame_metadata.BLACK_START in error.message


def test_cuts_are_ascending_unique_and_strictly_inside() -> None:
    events = [(frame_metadata.SCENE_CUT, s) for s in (0.0, 2.0, 2.0, 3.0)]

    assert frame_metadata.cuts(events, 3000) == [2000]


def test_cuts_ignore_other_marks_and_stay_ascending() -> None:
    events = [
        (frame_metadata.SCENE_CUT, 1.0),
        (frame_metadata.BLACK_START, 1.5),
        (frame_metadata.SCENE_CUT, 0.5),
        (frame_metadata.SCENE_CUT, 2.0),
    ]

    assert frame_metadata.cuts(events, 3000) == [1000, 2000]


SCDET = "frame:0    pts:25600   pts_time:2\nlavfi.scd.score=85.547\nlavfi.scd.time=2\n"


def test_scdet_scores_pair_frame_time_with_score() -> None:
    assert frame_metadata.scdet_scores(SCDET) == [(2.0, 85.547)]
    assert frame_metadata.scdet_scores("") == []


def test_scdet_scores_with_bad_number_is_probe_failed() -> None:
    text = "frame:0    pts:0   pts_time:1\nlavfi.scd.score=abc\n"

    assert refused(lambda: frame_metadata.scdet_scores(text)).code == "probe_failed"


def test_selected_times_read_each_frame_header() -> None:
    text = (
        "frame:0    pts:25600   pts_time:2\n"
        "lavfi.scene_score=0.5\n"
        "frame:1    pts:76800   pts_time:3.5\n"
        "lavfi.scene_score=0.7\n"
    )

    assert frame_metadata.selected_times(text) == [2.0, 3.5]
    assert frame_metadata.selected_times("") == []


# --- sheet -------------------------------------------------------------------------------


def test_clock_is_minutes_seconds_tenths() -> None:
    assert sheet.clock(1500) == "0:01.5"
    assert sheet.clock(61_950) == "1:02.0"
    assert sheet.clock(0) == "0:00.0"


@pytest.mark.parametrize(
    ("size", "tile"),
    [
        ((496, 864), (206, 360)),
        ((1920, 1080), (360, 202)),
        ((1, 2000), (1, 360)),
        ((2000, 1), (360, 1)),
    ],
)
def test_tile_long_side_is_360(size: tuple[int, int], tile: tuple[int, int]) -> None:
    assert sheet.tile_size(*size) == tile


def test_grid_follows_the_tile_aspect() -> None:
    assert sheet.grid(30, 206, 360) == (8, 4)
    assert sheet.grid(1, 360, 202) == (1, 1)


def test_composed_sheet_labels_tiles_and_leaves_empty_cells_black() -> None:
    gray = Image.new("RGB", (206, 360), (128, 128, 128))
    labels = ["0:00.0", "0:00.5", "0:01.0", "0:01.5", "0:02.0"]
    composed = sheet.compose_sheet([gray] * 5, labels, 206, 360)

    cols, rows = sheet.grid(5, 206, 360)
    assert cols * rows > 5
    assert composed.size == (cols * 206, rows * 360)
    assert composed.mode == "RGB"
    label = composed.crop((206, 0, 412, 28))
    assert label.getpixel((0, 0)) == (0, 0, 0)
    assert any(label.getpixel((x, y)) == (255, 255, 255) for x in range(206) for y in range(28))
    assert composed.getpixel((206 + 100, 200)) == (128, 128, 128)
    assert composed.getpixel((composed.width - 5, composed.height - 5)) == (0, 0, 0)


def test_label_box_stays_inside_a_narrow_tile() -> None:
    tile = Image.new("RGB", (4, 360), (200, 0, 0))

    composed = sheet.compose_sheet([tile, tile], ["0:00.0", "0:00.5"], 4, 360)

    cols, _ = sheet.grid(2, 4, 360)
    assert composed.size[0] == cols * 4
    assert composed.getpixel((4, 100)) == (200, 0, 0)


# --- scenes ------------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("size", "label"),
    [((900, 1000), "1:1"), ((1080, 1920), "9:16"), ((0, 0), "9:16"), ((1920, 1080), "16:9")],
)
def test_aspect_is_the_nearest_standard(size: tuple[int, int], label: str) -> None:
    assert scenes.aspect(*size) == label


def test_standard_sizes() -> None:
    assert scenes.STANDARD_SIZES == {"16:9": (854, 480), "9:16": (480, 854), "1:1": (640, 640)}


def test_flicker_is_merged_and_edge_cuts_dropped() -> None:
    assert scenes.boundaries([1.0, 1.1], 96) == [0, 24, 96]
    assert scenes.boundaries([(96 - 4) / 24], 96) == [0, 96]
    assert scenes.boundaries([], 96) == [0, 96]


def test_no_bounds_given() -> None:
    assert scenes.given_bounds(None) is None
    assert scenes.given_bounds([0, 5, 96]) == [0, 5, 96]


@pytest.mark.parametrize(
    "raw", [[0], [1, 5], [0, 5, 5], [0, True, 9], "0,5", [0, 4, 96], [0, 5.0, 96], 7]
)
def test_bad_bounds_are_contract_violations(raw: Any) -> None:
    error = refused(lambda: scenes.given_bounds(raw))

    assert error.code == "contract_violation"
    assert "bounds" in error.message


def test_middle_frame() -> None:
    assert scenes.middle_frame(48, 96) == 72
    assert scenes.middle_frame(0, 5) == 2
