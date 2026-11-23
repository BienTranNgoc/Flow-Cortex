"""The reframe capability: ratio and crop rules, face following and the single encode."""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest
from conftest import job_body

from flowcortex_worker.contracts.media_job import MediaJob
from flowcortex_worker.errors import PermanentError
from flowcortex_worker.handlers import reframe as reframe_handler
from flowcortex_worker.runtime import ProcessKilled, ProcessRunner
from flowcortex_worker.video_ops import protocol, reframe

needs_ffmpeg = pytest.mark.skipif(
    shutil.which("ffmpeg") is None, reason="ffmpeg is installed in the worker image"
)

Box = list[float]


class RecordingRunner(ProcessRunner):
    """Runs everything for real and records every call; `fail_when` picks an ffmpeg call to fail.

    The vision child is never started: a call to it answers `child_exit`, and with exit 0 writes
    `faces` as the child's result after recording what its work directory held.
    """

    def __init__(
        self,
        *,
        fail_when: Callable[[list[str]], bool] = lambda argv: False,
        child_exit: int = 0,
        faces: list[list[Box]] | None = None,
        raw_result: object = None,
    ) -> None:
        super().__init__()
        self.argvs: list[list[str]] = []
        self.child_calls: list[dict[str, Any]] = []
        self._fail_when = fail_when
        self._child_exit = child_exit
        self._faces = faces or []
        self._raw_result = raw_result

    def run(self, argv: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess[bytes]:
        self.argvs.append(argv)
        if argv[0] == sys.executable:
            return self._child(argv, cwd)
        if argv[0] == "ffmpeg" and self._fail_when(argv):
            return subprocess.CompletedProcess(argv, 1, b"", b"Error: simulated encoder failure\n")
        return super().run(argv, cwd)

    def _child(self, argv: list[str], cwd: Path | None) -> subprocess.CompletedProcess[bytes]:
        assert cwd is not None
        self.child_calls.append(
            {
                "argv": argv,
                "request": json.loads((cwd / protocol.REQUEST_FILE).read_text()),
                "frames": sorted(p.name for p in (cwd / protocol.FRAMES_DIR).iterdir()),
                "cwd": cwd,
            }
        )
        if self._child_exit == protocol.OK:
            result = {"faces": self._faces} if self._raw_result is None else self._raw_result
            (cwd / protocol.RESULT_FILE).write_text(json.dumps(result))
        return subprocess.CompletedProcess(argv, self._child_exit, b"", b"child stderr\n")

    @property
    def encodes(self) -> list[list[str]]:
        return [argv for argv in self.argvs if argv[0] == "ffmpeg"]


def job(**params: Any) -> MediaJob:
    return MediaJob.model_validate(job_body(params=params))


def make_clip(path: Path, *, size: str = "1280x720", seconds: int = 3, audio: bool = True) -> Path:
    command = [
        "ffmpeg", "-v", "error", "-y",
        "-f", "lavfi", "-i", f"testsrc=size={size}:rate=25:duration={seconds}",
    ]  # fmt: skip
    if audio:
        command += ["-f", "lavfi", "-i", f"sine=duration={seconds}", "-c:a", "aac"]
    subprocess.run(
        [*command, "-c:v", "libx264", "-pix_fmt", "yuv420p", "-f", "mp4", str(path)], check=True
    )
    return path


def refused(call: Callable[[], object]) -> PermanentError:
    with pytest.raises(PermanentError) as caught:
        call()
    return caught.value


def run_reframe(clip: Path, runner: ProcessRunner, **params: Any) -> Any:
    return reframe_handler.handle(job(**params), {"video": clip}, runner)


def video_filter(argv: list[str]) -> str:
    return argv[argv.index("-vf") + 1]


# --- pure rules --------------------------------------------------------------------------


def test_a_source_of_the_target_aspect_is_only_scaled() -> None:
    assert reframe.reframe_filter(720, 1280, "9:16", "fit", None, 0) == (
        "scale=1080:1920",
        "no-crop",
    )


def test_a_source_within_the_tolerance_is_only_scaled_whatever_the_mode() -> None:
    assert reframe.reframe_filter(1000, 1000, "1:1", "crop", None, 0) == (
        "scale=1080:1080",
        "no-crop",
    )
    assert reframe.reframe_filter(1000, 1015, "1:1", "fit", None, 0)[1] == "no-crop"
    assert reframe.reframe_filter(1000, 1030, "1:1", "fit", None, 0)[1] == "fit-blur-pad"


def test_fit_centres_the_whole_frame_over_a_blurred_cover_copy() -> None:
    filter_, method = reframe.reframe_filter(1280, 720, "9:16", "fit", None, 0)

    assert method == "fit-blur-pad"
    assert filter_ == (
        "split=2[bg][fg];"
        "[bg]scale=1080:1920:force_original_aspect_ratio=increase,"
        "crop=1080:1920,boxblur=20:2[bg2];"
        "[fg]scale=1080:1920:force_original_aspect_ratio=decrease[fg2];"
        "[bg2][fg2]overlay=(W-w)/2:(H-h)/2"
    )


def test_a_taller_source_is_cropped_full_width_and_centred_vertically() -> None:
    assert reframe.reframe_filter(720, 1280, "1:1", "crop", None, 0) == (
        "crop=720:720:0:280,scale=1080:1080",
        "center-crop-v",
    )


def test_a_wider_source_crops_a_window_around_the_face() -> None:
    assert reframe.reframe_filter(1280, 720, "9:16", "crop", 1000.0, 5) == (
        "crop=404:720:798:0,scale=1080:1920",
        "face-crop(n=5)",
    )


def test_a_face_near_an_edge_keeps_the_window_inside_the_frame() -> None:
    assert reframe.reframe_filter(1280, 720, "9:16", "crop", 5.0, 3)[0].startswith(
        "crop=404:720:0:0,"
    )
    assert reframe.reframe_filter(1280, 720, "9:16", "crop", 1275.0, 3)[0].startswith(
        "crop=404:720:876:0,"
    )


def test_without_a_face_the_window_is_centred() -> None:
    assert reframe.reframe_filter(1280, 720, "9:16", "crop", None, 0) == (
        "crop=404:720:438:0,scale=1080:1920",
        "center-crop",
    )


def test_the_crop_width_is_the_even_rounded_height_times_the_ratio() -> None:
    assert reframe.crop_width(720, "9:16") == 404
    assert reframe.crop_width(1080, "4:5") == 864
    assert reframe.crop_width(720, "1:1") == 720


@pytest.mark.parametrize(
    ("size", "ratio", "mode", "expected"),
    [
        ((1280, 720), "9:16", "crop", True),
        ((1280, 720), "9:16", "fit", False),
        ((720, 1280), "1:1", "crop", False),
        ((720, 1280), "9:16", "crop", False),
        ((1000, 1000), "1:1", "crop", False),
    ],
)
def test_only_a_wider_source_in_crop_mode_follows_faces(
    size: tuple[int, int], ratio: str, mode: str, expected: bool
) -> None:
    assert reframe.follows_faces(*size, ratio, mode) is expected


def test_the_face_x_is_the_median_centre_once_three_frames_have_a_face() -> None:
    frames: list[list[Box]] = [
        [[100, 0, 200, 100], [900, 0, 1100, 100]],
        [],
        [[300, 0, 500, 100]],
        [[950, 0, 1050, 100]],
    ]

    assert reframe.face_x(frames) == (700.0, 4)


def test_fewer_than_three_frames_with_a_face_gives_no_face_x() -> None:
    assert reframe.face_x([[[0, 0, 100, 100]], [], [[0, 0, 100, 100]]]) == (None, 0)
    assert reframe.face_x([]) == (None, 0)


@pytest.mark.parametrize(
    "frames",
    [
        "x",
        ["x"],
        [["x"]],
        [[[1, 2, 3]]],
        [[[1, 2, 3, "4"]]],
        [[[1, 2, 3, True]]],
        [[[1, 2, 3, float("nan")]]],
    ],
)
def test_a_malformed_face_result_is_a_contract_violation(frames: Any) -> None:
    error = refused(lambda: reframe.face_x(frames))

    assert error.code == "contract_violation"
    assert "face" in error.message


def test_an_empty_param_set_takes_the_defaults() -> None:
    assert reframe.check_params({}) == ("9:16", "fit")


def test_the_whole_domain_is_accepted() -> None:
    for ratio in ("9:16", "1:1", "4:5", "16:9"):
        assert reframe.check_params({"aspectRatio": ratio, "cropMode": "crop"}) == (ratio, "crop")
    assert reframe.check_params({"startSec": 0, "endSec": 0.5}) == ("9:16", "fit")


@pytest.mark.parametrize(
    ("params", "field"),
    [
        ({"aspectRatio": "21:9"}, "aspectRatio"),
        ({"aspectRatio": None}, "aspectRatio"),
        ({"aspectRatio": 1}, "aspectRatio"),
        ({"cropMode": "zoom"}, "cropMode"),
        ({"cropMode": None}, "cropMode"),
        ({"startSec": -1}, "startSec"),
        ({"startSec": True}, "startSec"),
        ({"startSec": "1"}, "startSec"),
        ({"startSec": None}, "startSec"),
        ({"startSec": float("nan")}, "startSec"),
        ({"endSec": -1}, "endSec"),
        ({"endSec": "2"}, "endSec"),
        ({"endSec": 0}, "endSec"),
        ({"startSec": 2, "endSec": 2}, "endSec"),
        ({"startSec": 3, "endSec": 1}, "endSec"),
    ],
)
def test_a_value_outside_the_domain_is_a_contract_violation_naming_the_field(
    params: dict[str, Any], field: str
) -> None:
    error = refused(lambda: reframe.check_params(params))

    assert error.code == "contract_violation"
    assert field in error.message


# --- handler: refusals before any decode -------------------------------------------------


def test_the_input_key_must_be_video() -> None:
    runner = RecordingRunner()

    error = refused(lambda: reframe_handler.handle(job(), {"image": Path("/nonexistent")}, runner))

    assert error.code == "contract_violation"
    assert "video" in error.message
    assert runner.argvs == []


def test_no_second_input_is_accepted() -> None:
    error = refused(
        lambda: reframe_handler.handle(
            job(), {"video": Path("/nonexistent"), "extra": Path("/x")}, RecordingRunner()
        )
    )

    assert error.code == "contract_violation"
    assert "video" in error.message


def test_an_unknown_ratio_is_refused_before_ffprobe_runs() -> None:
    runner = RecordingRunner()

    error = refused(
        lambda: reframe_handler.handle(job(aspectRatio="21:9"), {"video": Path("/x")}, runner)
    )

    assert error.code == "contract_violation"
    assert "aspectRatio" in error.message
    assert runner.argvs == []


# --- handler: real encodes ---------------------------------------------------------------


@needs_ffmpeg
def test_a_landscape_clip_is_fitted_over_a_blurred_background(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input")
    runner = RecordingRunner()

    outcome = run_reframe(clip, runner)

    made = tmp_path / "reframe.mp4"
    assert [(f.name, f.mime, f.body) for f in outcome.files] == [("reframe.mp4", "video/mp4", made)]
    assert (outcome.data["width"], outcome.data["height"]) == (1080, 1920)
    assert outcome.data["method"] == "fit-blur-pad"
    assert abs(outcome.data["durationMs"] - 3000) <= 100
    assert set(outcome.data) == {"width", "height", "durationMs", "method"}
    [encode] = runner.encodes
    assert encode[encode.index("-protocol_whitelist") + 1] == "file"
    assert "-ss" not in encode and "-t" not in encode
    assert encode[encode.index("-c:a") + 1 :][:3] == ["aac", "-b:a", "128k"]
    assert runner.child_calls == []


@needs_ffmpeg
def test_a_portrait_clip_of_the_target_aspect_is_only_scaled(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input", size="720x1280", seconds=1)

    outcome = run_reframe(clip, RecordingRunner())

    assert outcome.data["method"] == "no-crop"
    assert (outcome.data["width"], outcome.data["height"]) == (1080, 1920)


@needs_ffmpeg
def test_a_clip_without_audio_is_reframed_without_an_audio_stream(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input", seconds=1, audio=False)

    run_reframe(clip, RecordingRunner())

    streams = subprocess.run(
        [
            "ffprobe", "-v", "error", "-show_entries", "stream=codec_type",
            "-of", "csv=p=0", str(tmp_path / "reframe.mp4"),
        ],
        check=True, capture_output=True,
    ).stdout.split()  # fmt: skip
    assert streams == [b"video"]


@needs_ffmpeg
def test_the_end_past_the_clip_is_cut_to_it_in_one_encode(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input")
    runner = RecordingRunner()

    outcome = run_reframe(clip, runner, startSec=1, endSec=10)

    assert abs(outcome.data["durationMs"] - 2000) <= 100
    [encode] = runner.encodes
    assert encode[encode.index("-ss") + 1] == "1.000"
    assert encode.index("-ss") < encode.index("-i")
    assert encode[encode.index("-t") + 1] == "2.000"
    assert encode.index("-t") > encode.index("-i")


@needs_ffmpeg
def test_an_end_inside_the_clip_sets_the_duration_from_the_window(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input")
    runner = RecordingRunner()

    run_reframe(clip, runner, startSec=1, endSec=2)

    [encode] = runner.encodes
    assert encode[encode.index("-ss") + 1] == "1.000"
    assert encode[encode.index("-t") + 1] == "1.000"


@needs_ffmpeg
def test_a_start_at_the_end_of_the_clip_is_refused_before_any_decode(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input", audio=False)
    runner = RecordingRunner()

    error = refused(lambda: run_reframe(clip, runner, startSec=3))

    assert error.code == "contract_violation"
    assert "startSec" in error.message
    assert runner.encodes == []


@needs_ffmpeg
def test_a_portrait_clip_is_cropped_to_a_square_without_the_vision_child(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input", size="720x1280", seconds=1)
    runner = RecordingRunner(child_exit=protocol.RUNTIME_MISSING)

    outcome = run_reframe(clip, runner, aspectRatio="1:1", cropMode="crop")

    assert outcome.data["method"] == "center-crop-v"
    assert (outcome.data["width"], outcome.data["height"]) == (1080, 1080)
    assert "crop=720:720:0:280,scale=1080:1080" in video_filter(runner.encodes[-1])
    assert runner.child_calls == []


@needs_ffmpeg
def test_fit_completes_where_the_vision_runtime_is_missing(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input", seconds=1)
    runner = RecordingRunner(child_exit=protocol.RUNTIME_MISSING)

    outcome = run_reframe(clip, runner, cropMode="fit")

    assert outcome.data["method"] == "fit-blur-pad"
    assert runner.child_calls == []


@needs_ffmpeg
def test_crop_centres_the_window_on_the_median_face(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input")
    faces = [[[950.0, 100.0, 1050.0, 300.0]]] * 3 + [[[940.0, 100.0, 1060.0, 300.0]], []]
    runner = RecordingRunner(faces=faces)

    outcome = run_reframe(clip, runner, cropMode="crop")

    assert outcome.data["method"] == "face-crop(n=4)"
    assert (outcome.data["width"], outcome.data["height"]) == (1080, 1920)
    assert "crop=404:720:798:0,scale=1080:1920" in video_filter(runner.encodes[-1])
    [call] = runner.child_calls
    assert call["argv"][1:] == ["-m", "flowcortex_worker.video_ops.vision", "faces"]
    assert call["request"] == {"scale": 2.0}
    assert call["frames"] == ["f_0001.jpg", "f_0002.jpg", "f_0003.jpg"]


@needs_ffmpeg
def test_the_sampling_covers_the_window_once_a_second_at_640_px(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input", seconds=4)
    runner = RecordingRunner()

    run_reframe(clip, runner, cropMode="crop", startSec=1, endSec=3)

    sample = next(a for a in runner.encodes if "fps=1,scale=640:-2" in a)
    assert sample[sample.index("-ss") + 1] == "1.000"
    assert sample.index("-ss") < sample.index("-i") < sample.index("-t")
    assert sample[sample.index("-t") + 1] == "2.000"
    assert sample[-1].endswith("vision/frames/f_%04d.jpg")
    assert "-an" in sample


@needs_ffmpeg
def test_fewer_than_three_frames_with_a_face_centre_the_window(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input")
    runner = RecordingRunner(faces=[[[950.0, 100.0, 1050.0, 300.0]], [], []])

    outcome = run_reframe(clip, runner, cropMode="crop")

    assert outcome.data["method"] == "center-crop"
    assert "crop=404:720:438:0," in video_filter(runner.encodes[-1])


@needs_ffmpeg
def test_the_vision_work_dir_is_cleared_before_and_removed_after(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input", seconds=1)
    stale = tmp_path / "vision" / "frames"
    stale.mkdir(parents=True)
    (stale / "f_9999.jpg").write_bytes(b"stale")
    runner = RecordingRunner()

    run_reframe(clip, runner, cropMode="crop")

    [call] = runner.child_calls
    assert "f_9999.jpg" not in call["frames"]
    assert not (tmp_path / "vision").exists()


@needs_ffmpeg
def test_a_missing_vision_runtime_is_a_contract_violation_naming_it(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input", seconds=1)
    runner = RecordingRunner(child_exit=protocol.RUNTIME_MISSING)

    error = refused(lambda: run_reframe(clip, runner, cropMode="crop"))

    assert error.code == "contract_violation"
    assert "vision runtime" in error.message
    assert not (tmp_path / "vision").exists()
    assert not any("libx264" in a for a in runner.encodes)


@needs_ffmpeg
def test_a_missing_face_model_is_a_contract_violation_naming_it(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input", seconds=1)
    runner = RecordingRunner(child_exit=protocol.FACE_MODEL_MISSING)

    error = refused(lambda: run_reframe(clip, runner, cropMode="crop"))

    assert error.code == "contract_violation"
    assert "face detection model" in error.message
    assert not (tmp_path / "vision").exists()


@needs_ffmpeg
def test_a_malformed_child_result_is_a_contract_violation(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input", seconds=1)
    runner = RecordingRunner(raw_result={"faces": "none"})

    error = refused(lambda: run_reframe(clip, runner, cropMode="crop"))

    assert error.code == "contract_violation"
    assert "face" in error.message
    assert not (tmp_path / "vision").exists()


@needs_ffmpeg
def test_a_result_without_the_faces_key_is_a_contract_violation(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input", seconds=1)
    runner = RecordingRunner(raw_result={"boxes": []})

    error = refused(lambda: run_reframe(clip, runner, cropMode="crop"))

    assert error.code == "contract_violation"
    assert "faces" in error.message


@needs_ffmpeg
def test_a_failed_sampling_is_probe_failed_and_leaves_no_work_dir(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input", seconds=1)
    runner = RecordingRunner(fail_when=lambda argv: "fps=1,scale=640:-2" in argv)

    error = refused(lambda: run_reframe(clip, runner, cropMode="crop"))

    assert error.code == "probe_failed"
    assert "ffmpeg" in error.message
    assert runner.child_calls == []
    assert not (tmp_path / "vision").exists()


@needs_ffmpeg
def test_a_failed_encode_is_probe_failed_naming_ffmpeg_and_its_last_line(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input", seconds=1)
    runner = RecordingRunner(fail_when=lambda argv: "libx264" in argv)

    error = refused(lambda: run_reframe(clip, runner))

    assert error.code == "probe_failed"
    assert "ffmpeg" in error.message
    assert "simulated encoder failure" in error.message


@needs_ffmpeg
def test_a_playlist_saved_without_an_extension_is_probe_failed_before_any_decode(
    tmp_path: Path,
) -> None:
    playlist = tmp_path / "input"
    playlist.write_bytes(b"#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:1.0,\nsegment.ts\n#EXT-X-ENDLIST\n")
    runner = RecordingRunner()

    error = refused(lambda: run_reframe(playlist, runner))

    assert error.code == "probe_failed"
    assert runner.encodes == []


@needs_ffmpeg
def test_a_kill_during_the_encode_propagates_and_leaves_no_work_dir(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input", seconds=1)

    class Killed(RecordingRunner):
        def run(
            self, argv: list[str], cwd: Path | None = None
        ) -> subprocess.CompletedProcess[bytes]:
            if argv[0] == sys.executable:
                raise ProcessKilled("watchdog")
            return super().run(argv, cwd)

    with pytest.raises(ProcessKilled):
        run_reframe(clip, Killed(), cropMode="crop")

    assert not (tmp_path / "vision").exists()
