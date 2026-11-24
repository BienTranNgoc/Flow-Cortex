"""The derive capability's stabilize and reframe ops, against real ffmpeg and the vision child."""

from __future__ import annotations

import json
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any

import pytest
from test_derive import describe, job, make_clip, refused

from flowcortex_worker.handlers import derive
from flowcortex_worker.runtime import ProcessKilled, ProcessRunner
from flowcortex_worker.video_ops import protocol, stabilize

MODELS = Path(__file__).resolve().parents[1] / "models"


def _has_vidstab() -> bool:
    if shutil.which("ffmpeg") is None:
        return False
    filters = subprocess.run(
        ["ffmpeg", "-hide_banner", "-filters"], capture_output=True, text=True, check=False
    )
    return "vidstabdetect" in filters.stdout


needs_ffmpeg = pytest.mark.skipif(
    shutil.which("ffmpeg") is None, reason="ffmpeg is installed in the worker image"
)
needs_vidstab = pytest.mark.skipif(not _has_vidstab(), reason="ffmpeg built without libvidstab")


class VisionRunner(ProcessRunner):
    """Runs everything for real, except the vision child when told to answer for it.

    `result` is the canned result of every child call, `child_exit` the exit code every child
    call returns; with neither the real child runs.
    """

    def __init__(
        self, *, result: dict[str, Any] | None = None, child_exit: int | None = None
    ) -> None:
        super().__init__()
        self.argvs: list[list[str]] = []
        self.child_requests: list[dict[str, Any]] = []
        self._result = result
        self._child_exit = child_exit

    def run(self, argv: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess[bytes]:
        self.argvs.append(argv)
        if argv[0] == sys.executable and (self._child_exit is not None or self._result is not None):
            assert cwd is not None
            self.child_requests.append(json.loads((cwd / protocol.REQUEST_FILE).read_text()))
            if self._child_exit is not None:
                return subprocess.CompletedProcess(argv, self._child_exit, b"", b"stubbed\n")
            (cwd / protocol.RESULT_FILE).write_text(json.dumps(self._result))
            return subprocess.CompletedProcess(argv, 0, b"", b"")
        return super().run(argv, cwd)

    @property
    def ffmpeg_runs(self) -> list[list[str]]:
        return [argv for argv in self.argvs if argv[0] == "ffmpeg"]


def run_derive(video: Path, runner: ProcessRunner, **params: Any) -> Any:
    return derive.handle(job(**params), {"video": video}, runner)


def make_shaky_clip(path: Path) -> Path:
    """A 2 s test pattern whose picture jitters about 10 px a frame inside a larger source."""
    subprocess.run(
        [
            "ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i",
            "testsrc2=size=320x180:rate=25:duration=2,"
            "crop=300:160:x='10+8*sin(n*1.7)':y='10+6*cos(n*2.3)'",
            "-f", "lavfi", "-i", "sine=duration=2",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-f", "mp4", str(path),
        ],
        check=True,
    )  # fmt: skip
    return path


def frame_png(source: Path, video_filter: str, target: Path, *, seek: str | None = None) -> Path:
    """One frame of `source` as a PNG, picked and cropped by `video_filter`."""
    command = ["ffmpeg", "-v", "error", "-y"]
    command += ["-ss", seek] if seek else []
    command += ["-i", str(source), "-vf", video_filter, "-frames:v", "1", str(target)]
    subprocess.run(command, check=True)
    return target


def psnr(a: Path, b: Path) -> float:
    out = subprocess.run(
        ["ffmpeg", "-hide_banner", "-i", str(a), "-i", str(b), "-lavfi", "psnr", "-f", "null", "-"],
        capture_output=True,
        text=True,
        check=True,
    )
    found = re.search(r"average:(\S+)", out.stderr)
    assert found, out.stderr
    return float(found.group(1))


def shot_faces(frames: int, cut: int, first: float, second: float) -> dict[str, Any]:
    """A `track` result: a face centred on `first` before frame `cut` and on `second` from it."""

    def box(centre: float) -> list[float]:
        return [centre - 30, 100.0, centre + 30, 300.0]

    return {
        "frames": frames,
        "cuts": [cut],
        "faces": [box(first) if i < cut else box(second) for i in range(frames)],
    }


# --- parameters ----------------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("params", "field"),
    [
        ({"op": "stabilize", "opParams": {"smoothing": True}}, "smoothing"),
        ({"op": "stabilize", "opParams": {"smoothing": -1}}, "smoothing"),
        ({"op": "stabilize", "opParams": {"smoothing": 1001}}, "smoothing"),
        ({"op": "stabilize", "opParams": {"optzoom": 3}}, "optzoom"),
        ({"op": "reframe", "opParams": {"sheet": []}}, "sheet"),
    ],
)
def test_a_bad_op_parameter_is_refused_before_any_tool_runs(
    params: dict[str, Any], field: str
) -> None:
    runner = VisionRunner()

    error = refused(lambda: run_derive(Path("/nonexistent"), runner, **params))

    assert error.code == "contract_violation"
    assert field in error.message
    assert runner.argvs == []


