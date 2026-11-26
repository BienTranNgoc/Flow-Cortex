"""The vision child: its command line, runtime and model preconditions, and its three commands."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any

import pytest

cv2 = pytest.importorskip("cv2")
np = pytest.importorskip("numpy")

from flowcortex_worker.video_ops import protocol, vision  # noqa: E402

MODELS = Path(__file__).resolve().parents[1] / "models"
CHILD = [sys.executable, "-m", "flowcortex_worker.video_ops.vision"]

needs_ffmpeg = pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="ffmpeg not installed")


def ffmpeg(*args: str) -> None:
    subprocess.run(
        ["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y", *args],
        check=True,
        timeout=120,
    )


def work_dir(tmp_path: Path, request: dict[str, Any] | None = None) -> Path:
    work = tmp_path / "work"
    work.mkdir()
    (work / protocol.REQUEST_FILE).write_text(json.dumps(request or {}), encoding="utf-8")
    return work


def child(
    work: Path, *command: str, models: Path | None = MODELS
) -> subprocess.CompletedProcess[str]:
    env = {"PATH": os.environ["PATH"]}
    if models is not None:
        env["FLOWCORTEX_OVERLAY_MODELS"] = str(models)
    return subprocess.run(
        [*CHILD, *command],
        cwd=work,
        capture_output=True,
        text=True,
        env=env,
        timeout=120,
        check=False,
    )


def result(work: Path) -> dict[str, Any]:
    loaded = json.loads((work / protocol.RESULT_FILE).read_text(encoding="utf-8"))
    assert isinstance(loaded, dict)
    return loaded


def shifting_clip(path: Path, step: int = 4, frames: int = 64) -> None:
    ffmpeg(
        "-f", "lavfi", "-i", "testsrc=size=1000x360:rate=25",
        "-vf", f"crop=640:360:x={step}*n:y=0",
        "-frames:v", str(frames),
        "-c:v", "libx264", "-crf", "12", "-pix_fmt", "yuv420p", str(path),
    )  # fmt: skip


def still_clip(path: Path, frames: int = 64) -> None:
    frame = path.with_suffix(".png")
    ffmpeg("-f", "lavfi", "-i", "testsrc=size=640x360", "-frames:v", "1", str(frame))
    ffmpeg(
        "-loop", "1", "-framerate", "25", "-i", str(frame),
        "-frames:v", str(frames), "-c:v", "libx264", "-pix_fmt", "yuv420p", str(path),
    )  # fmt: skip


def black_then_white(path: Path) -> None:
    ffmpeg(
        "-f", "lavfi", "-i", "color=c=black:s=320x180:r=25:d=1",
        "-f", "lavfi", "-i", "color=c=white:s=320x180:r=25:d=1",
        "-filter_complex", "[0][1]concat=n=2:v=1:a=0",
        "-c:v", "libx264", "-pix_fmt", "yuv420p", str(path),
    )  # fmt: skip


class FakeFaces:
    """A face engine that answers from the brightness of the frame it is shown."""

    def __init__(self) -> None:
        self.shown: list[float] = []

    def faces(self, bgr: Any) -> list[tuple[int, int, int, int]]:
        brightness = float(bgr.mean())
        self.shown.append(brightness)
        if brightness < 128:
            return [(10, 20, 30, 40), (0, 0, 5, 5)]
        return []


# --- command line ------------------------------------------------------------------------


@pytest.mark.parametrize(
    "command", [(), ("../x",), ("Faces",), ("motion", "extra"), ("faces", "../x")]
)
def test_anything_but_one_known_command_exits_with_usage_and_writes_nothing(
    tmp_path: Path, command: tuple[str, ...]
) -> None:
    work = work_dir(tmp_path)
    before = sorted(p.name for p in work.iterdir())

    done = child(work, *command)

    assert done.returncode == protocol.USAGE
    assert sorted(p.name for p in work.iterdir()) == before
    assert not (tmp_path / "x").exists()


@pytest.mark.parametrize("request_body", ["not json", "[]", '{"scale": "2"}', '{"scale": 0}'])
def test_a_request_the_command_cannot_use_exits_with_usage(
    tmp_path: Path, request_body: str
) -> None:
    work = work_dir(tmp_path)
    (work / protocol.REQUEST_FILE).write_text(request_body, encoding="utf-8")
    (work / protocol.FRAMES_DIR).mkdir()

    done = child(work, "faces")

    assert done.returncode == protocol.USAGE
    assert not (work / protocol.RESULT_FILE).exists()


# --- runtime and model preconditions -----------------------------------------------------


def test_without_the_face_model_faces_exits_4(tmp_path: Path) -> None:
    work = work_dir(tmp_path, {"scale": 1.0})
    (work / protocol.FRAMES_DIR).mkdir()
    cv2.imwrite(str(work / "frames" / "f_0001.jpg"), np.zeros((90, 160, 3), np.uint8))
    empty = tmp_path / "models"
    empty.mkdir()

    done = child(work, "faces", models=empty)

    assert done.returncode == protocol.FACE_MODEL_MISSING, done.stderr
    assert not (work / protocol.RESULT_FILE).exists()


def test_without_the_face_model_track_exits_4(tmp_path: Path) -> None:
    work = work_dir(tmp_path, {"scale": 1.0, "stride": 5})
    empty = tmp_path / "models"
    empty.mkdir()

    assert child(work, "track", models=empty).returncode == protocol.FACE_MODEL_MISSING


@needs_ffmpeg
def test_motion_needs_no_face_model(tmp_path: Path) -> None:
    work = work_dir(tmp_path)
    still_clip(work / protocol.WINDOW_FILE, frames=8)
    still_clip(work / protocol.OUTPUT_FILE, frames=8)
    empty = tmp_path / "models"
    empty.mkdir()

    done = child(work, "motion", models=empty)

    assert done.returncode == protocol.OK, done.stderr


def test_without_opencv_the_child_exits_5_before_reading_anything(tmp_path: Path) -> None:
    work = work_dir(tmp_path)
    wrapper = (
        "import runpy, sys; sys.modules['cv2'] = None; sys.argv = ['vision', 'motion']; "
        "runpy.run_module('flowcortex_worker.video_ops.vision', run_name='__main__')"
    )

    done = subprocess.run(
        [sys.executable, "-c", wrapper],
        cwd=work,
        capture_output=True,
        text=True,
        env={"PATH": os.environ["PATH"]},
        timeout=120,
        check=False,
    )

    assert done.returncode == protocol.RUNTIME_MISSING, done.stderr
    assert not (work / protocol.RESULT_FILE).exists()


# --- motion ------------------------------------------------------------------------------


@needs_ffmpeg
def test_motion_of_a_pattern_shifting_four_pixels_a_frame_is_four(tmp_path: Path) -> None:
    work = work_dir(tmp_path)
    shifting_clip(work / protocol.WINDOW_FILE)
    still_clip(work / protocol.OUTPUT_FILE)

    done = child(work, "motion")

    assert done.returncode == protocol.OK, done.stderr
    measured = result(work)
    assert measured["windowMotion"] == pytest.approx(4.0, abs=0.5)
    assert measured["outputMotion"] < 0.1


@needs_ffmpeg
def test_motion_of_a_missing_file_fails_loudly(tmp_path: Path) -> None:
    work = work_dir(tmp_path)
    shifting_clip(work / protocol.WINDOW_FILE, frames=8)

    done = child(work, "motion")

    assert done.returncode == protocol.FAILED
    assert protocol.OUTPUT_FILE in done.stderr
    assert not (work / protocol.RESULT_FILE).exists()


# --- track -------------------------------------------------------------------------------


@needs_ffmpeg
def test_track_finds_the_cut_between_black_and_white_and_no_faces(tmp_path: Path) -> None:
    work = work_dir(tmp_path, {"scale": 1.0, "stride": 5})
    black_then_white(work / protocol.ANALYSIS_FILE)

    done = child(work, "track")

    assert done.returncode == protocol.OK, done.stderr
    assert result(work) == {"frames": 50, "cuts": [25], "faces": [None] * 50}


@needs_ffmpeg
def test_track_looks_for_faces_every_stride_and_on_cut_frames_and_drops_the_held_face_at_a_cut(
    tmp_path: Path,
) -> None:
    work = work_dir(tmp_path)
    black_then_white(work / protocol.ANALYSIS_FILE)
    engine = FakeFaces()

    tracked = vision.run_track(work, {"scale": 2.0, "stride": 10}, engine)

    assert len(engine.shown) == 6  # frames 0, 10, 20, the cut at 25, then 30, 40
    assert tracked["cuts"] == [25]
    biggest = [20.0, 40.0, 80.0, 120.0]  # the 30x40 box at (10, 20), scaled by 2
    assert tracked["faces"][:25] == [biggest] * 25
    assert tracked["faces"][25:] == [None] * 25


# --- faces -------------------------------------------------------------------------------


def test_faces_reads_the_frames_in_numeric_name_order_and_scales_every_box(tmp_path: Path) -> None:
    work = work_dir(tmp_path)
    frames = work / protocol.FRAMES_DIR
    frames.mkdir()
    for name, grey in (("f_0010.jpg", 0), ("f_0002.jpg", 255), ("f_0001.jpg", 0)):
        assert cv2.imwrite(str(frames / name), np.full((90, 160, 3), grey, np.uint8))
    (frames / "notes.txt").write_text("not a frame", encoding="utf-8")
    engine = FakeFaces()

    found = vision.run_faces(work, {"scale": 2.0}, engine)

    assert len(engine.shown) == 3
    assert [b < 128 for b in engine.shown] == [True, False, True]
    assert found == {
        "faces": [
            [[20.0, 40.0, 80.0, 120.0], [0.0, 0.0, 10.0, 10.0]],
            [],
            [[20.0, 40.0, 80.0, 120.0], [0.0, 0.0, 10.0, 10.0]],
        ]
    }


def test_faces_with_the_real_model_finds_none_on_a_blank_frame(tmp_path: Path) -> None:
    work = work_dir(tmp_path, {"scale": 1.5})
    (work / protocol.FRAMES_DIR).mkdir()
    for index in (1, 2):
        path = work / "frames" / f"f_{index:04d}.jpg"
        assert cv2.imwrite(str(path), np.zeros((180, 320, 3), np.uint8))

    done = child(work, "faces")

    assert done.returncode == protocol.OK, done.stderr
    assert result(work) == {"faces": [[], []]}


def test_faces_with_no_frames_is_an_empty_answer(tmp_path: Path) -> None:
    work = work_dir(tmp_path, {"scale": 1.0})
    (work / protocol.FRAMES_DIR).mkdir()

    done = child(work, "faces")

    assert done.returncode == protocol.OK, done.stderr
    assert result(work) == {"faces": []}
