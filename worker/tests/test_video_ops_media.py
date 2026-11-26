"""The shared probe of the video ops: clip and image reports, windows, output description."""

from __future__ import annotations

import json
import subprocess
import sys
from collections.abc import Callable
from pathlib import Path
from typing import Any, cast

import pytest

from flowcortex_worker.errors import PermanentError
from flowcortex_worker.runtime import ProcessRunner
from flowcortex_worker.video_analysis.streams import VideoStream
from flowcortex_worker.video_ops import media, protocol

MP4 = "mov,mp4,m4a,3gp,3g2,mj2"


def report(*stream_list: dict[str, Any], format_name: Any = MP4, duration: Any = "3.0") -> Any:
    return {"format": {"format_name": format_name, "duration": duration}, "streams": stream_list}


def picture(**change: Any) -> dict[str, Any]:
    base: dict[str, Any] = {
        "index": 0,
        "codec_type": "video",
        "width": 1280,
        "height": 720,
        "avg_frame_rate": "25/1",
    }
    return {**base, **change}


def sound(index: int = 1) -> dict[str, Any]:
    return {"index": index, "codec_type": "audio"}


def still(format_name: str, width: int, height: int) -> Any:
    return {
        "format": {"format_name": format_name},
        "streams": [{"index": 0, "codec_type": "video", "width": width, "height": height}],
    }


def refused(call: Callable[[], object]) -> PermanentError:
    with pytest.raises(PermanentError) as caught:
        call()
    return caught.value


