"""The still capability: parameter rules, the zoom filter and the clip made from one image."""

from __future__ import annotations

import json
import shutil
import subprocess
from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest
from conftest import job_body

from flowcortex_worker.contracts.media_job import MediaJob
from flowcortex_worker.errors import PermanentError
from flowcortex_worker.handlers import still as still_handler
from flowcortex_worker.runtime import ProcessKilled, ProcessRunner
from flowcortex_worker.video_ops import still

needs_ffmpeg = pytest.mark.skipif(
    shutil.which("ffmpeg") is None, reason="ffmpeg is installed in the worker image"
)


class RecordingRunner(ProcessRunner):
    """Runs everything for real, except an ffmpeg call `fail_when` picks, which exits 1."""

    def __init__(self, fail_when: Callable[[list[str]], bool] = lambda argv: False) -> None:
        super().__init__()
        self.argvs: list[list[str]] = []
        self._fail_when = fail_when

    def run(self, argv: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess[bytes]:
        self.argvs.append(argv)
        if argv[0] == "ffmpeg" and self._fail_when(argv):
            return subprocess.CompletedProcess(argv, 1, b"", b"Error: simulated encoder failure\n")
        return super().run(argv, cwd)

    @property
    def encodes(self) -> list[list[str]]:
        return [argv for argv in self.argvs if argv[0] == "ffmpeg"]


class ReportRunner(ProcessRunner):
    """Answers ffprobe with a fixed report and records everything it was asked to run."""

    def __init__(self, report: dict[str, Any]) -> None:
        super().__init__()
        self.argvs: list[list[str]] = []
        self._report = report

    def run(self, argv: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess[bytes]:
        self.argvs.append(argv)
        return subprocess.CompletedProcess(argv, 0, json.dumps(self._report).encode(), b"")


def job(**params: Any) -> MediaJob:
    return MediaJob.model_validate(job_body(params=params))


def make_image(path: Path, *, codec: str = "png", size: str = "1280x720") -> Path:
    """An extensionless image, as the worker saves every input."""
    muxer = "gif" if codec == "gif" else "image2"
    subprocess.run(
        [
            "ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", f"testsrc=size={size}:rate=1",
            "-frames:v", "1", "-c:v", codec, "-f", muxer, str(path),
        ],
        check=True,
    )  # fmt: skip
    return path


def has_encoder(name: str) -> bool:
    if shutil.which("ffmpeg") is None:
        return False
    listing = subprocess.run(["ffmpeg", "-hide_banner", "-encoders"], capture_output=True)
    return name in listing.stdout.decode()


def probe(path: Path) -> dict[str, Any]:
    out = subprocess.run(
        [
            "ffprobe", "-v", "error", "-show_entries",
            "stream=codec_type,codec_name,width,height,channels,sample_rate,r_frame_rate"
            ":format=duration",
            "-of", "json", str(path),
        ],
        check=True, capture_output=True,
    )  # fmt: skip
    report: dict[str, Any] = json.loads(out.stdout)
    return report


def refused(call: Callable[[], object]) -> PermanentError:
    with pytest.raises(PermanentError) as caught:
        call()
    return caught.value


def encode_filter(argv: list[str]) -> str:
    return argv[argv.index("-vf") + 1]


# --- pure rules --------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("size", "expected"),
    [
        ((3001, 1001), (1920, 640)),
        ((641, 361), (640, 360)),
        ((1, 1), (2, 2)),
        ((1920, 1080), (1920, 1080)),
    ],
)
def test_target_size_keeps_the_aspect_within_1920_with_even_sides(
    size: tuple[int, int], expected: tuple[int, int]
) -> None:
    assert still.target_size(*size) == expected


def test_an_empty_param_set_takes_the_defaults() -> None:
    assert still.check_params({}) == (5.0, 30, "zoom_in")


def test_the_whole_domain_is_accepted() -> None:
    params = {"durationSec": 60, "fps": 1.0, "motion": "none"}
    assert still.check_params(params) == (60.0, 1, "none")
    assert still.check_params({"durationSec": 1, "fps": 60, "motion": "zoom_out"}) == (
        1.0,
        60,
        "zoom_out",
    )


@pytest.mark.parametrize(
    ("key", "value"),
    [
        ("durationSec", 0.5),
        ("durationSec", 61),
        ("durationSec", True),
        ("durationSec", "5"),
        ("durationSec", None),
        ("durationSec", float("nan")),
        ("fps", 0),
        ("fps", 61),
        ("fps", 2.5),
        ("fps", False),
        ("fps", "30"),
        ("motion", "pan_left"),
        ("motion", ["zoom_in"]),
        ("motion", None),
        ("motion", 1),
    ],
)
def test_a_value_outside_the_domain_is_a_contract_violation_naming_the_field(
    key: str, value: Any
) -> None:
    error = refused(lambda: still.check_params({key: value}))

    assert error.code == "contract_violation"
    assert key in error.message


def test_zoom_out_filter_of_a_two_second_clip() -> None:
    filter_ = still.zoom_filter(640, 360, 2.0, 25, "zoom_out")

    assert "scale=2560:-2" in filter_
    assert "zoompan=z='if(lte(zoom,1.0),1.12,max(zoom-0.0005,1.0))':d=50" in filter_
    assert "s=640x360:fps=25" in filter_


def test_zoom_in_filter_grows_to_the_cap() -> None:
    assert "z='min(zoom+0.0005,1.12)'" in still.zoom_filter(640, 360, 2.0, 25, "zoom_in")


def test_a_non_zoom_motion_has_no_zoom_filter() -> None:
    with pytest.raises(ValueError, match="none"):
        still.zoom_filter(640, 360, 2.0, 25, "none")


def test_static_filter_scales_and_sets_the_rate() -> None:
    assert still.static_filter(640, 360, 25) == "scale=640:360,fps=25,format=yuv420p"


# --- handler: refusals before any decode -------------------------------------------------


def test_the_input_key_must_be_image() -> None:
    runner = RecordingRunner()

    error = refused(lambda: still_handler.handle(job(), {"video": Path("/nonexistent")}, runner))

    assert error.code == "contract_violation"
    assert "image" in error.message
    assert runner.argvs == []


def test_no_second_input_is_accepted() -> None:
    error = refused(
        lambda: still_handler.handle(
            job(), {"image": Path("/nonexistent"), "extra": Path("/x")}, RecordingRunner()
        )
    )

    assert error.code == "contract_violation"
    assert "image" in error.message


def test_a_bad_param_is_refused_before_ffprobe_runs() -> None:
    runner = RecordingRunner()

    error = refused(
        lambda: still_handler.handle(job(durationSec=61), {"image": Path("/nonexistent")}, runner)
    )

    assert error.code == "contract_violation"
    assert "durationSec" in error.message
    assert runner.argvs == []


def test_an_image_over_the_cap_is_rejected_before_any_ffmpeg_runs() -> None:
    report = {
        "format": {"format_name": "png_pipe"},
        "streams": [{"index": 0, "codec_type": "video", "width": 5001, "height": 5000}],
    }
    runner = ReportRunner(report)

    error = refused(lambda: still_handler.handle(job(), {"image": Path("/nonexistent")}, runner))

    assert error.code == "input_rejected"
    assert "5001" in error.message
    assert [argv[0] for argv in runner.argvs] == ["ffprobe"]


@needs_ffmpeg
def test_a_gif_is_probe_failed_naming_the_format(tmp_path: Path) -> None:
    gif = make_image(tmp_path / "input", codec="gif", size="64x64")
    runner = RecordingRunner()

    error = refused(lambda: still_handler.handle(job(), {"image": gif}, runner))

    assert error.code == "probe_failed"
    assert "gif" in error.message
    assert runner.encodes == []


@needs_ffmpeg
def test_random_bytes_are_probe_failed_naming_ffprobe(tmp_path: Path) -> None:
    noise = tmp_path / "input"
    noise.write_bytes(bytes(range(256)) * 16)

    error = refused(lambda: still_handler.handle(job(), {"image": noise}, RecordingRunner()))

    assert error.code == "probe_failed"
    assert "ffprobe" in error.message


# --- handler: real encodes ---------------------------------------------------------------


@needs_ffmpeg
def test_the_default_still_is_five_seconds_with_a_silent_audio_track(tmp_path: Path) -> None:
    image = make_image(tmp_path / "input")

    outcome = still_handler.handle(job(), {"image": image}, ProcessRunner())

    assert [(f.name, f.mime) for f in outcome.files] == [("still.mp4", "video/mp4")]
    made = tmp_path / "still.mp4"
    assert outcome.files[0].body == made
    assert outcome.data["motion"] == "zoom_in"
    assert (outcome.data["width"], outcome.data["height"]) == (1280, 720)
    assert abs(outcome.data["durationMs"] - 5000) <= 40
    assert set(outcome.data) == {"width", "height", "durationMs", "motion"}
    streams = {s["codec_type"]: s for s in probe(made)["streams"]}
    assert streams["video"]["codec_name"] == "h264"
    assert streams["video"]["r_frame_rate"] == "30/1"
    assert streams["audio"]["codec_name"] == "aac"
    assert (streams["audio"]["channels"], streams["audio"]["sample_rate"]) == (2, "44100")
    assert sum(s["codec_type"] == "audio" for s in probe(made)["streams"]) == 1


@needs_ffmpeg
def test_a_wide_image_is_scaled_down_to_1920(tmp_path: Path) -> None:
    image = make_image(tmp_path / "input", size="3001x1001")

    outcome = still_handler.handle(
        job(durationSec=1, fps=5, motion="none"), {"image": image}, ProcessRunner()
    )

    assert (outcome.data["width"], outcome.data["height"]) == (1920, 640)


@needs_ffmpeg
@pytest.mark.parametrize(
    "codec",
    [
        "png",
        "mjpeg",
        pytest.param(
            "libwebp",
            marks=pytest.mark.skipif(not has_encoder("libwebp"), reason="no libwebp encoder"),
        ),
    ],
)
def test_each_accepted_image_format_is_decoded_with_its_demuxer_forced(
    tmp_path: Path, codec: str
) -> None:
    image = make_image(tmp_path / "input", codec=codec, size="320x180")
    runner = RecordingRunner()

    outcome = still_handler.handle(
        job(durationSec=1, fps=5, motion="none"), {"image": image}, runner
    )

    assert outcome.data["motion"] == "none"
    [encode] = runner.encodes
    forced = encode[encode.index("-f", encode.index("-loop")) + 1]
    assert forced in {"jpeg_pipe", "png_pipe", "webp_pipe"}
    assert encode[encode.index("-protocol_whitelist") + 1] == "file"
    assert encode.index("-f") < encode.index("-i")
    assert encode[-1] == str(tmp_path / "still.mp4")


@needs_ffmpeg
def test_the_zoom_encode_runs_once_with_the_zoom_filter_over_the_whole_clip(
    tmp_path: Path,
) -> None:
    image = make_image(tmp_path / "input", size="640x360")
    runner = RecordingRunner()

    outcome = still_handler.handle(
        job(durationSec=2, fps=25, motion="zoom_out"), {"image": image}, runner
    )

    [encode] = runner.encodes
    assert "zoompan=z='if(lte(zoom,1.0),1.12,max(zoom-0.0005,1.0))':d=50" in encode_filter(encode)
    assert outcome.data["motion"] == "zoom_out"
    assert abs(outcome.data["durationMs"] - 2000) <= 40


@needs_ffmpeg
def test_a_failed_zoom_encode_falls_back_to_one_static_encode(tmp_path: Path) -> None:
    image = make_image(tmp_path / "input", size="640x360")
    runner = RecordingRunner(lambda argv: "zoompan" in encode_filter(argv))

    outcome = still_handler.handle(
        job(durationSec=2, fps=25, motion="zoom_in"), {"image": image}, runner
    )

    first, second = runner.encodes
    assert "zoompan" in encode_filter(first)
    assert "zoompan" not in encode_filter(second)
    assert outcome.data["motion"] == "none"
    assert (outcome.data["width"], outcome.data["height"]) == (640, 360)


@needs_ffmpeg
def test_a_failed_static_encode_is_probe_failed_naming_ffmpeg(tmp_path: Path) -> None:
    image = make_image(tmp_path / "input", size="640x360")
    runner = RecordingRunner(lambda argv: True)

    error = refused(lambda: still_handler.handle(job(durationSec=1), {"image": image}, runner))

    assert error.code == "probe_failed"
    assert "ffmpeg" in error.message
    assert "simulated encoder failure" in error.message
    assert len(runner.encodes) == 2


@needs_ffmpeg
def test_a_static_motion_that_fails_is_not_retried(tmp_path: Path) -> None:
    image = make_image(tmp_path / "input", size="640x360")
    runner = RecordingRunner(lambda argv: True)

    error = refused(
        lambda: still_handler.handle(job(durationSec=1, motion="none"), {"image": image}, runner)
    )

    assert error.code == "probe_failed"
    assert len(runner.encodes) == 1


@needs_ffmpeg
def test_a_kill_during_the_encode_propagates(tmp_path: Path) -> None:
    image = make_image(tmp_path / "input", size="320x180")

    class Killed(RecordingRunner):
        def run(
            self, argv: list[str], cwd: Path | None = None
        ) -> subprocess.CompletedProcess[bytes]:
            if argv[0] == "ffmpeg":
                raise ProcessKilled("run_timeout")
            return super().run(argv, cwd)

    with pytest.raises(ProcessKilled):
        still_handler.handle(job(durationSec=1), {"image": image}, Killed())
