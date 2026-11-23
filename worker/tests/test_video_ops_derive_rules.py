"""Rules of the stabilize and derive-reframe ops, called with numbers: no ffmpeg, no OpenCV."""

from __future__ import annotations

from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest

from flowcortex_worker.errors import PermanentError
from flowcortex_worker.video_ops import reframe_path, stabilize

WIN = 404
SRC_W = 1280
SHEET = {"cols": 2, "rows": 1, "stepMs": 500, "frames": 2}


def refused(call: Any) -> PermanentError:
    with pytest.raises(PermanentError) as caught:
        call()
    return caught.value


def face(left: float, right: float) -> tuple[float, float, float, float]:
    return (left, 100.0, right, 300.0)


def free(amount: int) -> Any:
    return lambda _path: SimpleNamespace(free=amount)


# --- stabilize parameters, log and measure -------------------------------------------------------


def test_empty_params_take_the_defaults() -> None:
    assert stabilize.read_params({}) == (10, 1)


def test_the_whole_domain_is_accepted() -> None:
    assert stabilize.read_params({"smoothing": 0, "optzoom": 0}) == (0, 0)
    assert stabilize.read_params({"smoothing": 25, "optzoom": 2}) == (25, 2)
    assert stabilize.read_params({"smoothing": 4.0}) == (4, 1)


@pytest.mark.parametrize(
    ("params", "field"),
    [
        ({"smoothing": True}, "smoothing"),
        ({"smoothing": -1}, "smoothing"),
        ({"smoothing": 1.5}, "smoothing"),
        ({"smoothing": "5"}, "smoothing"),
        ({"smoothing": None}, "smoothing"),
        ({"optzoom": 3}, "optzoom"),
        ({"optzoom": True}, "optzoom"),
        ({"optzoom": 0.5}, "optzoom"),
        ({"optzoom": "1"}, "optzoom"),
    ],
)
def test_a_value_outside_the_domain_is_a_contract_violation_naming_the_field(
    params: dict[str, Any], field: str
) -> None:
    error = refused(lambda: stabilize.read_params(params))

    assert error.code == "contract_violation"
    assert field in error.message


def test_the_zoom_is_the_last_final_zoom_line_rounded_half_up() -> None:
    assert stabilize.parse_zoom_pct("Final zoom: 3.4\nFinal zoom: 4.5") == 5


def test_a_log_without_a_zoom_line_is_zero() -> None:
    assert stabilize.parse_zoom_pct("") == 0
    assert stabilize.parse_zoom_pct("[Parsed_vidstabtransform_0] Final zoom missing") == 0


def test_a_negative_zoom_is_zero() -> None:
    assert stabilize.parse_zoom_pct("Final zoom: -2.0") == 0


def test_a_clearly_lower_motion_is_steadier() -> None:
    assert stabilize.not_steadier(3.1, 1.2) is False


def test_a_difference_within_measurement_noise_is_not_steadier() -> None:
    assert stabilize.not_steadier(0.50, 0.49) is True


def test_a_higher_output_motion_is_not_steadier() -> None:
    assert stabilize.not_steadier(1.0, 1.4) is True


# --- stabilize disk guard ------------------------------------------------------------------------


def test_the_intra_copy_of_a_long_1080p_window_needs_the_documented_bytes() -> None:
    assert stabilize.intra_bytes(1920, 1080, 30, 60) == 2_985_984_000


def test_a_long_window_on_a_small_shared_disk_is_duration_exceeded(tmp_path: Path) -> None:
    error = refused(
        lambda: stabilize.check_disk_room(1920, 1080, 30, 60, tmp_path, free(4_000_000_000))
    )

    assert error.code == "duration_exceeded"
    assert "2985984000" in error.message
    assert "4000000000" in error.message


def test_a_window_that_fits_twice_is_accepted(tmp_path: Path) -> None:
    stabilize.check_disk_room(1920, 1080, 30, 60, tmp_path, free(7_000_000_000))


def test_the_disk_is_read_from_the_directory_given(tmp_path: Path) -> None:
    asked: list[Path] = []

    def usage(path: Path) -> Any:
        asked.append(path)
        return SimpleNamespace(free=10**12)

    stabilize.check_disk_room(640, 360, 25, 2, tmp_path, usage)

    assert asked == [tmp_path]