class FakeRunner:
    def __init__(self, code: int = 0, stdout: bytes = b"", stderr: bytes = b"") -> None:
        self.result = (code, stdout, stderr)
        self.argv: list[list[str]] = []

    def run(self, argv: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess[bytes]:
        self.argv.append(argv)
        return subprocess.CompletedProcess(argv, *self.result)

    @property
    def processes(self) -> ProcessRunner:
        return cast(ProcessRunner, self)


# --- read_clip ---------------------------------------------------------------------------


@pytest.mark.parametrize("name", ["hls", "concat", "mp3"])
def test_container_outside_the_allowlist_is_probe_failed_naming_it(name: str) -> None:
    error = refused(lambda: media.read_clip(report(picture(), format_name=name)))

    assert error.code == "probe_failed"
    assert name in error.message


def test_audio_only_input_is_probe_failed_without_a_moving_picture() -> None:
    error = refused(lambda: media.read_clip(report(sound(0))))

    assert error.code == "probe_failed"
    assert "no moving picture" in error.message


def test_a_clip_reads_its_length_picture_and_rate() -> None:
    clip = media.read_clip(report(picture(), sound()))

    assert clip == media.Clip(
        duration_ms=3000,
        video=VideoStream(0, 1280, 720, (25, 1)),
        has_audio=True,
        color_tagged=False,
    )


def test_a_picture_without_a_frame_rate_is_probe_failed() -> None:
    error = refused(lambda: media.read_clip(report(picture(avg_frame_rate="0/0"))))

    assert error.code == "probe_failed"
    assert "avg_frame_rate" in error.message


@pytest.mark.parametrize(
    ("streams", "has_audio"),
    [([picture(), sound()], True), ([picture()], False)],
)
def test_has_audio_follows_the_audio_stream(streams: list[dict[str, Any]], has_audio: bool) -> None:
    assert media.read_clip(report(*streams)).has_audio is has_audio


@pytest.mark.parametrize(
    ("tag", "tagged"),
    [
        ({}, False),
        ({"color_space": None}, False),
        ({"color_space": ""}, False),
        ({"color_space": "unknown"}, False),
        ({"color_space": "bt709"}, True),
        ({"color_space": "smpte170m"}, True),
    ],
)
def test_color_tagged_is_false_for_an_untagged_stream(tag: dict[str, Any], tagged: bool) -> None:
    assert media.read_clip(report(picture(**tag))).color_tagged is tagged


# --- read_image --------------------------------------------------------------------------


def test_gif_is_probe_failed_naming_the_format() -> None:
    error = refused(lambda: media.read_image(still("gif", 100, 100)))

    assert error.code == "probe_failed"
    assert "gif" in error.message


@pytest.mark.parametrize("name", sorted(media.IMAGE_FORMATS))
def test_still_image_formats_are_accepted(name: str) -> None:
    assert media.read_image(still(name, 640, 360)) == (name, 640, 360)


def test_image_just_over_the_pixel_cap_is_input_rejected_naming_both_numbers() -> None:
    error = refused(lambda: media.read_image(still("png_pipe", 5001, 5000)))

    assert error.code == "input_rejected"
    assert "5001" in error.message
    assert "5000" in error.message


def test_image_at_the_pixel_cap_is_accepted() -> None:
    assert media.read_image(still("png_pipe", 5000, 5000)) == ("png_pipe", 5000, 5000)


def test_image_report_without_a_picture_is_probe_failed() -> None:
    body = {"format": {"format_name": "png_pipe"}, "streams": []}

    assert refused(lambda: media.read_image(body)).code == "probe_failed"


# --- read_window -------------------------------------------------------------------------


def window_ms(params: dict[str, Any], duration_ms: int = 3000) -> tuple[int, int]:
    start, end = media.read_window(params, "srcInMs", "srcOutMs", duration_ms, "ms")
    return int(start), int(end)


@pytest.mark.parametrize("value", [-1, True, 1.5, "1000", None, float("inf")])
def test_start_that_is_not_a_whole_non_negative_number_names_the_field(value: Any) -> None:
    error = refused(lambda: window_ms({"srcInMs": value}))

    assert error.code == "contract_violation"
    assert "srcInMs" in error.message


@pytest.mark.parametrize("value", [-1, True, 2.5, "2000"])
def test_end_that_is_not_a_whole_non_negative_number_names_the_field(value: Any) -> None:
    error = refused(lambda: window_ms({"srcOutMs": value}))

    assert error.code == "contract_violation"
    assert "srcOutMs" in error.message


@pytest.mark.parametrize(
    "params", [{"srcInMs": 2000, "srcOutMs": 1000}, {"srcInMs": 5, "srcOutMs": 5}]
)
def test_end_not_above_start_names_the_end(params: dict[str, Any]) -> None:
    error = refused(lambda: window_ms(params))

    assert error.code == "contract_violation"
    assert "srcOutMs" in error.message


@pytest.mark.parametrize("start", [3000, 4000])
def test_start_at_or_past_the_end_names_the_start(start: int) -> None:
    error = refused(lambda: window_ms({"srcInMs": start}))

    assert error.code == "contract_violation"
    assert "srcInMs" in error.message


def test_end_past_the_clip_is_cut_to_it() -> None:
    assert window_ms({"srcInMs": 1000, "srcOutMs": 10_000}) == (1000, 3000)


def test_absent_keys_take_the_whole_clip() -> None:
    assert window_ms({}) == (0, 3000)
    assert window_ms({"srcInMs": 500}) == (500, 3000)
    assert window_ms({"srcOutMs": 2000}) == (0, 2000)


def test_a_window_inside_the_clip_is_returned_as_given() -> None:
    assert window_ms({"srcInMs": 1000, "srcOutMs": 2000}) == (1000, 2000)


def test_seconds_window_reads_floats_and_cuts_the_end() -> None:
    assert media.read_window({"startSec": 1, "endSec": 10}, "startSec", "endSec", 3000, "s") == (
        1.0,
        3.0,
    )
    assert media.read_window({"startSec": 0.25}, "startSec", "endSec", 3000, "s") == (0.25, 3.0)
    assert media.read_window({}, "startSec", "endSec", 3000, "s") == (0.0, 3.0)


@pytest.mark.parametrize(
    ("params", "field"),
    [
        ({"startSec": -1}, "startSec"),
        ({"startSec": True}, "startSec"),
        ({"startSec": 3}, "startSec"),
        ({"startSec": 2, "endSec": 2}, "endSec"),
        ({"endSec": "9"}, "endSec"),
    ],
)
def test_seconds_window_refusals_name_the_field(params: dict[str, Any], field: str) -> None:
    error = refused(lambda: media.read_window(params, "startSec", "endSec", 3000, "s"))

    assert error.code == "contract_violation"
    assert field in error.message


# --- probes and output description -------------------------------------------------------


def test_probe_clip_whitelists_files_and_reads_the_report(tmp_path: Path) -> None:
    body = json.dumps(report(picture(color_space="bt709"), sound())).encode()
    runner = FakeRunner(0, body)

    clip = media.probe_clip(tmp_path / "input", runner.processes)

    assert clip.color_tagged
    [argv] = runner.argv
    assert argv[:5] == ["ffprobe", "-v", "error", "-protocol_whitelist", "file"]
    assert "color_space" in argv[argv.index("-show_entries") + 1]
    assert argv[-1] == str(tmp_path / "input")


def test_probe_failure_names_ffprobe_and_the_last_error_line(tmp_path: Path) -> None:
    stderr = b"first\n\nInvalid data found when processing input " + b"x" * 400 + b"\n"

    error = refused(
        lambda: media.probe_clip(tmp_path / "input", FakeRunner(1, b"", stderr).processes)
    )

    assert error.code == "probe_failed"
    assert error.message.startswith("ffprobe:")
    assert "Invalid data found" in error.message
    assert "first" not in error.message
    assert len(error.message) < 400


@pytest.mark.parametrize("body", [b"not json", b"[]"])
def test_unreadable_probe_report_is_probe_failed(tmp_path: Path, body: bytes) -> None:
    error = refused(lambda: media.probe_clip(tmp_path / "input", FakeRunner(0, body).processes))

    assert error.code == "probe_failed"
    assert "ffprobe" in error.message


def test_probe_image_reads_format_and_size(tmp_path: Path) -> None:
    runner = FakeRunner(0, json.dumps(still("jpeg_pipe", 800, 600)).encode())

    assert media.probe_image(tmp_path / "input", runner.processes) == ("jpeg_pipe", 800, 600)
    assert runner.argv[0][:5] == ["ffprobe", "-v", "error", "-protocol_whitelist", "file"]


def test_output_is_described_from_the_file(tmp_path: Path) -> None:
    output = tmp_path / "out.mp4"
    output.write_bytes(b"not empty")
    body = report(picture(width=640, height=360), sound(), duration="2.0005")
    runner = FakeRunner(0, json.dumps(body).encode())

    described = media.describe_output(output, runner.processes)

    assert described == {"width": 640, "height": 360, "durationMs": 2001, "hasAudio": True}


def test_output_described_with_the_display_size_of_a_rotated_picture(tmp_path: Path) -> None:
    output = tmp_path / "out.mp4"
    output.write_bytes(b"x")
    rotated = picture(width=1280, height=720, side_data_list=[{"rotation": -90}])
    runner = FakeRunner(0, json.dumps(report(rotated)).encode())

    described = media.describe_output(output, runner.processes)

    assert (described["width"], described["height"], described["hasAudio"]) == (720, 1280, False)


@pytest.mark.parametrize("content", [None, b""])
def test_missing_or_empty_output_is_probe_failed_without_probing(
    tmp_path: Path, content: bytes | None
) -> None:
    output = tmp_path / "out.mp4"
    if content is not None:
        output.write_bytes(content)
    runner = FakeRunner()

    error = refused(lambda: media.describe_output(output, runner.processes))

    assert error.code == "probe_failed"
    assert runner.argv == []


# --- run_child ---------------------------------------------------------------------------


class ChildRunner:
    """Plays the vision child: `result` is written to its work directory when given."""

    def __init__(self, code: int = 0, stderr: bytes = b"", result: str | None = "{}") -> None:
        self.code, self.stderr, self.result = code, stderr, result
        self.calls: list[tuple[list[str], Path | None]] = []
        self.request_seen: str | None = None

    def run(self, argv: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess[bytes]:
        self.calls.append((argv, cwd))
        assert cwd is not None
        self.request_seen = (cwd / protocol.REQUEST_FILE).read_text(encoding="utf-8")
        if self.result is not None:
            (cwd / protocol.RESULT_FILE).write_text(self.result, encoding="utf-8")
        return subprocess.CompletedProcess(argv, self.code, b"", self.stderr)


def call_child(tmp_path: Path, runner: ChildRunner, command: str = "faces") -> dict[str, Any]:
    return protocol.run_child(command, tmp_path, {"scale": 2.0}, cast(ProcessRunner, runner))


def test_run_child_runs_the_command_in_the_work_directory_with_no_path_argument(
    tmp_path: Path,
) -> None:
    runner = ChildRunner(result='{"faces": []}')

    assert call_child(tmp_path, runner) == {"faces": []}

    [(argv, cwd)] = runner.calls
    assert argv == [sys.executable, "-m", "flowcortex_worker.video_ops.vision", "faces"]
    assert cwd == tmp_path
    assert json.loads(runner.request_seen or "") == {"scale": 2.0}


def test_run_child_refuses_a_command_outside_the_fixed_set(tmp_path: Path) -> None:
    runner = ChildRunner()

    with pytest.raises(ValueError, match="unknown vision command"):
        call_child(tmp_path, runner, "../x")

    assert runner.calls == []


@pytest.mark.parametrize(
    ("code", "named"),
    [(protocol.FACE_MODEL_MISSING, "face detection model"), (protocol.RUNTIME_MISSING, "OpenCV")],
)
def test_run_child_names_what_the_image_lacks(tmp_path: Path, code: int, named: str) -> None:
    error = refused(lambda: call_child(tmp_path, ChildRunner(code=code, result=None)))

    assert error.code == "contract_violation"
    assert named in error.message


def test_run_child_reports_the_last_stderr_line_of_any_other_failure(tmp_path: Path) -> None:
    runner = ChildRunner(code=1, stderr=b"warning\nunreadable input: window.mkv is missing\n")

    error = refused(lambda: call_child(tmp_path, runner, "motion"))

    assert error.code == "contract_violation"
    assert "exit 1" in error.message
    assert "window.mkv is missing" in error.message
    assert "warning" not in error.message


@pytest.mark.parametrize("body", [None, "not json", "[]"])
def test_run_child_without_a_readable_result_is_a_violation(
    tmp_path: Path, body: str | None
) -> None:
    error = refused(lambda: call_child(tmp_path, ChildRunner(result=body)))

    assert error.code == "contract_violation"
    assert "no readable result" in error.message


def test_run_child_does_not_read_a_result_left_by_an_earlier_run(tmp_path: Path) -> None:
    (tmp_path / protocol.RESULT_FILE).write_text('{"faces": [[1]]}', encoding="utf-8")

    error = refused(lambda: call_child(tmp_path, ChildRunner(result=None)))

    assert "no readable result" in error.message


def test_output_without_a_moving_picture_is_probe_failed(tmp_path: Path) -> None:
    output = tmp_path / "out.mp4"
    output.write_bytes(b"x")
    runner = FakeRunner(0, json.dumps(report(sound(0))).encode())

    error = refused(lambda: media.describe_output(output, runner.processes))

    assert error.code == "probe_failed"
    assert "no moving picture" in error.message