# --- stabilize -----------------------------------------------------------------------------------


@needs_vidstab
def test_stabilize_returns_the_window_with_its_zoom_and_steadier_verdict(tmp_path: Path) -> None:
    pytest.importorskip("cv2")
    clip = make_shaky_clip(tmp_path / "input")
    runner = VisionRunner()

    outcome = run_derive(clip, runner, op="stabilize")

    [stored] = outcome.files
    assert stored.name == "derive.mp4"
    extras = outcome.data["extras"]
    assert isinstance(extras["zoomPct"], int)
    assert extras["zoomPct"] >= 0
    assert isinstance(extras["notSteadier"], bool)
    assert outcome.data["hasAudio"] is True
    assert outcome.data["width"] == 300
    assert outcome.data["height"] == 160
    assert not (tmp_path / "vision").exists()
    streams = describe(stored.body)["streams"]
    assert "audio" in streams
    assert "rotate" not in streams["video"].get("tags", {})


@needs_vidstab
def test_stabilize_steadies_a_shaky_pan_and_says_so(tmp_path: Path) -> None:
    pytest.importorskip("cv2")
    clip = make_shaky_clip(tmp_path / "input")

    outcome = run_derive(clip, VisionRunner(), op="stabilize", opParams={"smoothing": 15})

    assert outcome.data["extras"]["notSteadier"] is False


@needs_vidstab
def test_the_window_is_cut_once_to_an_intra_copy_that_both_vidstab_passes_read(
    tmp_path: Path,
) -> None:
    clip = make_shaky_clip(tmp_path / "input")
    runner = VisionRunner(result={"windowMotion": 2.0, "outputMotion": 1.0})

    run_derive(clip, runner, op="stabilize", srcInMs=200, srcOutMs=1200, keepAudio=False)

    cut, detect, transform = runner.ffmpeg_runs
    assert cut[cut.index("-ss") + 1] == "0.200"
    assert cut[cut.index("-t") + 1] == "1.000"
    assert cut[cut.index("-g") : cut.index("-g") + 4] == ["-g", "1", "-crf", "10"]
    assert any(a.startswith("vidstabdetect=result=") for a in detect)
    window = next(a for a in cut if a.endswith("window.mkv"))
    assert window in detect
    assert window in transform
    assert transform.count("-i") == 1  # no audio input when keepAudio is false
    assert "-an" in transform
    assert any("optzoom=1" in a and "smoothing=10" in a for a in transform)


