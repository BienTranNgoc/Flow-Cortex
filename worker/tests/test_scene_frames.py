"""The scene_frames capability: parameter checks, length gate, normalisation and scene frames."""

from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path
from typing import Any

import pytest
from conftest import job_body

from flowcortex_worker.contracts.media_job import MediaJob
from flowcortex_worker.errors import PermanentError
from flowcortex_worker.handlers import scene_frames
from flowcortex_worker.runtime import ProcessRunner

needs_ffmpeg = pytest.mark.skipif(
    shutil.which("ffmpeg") is None, reason="ffmpeg is installed in the worker image"
)


class RecordingRunner(ProcessRunner):
    def __init__(self) -> None:
        super().__init__()
        self.argvs: list[list[str]] = []

    def run(self, argv: list[str]) -> subprocess.CompletedProcess[bytes]:
        self.argvs.append(argv)
        return super().run(argv)

    def middle_frame_runs(self) -> list[list[str]]:
        return [argv for argv in self.argvs if any("eq(n" in part for part in argv)]


def job(**params: Any) -> MediaJob:
    return MediaJob.model_validate(job_body(params=params))


def render(path: Path, *arguments: str) -> Path:
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", *arguments, "-pix_fmt", "yuv420p", "-f", "mp4", str(path)],
        check=True,
    )
    return path


def plain_clip(path: Path, *, seconds: int, size: str = "160x90", rate: int = 5) -> Path:
    return render(path, "-f", "lavfi", "-i", f"testsrc=duration={seconds}:size={size}:rate={rate}")


def two_halves(path: Path) -> Path:
    return render(
        path,
        "-f", "lavfi", "-i", "color=c=red:s=900x1000:d=2:r=25",
        "-f", "lavfi", "-i", "color=c=blue:s=900x1000:d=2:r=25",
        "-filter_complex", "[0:v][1:v]concat=n=2:v=1:a=0[v]",
        "-map", "[v]",
    )  # fmt: skip


def stream_facts(path: Path) -> dict[str, Any]:
    completed = subprocess.run(
        [
            "ffprobe", "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=width,height,avg_frame_rate", "-of", "json", str(path),
        ],
        check=True,
        capture_output=True,
    )  # fmt: skip
    facts: dict[str, Any] = json.loads(completed.stdout)["streams"][0]
    return facts


def refused(runner: ProcessRunner, source: Path, **params: Any) -> PermanentError:
    with pytest.raises(PermanentError) as caught:
        scene_frames.handle(job(**params), {"video": source}, runner)
    return caught.value


def test_input_keys_are_exactly_video() -> None:
    with pytest.raises(PermanentError) as caught:
        scene_frames.handle(job(), {"media": Path("/nonexistent")}, ProcessRunner())
    assert caught.value.code == "contract_violation"
    assert "video" in caught.value.message


@pytest.mark.parametrize("threshold", [0, 1, "0.3", True, float("nan"), -0.5])
def test_threshold_must_be_strictly_between_zero_and_one(threshold: Any) -> None:
    error = refused(ProcessRunner(), Path("/nonexistent"), sceneThreshold=threshold)
    assert error.code == "contract_violation"
    assert "sceneThreshold" in error.message


@pytest.mark.parametrize("bounds", [[0, True, 96], [0, 4, 96], [1, 30], "0,5", [0]])
def test_bad_bounds_are_refused_before_any_process_runs(bounds: Any) -> None:
    runner = RecordingRunner()
    error = refused(runner, Path("/nonexistent"), bounds=bounds)
    assert error.code == "contract_violation"
    assert "bounds" in error.message
    assert runner.argvs == []


@needs_ffmpeg
def test_three_second_clip_is_input_rejected(tmp_path: Path) -> None:
    clip = plain_clip(tmp_path / "input", seconds=3)
    assert refused(ProcessRunner(), clip).code == "input_rejected"


@needs_ffmpeg
def test_sixty_one_second_clip_is_duration_exceeded(tmp_path: Path) -> None:
    clip = plain_clip(tmp_path / "input", seconds=61)
    assert refused(ProcessRunner(), clip).code == "duration_exceeded"


@needs_ffmpeg
def test_audio_only_input_is_probe_failed(tmp_path: Path) -> None:
    source = render(tmp_path / "input", "-f", "lavfi", "-i", "sine=duration=5", "-c:a", "aac")
    assert refused(ProcessRunner(), source).code == "probe_failed"