# --- derive reframe: window, sheet answers -------------------------------------------------------


def test_the_window_is_nine_sixteenths_of_the_height_rounded_to_even() -> None:
    assert reframe_path.window_width(720) == 404
    assert reframe_path.window_width(1080) == 606
    assert reframe_path.window_width(721) == 404


def test_a_box_in_the_second_tile_becomes_a_tile_box_at_that_tile_time() -> None:
    reply = [{"tile": 2, "bbox": [600, 100, 800, 900]}]

    assert reframe_path.sheet_boxes_to_frames(reply, SHEET) == {500: [200, 100, 600, 900]}


def test_tile_times_count_from_the_window_start() -> None:
    reply = [{"tile": 1, "bbox": [100, 100, 300, 900]}]

    boxes = reframe_path.sheet_boxes_to_frames(reply, SHEET)

    assert boxes == {0: [200, 100, 600, 900]}
    products = reframe_path.product_per_frame(boxes, [], 3, 25.0, SRC_W)
    assert products[0] is not None


def test_a_box_centred_in_the_wrong_tile_gives_no_box() -> None:
    reply = [{"tile": 1, "bbox": [600, 100, 800, 900]}]

    assert reframe_path.sheet_boxes_to_frames(reply, SHEET) == {}


def test_a_reply_that_is_not_json_gives_no_box() -> None:
    assert reframe_path.sheet_boxes_to_frames("no product", SHEET) == {}
    assert reframe_path.sheet_null_frames("no product", SHEET) == set()


def test_a_tile_beyond_the_layout_gives_no_box() -> None:
    reply = [{"tile": 3, "bbox": [100, 100, 300, 900]}, {"tile": 0, "bbox": [0, 0, 400, 900]}]

    assert reframe_path.sheet_boxes_to_frames(reply, SHEET) == {}


def test_a_degenerate_box_gives_no_box() -> None:
    reply = [{"tile": 1, "bbox": [200, 100, 200, 900]}, {"tile": 2, "bbox": [900, 500, 700, 500]}]

    assert reframe_path.sheet_boxes_to_frames(reply, SHEET) == {}


def test_a_reply_may_be_a_json_string_or_an_object_with_tiles() -> None:
    boxes = {0: [200, 100, 600, 900]}

    assert (
        reframe_path.sheet_boxes_to_frames('[{"tile": 1, "bbox": [100, 100, 300, 900]}]', SHEET)
        == boxes
    )
    assert (
        reframe_path.sheet_boxes_to_frames(
            {"tiles": [{"tile": 1, "bbox": [100, 100, 300, 900]}]}, SHEET
        )
        == boxes
    )


def test_a_layout_that_cannot_be_read_gives_no_box() -> None:
    reply = [{"tile": 1, "bbox": [100, 100, 300, 900]}]

    assert reframe_path.sheet_boxes_to_frames(reply, {}) == {}
    assert reframe_path.sheet_boxes_to_frames(reply, {**SHEET, "cols": 0}) == {}
    assert reframe_path.sheet_boxes_to_frames(reply, {**SHEET, "cols": "wide"}) == {}


def test_a_null_bbox_marks_the_product_absent_at_that_tile_time() -> None:
    reply = [{"tile": 1, "bbox": [100, 100, 300, 900]}, {"tile": 2, "bbox": None}]

    assert reframe_path.sheet_null_frames(reply, SHEET) == {500}


def test_the_product_of_a_frame_is_the_nearest_answer_inside_its_own_shot() -> None:
    answers: dict[int, list[int] | None] = {0: [100, 0, 300, 1000], 1000: None}

    products = reframe_path.product_per_frame(answers, [25], 50, 25.0, SRC_W)

    first = products[0]
    assert first is not None
    assert first[0] == pytest.approx(128.0)
    assert first[2] == pytest.approx(384.0)
    assert products[24] == first
    assert products[25] is None  # the second shot's own answer is null
    assert products[49] is None


