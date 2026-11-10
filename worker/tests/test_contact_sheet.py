"""The contact_sheet capability: parameter checks, the frame budget and the stored sheet."""

from __future__ import annotations

import io
import shutil
import subprocess
from pathlib import Path
from typing import Any

import pytest
from conftest import job_body
from PIL import Image

from flowcortex_worker.contracts.media_job import MediaJob
from flowcortex_worker.errors import PermanentError
from flowcortex_worker.handlers import contact_sheet
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


def job(**params: Any) -> MediaJob:
    return MediaJob.model_validate(job_body(params=params))


def make_video(path: Path, *, size: str, seconds: int, rate: int, audio: bool = False) -> Path:
    command = [
        "ffmpeg", "-v", "error", "-y",
        "-f", "lavfi", "-i", f"testsrc=duration={seconds}:size={size}:rate={rate}",
    ]  # fmt: skip
    if audio:
        command += ["-f", "lavfi", "-i", f"sine=duration={seconds}", "-shortest"]
    subprocess.run(
        [*command, "-c:v", "libx264", "-pix_fmt", "yuv420p", "-f", "mp4", str(path)], check=True
    )
    return path


def refused(**params: Any) -> PermanentError:
    with pytest.raises(PermanentError) as caught:
        contact_sheet.handle(job(**params), {"video": Path("/nonexistent")}, ProcessRunner())
    return caught.value


def test_input_keys_are_exactly_video() -> None:
    with pytest.raises(PermanentError) as caught:
        contact_sheet.handle(
            job(stepMs=500, maxFrames=4), {"media": Path("/nonexistent")}, ProcessRunner()
        )
    assert caught.value.code == "contract_violation"
    assert "video" in caught.value.message


@pytest.mark.parametrize("step", [0, "500", True, 1.5, None])
def test_step_must_be_a_whole_number_above_zero(step: Any) -> None:
    error = refused(stepMs=step, maxFrames=4)
    assert error.code == "contract_violation"
    assert "stepMs" in error.message


@pytest.mark.parametrize("frames", [0, 61, "30", True, None])
def test_max_frames_is_bounded(frames: Any) -> None:
    error = refused(stepMs=500, maxFrames=frames)
    assert error.code == "contract_violation"
    assert "maxFrames" in error.message


@needs_ffmpeg
def test_ceiling_of_sixty_is_accepted(tmp_path: Path) -> None:
    clip = make_video(tmp_path / "input", size="64x64", seconds=1, rate=5)
    outcome = contact_sheet.handle(job(stepMs=500, maxFrames=60), {"video": clip}, ProcessRunner())
    assert outcome.data["frames"] == 2


@needs_ffmpeg
def test_clip_over_the_frame_budget_is_refused_before_decoding(tmp_path: Path) -> None:
    clip = make_video(tmp_path / "input", size="360x640", seconds=3, rate=24)
    runner = RecordingRunner()
    with pytest.raises(PermanentError) as caught:
        contact_sheet.handle(job(stepMs=500, maxFrames=4), {"video": clip}, runner)
    assert caught.value.code == "duration_exceeded"
    assert "6" in caught.value.message
    assert "4" in caught.value.message
    assert [argv[0] for argv in runner.argvs] == ["ffprobe"]


@needs_ffmpeg
def test_portrait_clip_becomes_a_near_square_labelled_sheet(tmp_path: Path) -> None:
    clip = make_video(tmp_path / "input", size="496x864", seconds=15, rate=24)
    outcome = contact_sheet.handle(job(stepMs=500, maxFrames=30), {"video": clip}, ProcessRunner())

    assert outcome.data == {
        "frames": 30,
        "stepMs": 500,
        "cols": 8,
        "rows": 4,
        "tileWidth": 206,
        "tileHeight": 360,
        "width": 1648,
        "height": 1440,
    }
    [sheet] = outcome.files
    assert (sheet.name, sheet.mime) == ("sheet.jpg", "image/jpeg")
    assert isinstance(sheet.body, Path)
    with Image.open(io.BytesIO(sheet.body.read_bytes())) as image:
        assert image.size == (1648, 1440)
        # The 30 tiles fill 6 cells of the last row; the two after them stay black.
        gray = image.convert("L")
        empty_cell = gray.crop((7 * 206 + 20, 3 * 360 + 40, 8 * 206 - 20, 4 * 360 - 20))
        assert max(empty_cell.tobytes()) <= 8
        # The label box at the top left of the first tile is dark with white text in it.
        assert max(gray.crop((0, 0, 60, 28)).tobytes()) >= 200


@needs_ffmpeg
def test_audio_only_input_is_probe_failed(tmp_path: Path) -> None:
    source = tmp_path / "input"
    subprocess.run(
        [
            "ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "sine=duration=1",
            "-c:a", "aac", "-f", "mp4", str(source),
        ],
        check=True,
    )  # fmt: skip
    with pytest.raises(PermanentError) as caught:
        contact_sheet.handle(job(stepMs=500, maxFrames=4), {"video": source}, ProcessRunner())
    assert caught.value.code == "probe_failed"