@needs_ffmpeg
def test_two_colour_halves_become_two_scenes_on_a_square_canvas(tmp_path: Path) -> None:
    clip = two_halves(tmp_path / "input")
    runner = RecordingRunner()
    outcome = scene_frames.handle(job(), {"video": clip}, runner)

    data = outcome.data
    assert data["aspect"] == "1:1"
    assert (data["fps"], data["width"], data["height"], data["durationSec"]) == (24, 640, 640, 4.0)
    assert data["normalized"] == "normalized.mp4"
    first, second = data["scenes"]
    assert abs(second["startFrame"] - 48) <= 1
    assert first == {
        "index": 0,
        "startFrame": 0,
        "endFrame": second["startFrame"],
        "frame": "frame_000.jpg",
    }
    assert second == {
        "index": 1,
        "startFrame": second["startFrame"],
        "endFrame": 96,
        "frame": "frame_001.jpg",
    }

    assert [(f.name, f.mime) for f in outcome.files] == [
        ("normalized.mp4", "video/mp4"),
        ("frame_000.jpg", "image/jpeg"),
        ("frame_001.jpg", "image/jpeg"),
    ]
    assert all(isinstance(f.body, Path) and f.body.stat().st_size > 0 for f in outcome.files)
    normalized = outcome.files[0].body
    assert isinstance(normalized, Path)
    facts = stream_facts(normalized)
    assert (facts["width"], facts["height"], facts["avg_frame_rate"]) == (640, 640, "24/1")
    assert len(runner.middle_frame_runs()) == 1


@needs_ffmpeg
def test_caller_bounds_are_kept_without_detection(tmp_path: Path) -> None:
    clip = two_halves(tmp_path / "input")
    runner = RecordingRunner()
    outcome = scene_frames.handle(job(bounds=[0, 30, 96]), {"video": clip}, runner)

    assert [(s["startFrame"], s["endFrame"]) for s in outcome.data["scenes"]] == [
        (0, 30),
        (30, 96),
    ]
    assert not any("gt(scene" in part for argv in runner.argvs for part in argv)


@needs_ffmpeg
def test_bounds_of_another_clip_name_both_frame_counts(tmp_path: Path) -> None:
    clip = two_halves(tmp_path / "input")
    runner = RecordingRunner()
    error = refused(runner, clip, bounds=[0, 48, 120])
    assert error.code == "contract_violation"
    assert "120" in error.message
    assert "96" in error.message
    assert runner.middle_frame_runs() == []


@needs_ffmpeg
def test_a_short_audio_track_does_not_cut_the_picture(tmp_path: Path) -> None:
    clip = render(
        tmp_path / "input",
        "-f", "lavfi", "-i", "testsrc=duration=6:size=160x90:rate=24",
        "-f", "lavfi", "-i", "sine=duration=1",
        "-c:v", "libx264", "-c:a", "aac",
    )  # fmt: skip

    outcome = scene_frames.handle(job(), {"video": clip}, ProcessRunner())

    assert outcome.data["durationSec"] == 6.0
    normalized = outcome.files[0].body
    assert isinstance(normalized, Path)
    counted = subprocess.run(
        [
            "ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0",
            "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0", str(normalized),
        ],
        check=True,
        capture_output=True,
    )  # fmt: skip
    assert counted.stdout.decode().strip() == "144"


@needs_ffmpeg
def test_a_long_audio_track_is_cut_to_the_picture(tmp_path: Path) -> None:
    clip = render(
        tmp_path / "input",
        "-f", "lavfi", "-i", "testsrc=duration=4:size=160x90:rate=24",
        "-f", "lavfi", "-i", "sine=duration=9",
        "-c:v", "libx264", "-c:a", "aac",
    )  # fmt: skip

    assert scene_frames.handle(job(), {"video": clip}, ProcessRunner()).data["durationSec"] == 4.0


@pytest.mark.parametrize("threshold", [0.5, 0.0001, 0.9999, 1 / 3])
def test_thresholds_inside_the_open_range_are_accepted(threshold: float) -> None:
    assert scene_frames._threshold({"sceneThreshold": threshold}) == threshold


def test_a_missing_or_null_threshold_means_the_default() -> None:
    assert scene_frames._threshold({}) == scene_frames._threshold({"sceneThreshold": None}) == 0.3