def test_no_answers_means_no_product_on_any_frame() -> None:
    assert reframe_path.product_per_frame({}, [], 4, 25.0, SRC_W) == [None] * 4


# --- derive reframe: crop path -------------------------------------------------------------------


def test_a_still_subject_gets_one_fixed_crop_per_shot() -> None:
    faces = [face(300, 360)] * 25 + [face(900, 960)] * 25

    path = reframe_path.crop_path(50, faces, [None] * 50, [25], WIN, SRC_W)

    assert len(path) == 50
    assert set(path[:25]) == {128}
    assert set(path[25:]) == {728}


def test_a_subject_within_the_deadzone_of_its_median_is_fixed() -> None:
    faces = [face(300 + step, 360 + step) for step in range(0, 50, 2)]

    path = reframe_path.crop_path(25, faces, [None] * 25, [], WIN, SRC_W)

    assert len(set(path)) == 1


def test_a_cut_restarts_tracking_on_the_new_face_without_smoothing() -> None:
    first = [face(200 - 30, 200 + 30)] * 30
    second = [face(x - 30, x + 30) for x in range(1000, 700, -10)]

    path = reframe_path.crop_path(60, first + second, [None] * 60, [30], WIN, SRC_W)

    assert path[29] == 0
    assert path[30] == 798
    assert path[31] > 700  # tracking continues from the new position, not from the old one


def test_without_the_cut_the_old_position_would_have_pulled_the_crop() -> None:
    first = [face(170, 230)] * 30
    second = [face(x - 30, x + 30) for x in range(1000, 700, -10)]

    path = reframe_path.crop_path(60, first + second, [None] * 60, [], WIN, SRC_W)

    assert path[30] != 798


def test_a_face_missed_inside_a_shot_keeps_the_nearest_earlier_face() -> None:
    faces = [None, face(300, 360), None, None]

    path = reframe_path.crop_path(4, faces, [None] * 4, [], WIN, SRC_W)

    assert set(path) == {128}


def test_a_shot_with_no_face_is_centred() -> None:
    path = reframe_path.crop_path(5, [None] * 5, [None] * 5, [], WIN, SRC_W)

    assert set(path) == {(SRC_W - WIN) // 2}


def test_a_face_beyond_the_frame_edge_keeps_the_crop_inside_the_frame() -> None:
    faces = [face(1250, 1280)] * 5

    path = reframe_path.crop_path(5, faces, [None] * 5, [], WIN, SRC_W)

    assert set(path) == {SRC_W - WIN}


def test_the_product_stays_inside_every_frame_when_face_and_product_fit() -> None:
    faces = [face(100 + step * 4, 160 + step * 4) for step in range(30)]
    products = [(300.0, 0.0, 360.0, 0.0)] * 30

    path = reframe_path.crop_path(30, faces, products, [], WIN, SRC_W)

    for left in path:
        assert left <= 100
        assert left + WIN >= 360


def test_a_product_wider_than_the_window_is_centred_on() -> None:
    path = reframe_path.crop_path(3, [None] * 3, [(100.0, 0.0, 700.0, 0.0)] * 3, [], WIN, SRC_W)

    assert set(path) == {198}


def test_a_cut_outside_the_clip_is_ignored() -> None:
    faces = [face(300, 360)] * 10

    path = reframe_path.crop_path(10, faces, [None] * 10, [0, 10, 99], WIN, SRC_W)

    assert set(path) == {128}


# --- derive reframe: sendcmd file ----------------------------------------------------------------


def test_commands_are_written_only_where_the_edge_changes() -> None:
    path = [0] * 25 + [200] * 25

    text = reframe_path.crop_commands(path, (25, 1))

    assert text.splitlines() == ["0.000000 crop x 0;", "1.000000 crop x 200;"]


def test_command_times_are_whole_microseconds_at_a_fractional_rate() -> None:
    path = [0, 0, 7]

    text = reframe_path.crop_commands(path, (30000, 1001))

    assert text.splitlines() == ["0.000000 crop x 0;", "0.066733 crop x 7;"]


def test_an_empty_path_has_no_commands() -> None:
    assert reframe_path.crop_commands([], (25, 1)) == ""