@needs_vidstab
def test_stabilize_trims_an_odd_sided_source_to_even_sides(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input", size="321x181", seconds=2)
    runner = VisionRunner(result={"windowMotion": 2.0, "outputMotion": 1.0})

    outcome = run_derive(clip, runner, op="stabilize")

    assert (outcome.data["width"], outcome.data["height"]) == (320, 180)


@needs_ffmpeg
def test_reframe_crops_an_odd_high_source_to_an_even_height(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input", size="1281x721", seconds=2)
    track = shot_faces(50, 25, 202, 402)

    outcome = run_derive(clip, VisionRunner(result=track), op="reframe", keepAudio=False)

    assert (outcome.data["width"], outcome.data["height"]) == (404, 720)


@needs_vidstab
def test_a_missing_vision_runtime_fails_stabilize_naming_it_and_leaves_no_work_dir(
    tmp_path: Path,
) -> None:
    clip = make_shaky_clip(tmp_path / "input")
    runner = VisionRunner(child_exit=protocol.RUNTIME_MISSING)

    error = refused(lambda: run_derive(clip, runner, op="stabilize"))

    assert error.code == "contract_violation"
    assert "vision runtime" in error.message
    assert not (tmp_path / "vision").exists()


@needs_vidstab
def test_a_kill_during_the_transform_propagates_and_leaves_no_work_dir(tmp_path: Path) -> None:
    clip = make_shaky_clip(tmp_path / "input")

    class Killed(VisionRunner):
        def run(
            self, argv: list[str], cwd: Path | None = None
        ) -> subprocess.CompletedProcess[bytes]:
            if any("vidstabtransform" in a for a in argv):
                raise ProcessKilled("job cancelled")
            return super().run(argv, cwd)

    runner = Killed()

    with pytest.raises(ProcessKilled):
        run_derive(clip, runner, op="stabilize")

    assert not (tmp_path / "vision").exists()


@needs_ffmpeg
@pytest.mark.parametrize(
    ("exit_code", "named"),
    [
        (protocol.FACE_MODEL_MISSING, "face detection model"),
        (protocol.RUNTIME_MISSING, "vision runtime"),
    ],
)
def test_a_reframe_without_the_face_model_or_the_runtime_fails_naming_it(
    tmp_path: Path, exit_code: int, named: str
) -> None:
    clip = make_clip(tmp_path / "input", size="1280x720", seconds=1)
    runner = VisionRunner(child_exit=exit_code)

    error = refused(lambda: run_derive(clip, runner, op="reframe"))

    assert error.code == "contract_violation"
    assert named in error.message
    assert not (tmp_path / "vision").exists()
    assert len(runner.ffmpeg_runs) == 1  # the analysis only; no encode follows


@needs_ffmpeg
def test_a_window_the_disk_cannot_hold_is_refused_before_any_decode(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    clip = make_clip(tmp_path / "input", seconds=1)
    real = stabilize.check_disk_room

    class Full:
        free = 1000

    monkeypatch.setattr(
        stabilize,
        "check_disk_room",
        lambda w, h, fps, seconds, work_dir: real(w, h, fps, seconds, work_dir, lambda _: Full()),
    )
    runner = VisionRunner()

    error = refused(lambda: run_derive(clip, runner, op="stabilize"))

    assert error.code == "duration_exceeded"
    assert runner.ffmpeg_runs == []
    assert not (tmp_path / "vision").exists()


# --- reframe -------------------------------------------------------------------------------------


@needs_ffmpeg
@pytest.mark.parametrize("boxes", [None, "no product", [{"tile": 1, "bbox": None}]])
def test_a_landscape_window_without_a_usable_box_is_cropped_to_404_by_720(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, boxes: object
) -> None:
    pytest.importorskip("cv2")
    monkeypatch.setenv("FLOWCORTEX_OVERLAY_MODELS", str(MODELS))
    clip = make_clip(tmp_path / "input", size="1280x720", seconds=2)
    runner = VisionRunner()
    op_params: dict[str, Any] = {"sheet": {"cols": 1, "rows": 1, "stepMs": 1000, "frames": 1}}
    if boxes is not None:
        op_params["boxes"] = boxes

    outcome = run_derive(clip, runner, op="reframe", opParams=op_params)

    assert (outcome.data["width"], outcome.data["height"]) == (404, 720)
    assert outcome.data["extras"] == {"productMissing": True}
    assert outcome.data["hasAudio"] is True
    sampled, encoded = runner.ffmpeg_runs
    assert "fps=25/1,setpts=N/(25/1*TB),scale=640:360" in sampled
    assert any("sendcmd=f=" in a and "crop=404:720:" in a for a in encoded)
    assert not (tmp_path / "vision").exists()
    assert not (tmp_path / "crop.cmd").exists()


@needs_ffmpeg
def test_a_portrait_source_is_rejected_before_any_decode(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input", size="720x1280", seconds=1)
    runner = VisionRunner()

    error = refused(lambda: run_derive(clip, runner, op="reframe"))

    assert error.code == "input_rejected"
    assert runner.ffmpeg_runs == []


@needs_ffmpeg
def test_the_child_is_asked_for_the_tracking_in_source_pixels(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input", size="1280x720", seconds=1)
    runner = VisionRunner(result={"frames": 25, "cuts": [], "faces": [None] * 25})

    run_derive(clip, runner, op="reframe")

    assert runner.child_requests == [{"stride": 5, "scale": 2.0}]


@needs_ffmpeg
@pytest.mark.parametrize(
    "track",
    [
        {"frames": 2, "cuts": [], "faces": [None]},
        {"frames": 2, "cuts": ["x"], "faces": [None, None]},
        {"frames": 1, "cuts": [], "faces": [[1, 2, 3]]},
        {"frames": 0, "cuts": [], "faces": []},
        {"cuts": [], "faces": []},
    ],
)
def test_a_malformed_track_result_fails_the_job_and_leaves_no_work_dir(
    tmp_path: Path, track: dict[str, Any]
) -> None:
    clip = make_clip(tmp_path / "input", size="1280x720", seconds=1)
    runner = VisionRunner(result=track)

    error = refused(lambda: run_derive(clip, runner, op="reframe"))

    assert error.code in {"contract_violation", "probe_failed"}
    assert not (tmp_path / "vision").exists()


@needs_ffmpeg
def test_a_product_box_pulls_the_crop_to_the_product(
    tmp_path: Path,
) -> None:
    clip = make_clip(tmp_path / "input", size="1280x720", seconds=2)
    track = {"frames": 50, "cuts": [], "faces": [None] * 50}
    # Tile 1 of a 2x1 sheet is sheet x 0-500; the box is its right-hand 20 %: source x 1024-1280.
    boxes = [{"tile": 1, "bbox": [400, 100, 500, 900]}]
    sheet = {"cols": 2, "rows": 1, "stepMs": 1000, "frames": 2}

    outcome = run_derive(
        clip, VisionRunner(result=track), op="reframe", opParams={"boxes": boxes, "sheet": sheet}
    )

    assert outcome.data["extras"] == {"productMissing": False}
    produced = tmp_path / "derive.mp4"
    got = frame_png(produced, "select=eq(n\\,10)", tmp_path / "got.png")
    fixed = frame_png(clip, "select=eq(n\\,10),crop=404:720:876:0", tmp_path / "x876.png")
    centred = frame_png(clip, "select=eq(n\\,10),crop=404:720:438:0", tmp_path / "x438.png")
    assert psnr(got, fixed) >= 40
    assert psnr(got, centred) < 30


@needs_ffmpeg
def test_the_crop_moves_at_the_frame_time_of_the_cut(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input", size="1280x720", seconds=2)
    track = shot_faces(50, 25, 202, 402)  # left edge 0, then 200 from frame 25

    run_derive(clip, VisionRunner(result=track), op="reframe", keepAudio=False)

    produced = tmp_path / "derive.mp4"
    for frame, edge, other in [(24, 0, 200), (25, 200, 0), (0, 0, 200), (49, 200, 0)]:
        got = frame_png(produced, f"select=eq(n\\,{frame})", tmp_path / f"got{frame}.png")
        want = frame_png(
            clip, f"select=eq(n\\,{frame}),crop=404:720:{edge}:0", tmp_path / f"want{frame}.png"
        )
        wrong = frame_png(
            clip, f"select=eq(n\\,{frame}),crop=404:720:{other}:0", tmp_path / f"wrong{frame}.png"
        )
        assert psnr(got, want) >= 40, frame
        assert psnr(got, wrong) < 40, frame


@needs_ffmpeg
def test_a_window_start_off_the_frame_grid_keeps_the_crop_on_the_frame_time(
    tmp_path: Path,
) -> None:
    clip = make_clip(tmp_path / "input", size="1280x720", seconds=3)
    track = shot_faces(49, 25, 202, 402)

    run_derive(clip, VisionRunner(result=track), op="reframe", srcInMs=1013, keepAudio=False)

    produced = tmp_path / "derive.mp4"
    timing = "fps=25/1,setpts=N/(25/1*TB)"
    for frame, edge, other in [(24, 0, 200), (25, 200, 0)]:
        got = frame_png(produced, f"select=eq(n\\,{frame})", tmp_path / f"got{frame}.png")
        want = frame_png(
            clip,
            f"{timing},select=eq(n\\,{frame}),crop=404:720:{edge}:0",
            tmp_path / f"want{frame}.png",
            seek="1.013",
        )
        wrong = frame_png(
            clip,
            f"{timing},select=eq(n\\,{frame}),crop=404:720:{other}:0",
            tmp_path / f"wrong{frame}.png",
            seek="1.013",
        )
        assert psnr(got, want) >= 40, frame
        assert psnr(got, wrong) < 40, frame
