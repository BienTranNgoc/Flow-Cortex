"""`detect_shots` finds hard cuts inside a source window."""

from __future__ import annotations

import shutil
import subprocess
from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest
from conftest import job_body

from flowcortex_worker.contracts.media_job import MediaJob
from flowcortex_worker.errors import PermanentError
from flowcortex_worker.handlers import detect_shots
from flowcortex_worker.runtime import ProcessRunner

needs_ffmpeg = pytest.mark.skipif(
    shutil.which("ffmpeg") is None, reason="ffmpeg is installed in the worker image"
)

GOOD: dict[str, Any] = {"srcInMs": 0, "srcOutMs": 3000, "threshold": 10}


def job(**change: Any) -> MediaJob:
    return MediaJob.model_validate(job_body(params={**GOOD, **change}))


def refused(call: Callable[[], object]) -> PermanentError:
    with pytest.raises(PermanentError) as caught:
        call()
    return caught.value


def ffmpeg(*args: str) -> None:
    subprocess.run(["ffmpeg", "-v", "error", "-y", *args], check=True)


@pytest.fixture(scope="module")
def blocks(tmp_path_factory: pytest.TempPathFactory) -> Path:
    path = tmp_path_factory.mktemp("blocks") / "input"
    ffmpeg(
        "-f", "lavfi", "-i", "color=c=black:s=320x240:r=25:d=1",
        "-f", "lavfi", "-i", "color=c=gray:s=320x240:r=25:d=1",
        "-f", "lavfi", "-i", "color=c=white:s=320x240:r=25:d=1",
        "-filter_complex", "concat=n=3:v=1:a=0",
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-f", "mp4", str(path),
    )  # fmt: skip
    return path


@pytest.mark.parametrize(
    ("change", "field"),
    [
        ({"srcInMs": -1}, "srcInMs"),
        ({"srcOutMs": 0}, "srcOutMs"),
        ({"threshold": 101}, "threshold"),
        ({"threshold": -1}, "threshold"),
        ({"threshold": True}, "threshold"),
        ({"threshold": float("nan")}, "threshold"),
        ({"srcInMs": "0"}, "srcInMs"),
        ({"srcOutMs": None}, "srcOutMs"),
    ],
)
def test_bad_params_are_contract_violations(
    change: dict[str, Any], field: str, tmp_path: Path
) -> None:
    error = refused(
        lambda: detect_shots.handle(job(**change), {"video": tmp_path}, ProcessRunner())
    )

    assert error.code == "contract_violation"
    assert field in error.message


def test_missing_window_end_is_a_contract_violation(tmp_path: Path) -> None:
    body = MediaJob.model_validate(job_body(params={"srcInMs": 0, "threshold": 10}))

    error = refused(lambda: detect_shots.handle(body, {"video": tmp_path}, ProcessRunner()))

    assert error.code == "contract_violation"
    assert "srcOutMs" in error.message


def test_input_keys_must_be_exactly_video(tmp_path: Path) -> None:
    error = refused(lambda: detect_shots.handle(job(), {"media": tmp_path}, ProcessRunner()))

    assert error.code == "contract_violation"
    assert "video" in error.message


@needs_ffmpeg
def test_cuts_between_colour_blocks(blocks: Path) -> None:
    outcome = detect_shots.handle(job(), {"video": blocks}, ProcessRunner())

    data = outcome.data
    assert outcome.files == []
    assert len(data["sourceCutsMs"]) == 2
    assert abs(data["sourceCutsMs"][0] - 1000) <= 40
    assert abs(data["sourceCutsMs"][1] - 2000) <= 40
    detector = data["detector"]
    assert len(detector["scores"]) == 2
    assert (detector["filter"], detector["threshold"]) == ("scdet", 10.0)
    assert (detector["srcInMs"], detector["srcOutMs"]) == (0, 3000)
    assert isinstance(detector["ffmpegVersion"], str) and detector["ffmpegVersion"]


@needs_ffmpeg
def test_window_offset_reports_source_time(blocks: Path) -> None:
    outcome = detect_shots.handle(job(srcInMs=1500), {"video": blocks}, ProcessRunner())

    [cut] = outcome.data["sourceCutsMs"]
    assert abs(cut - 2000) <= 40


@needs_ffmpeg
def test_one_continuous_shot_has_no_cut(tmp_path: Path) -> None:
    path = tmp_path / "input"
    ffmpeg(
        "-f", "lavfi", "-i", "testsrc=s=320x240:r=25:d=3",
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-f", "mp4", str(path),
    )  # fmt: skip

    data = detect_shots.handle(job(), {"video": path}, ProcessRunner()).data

    assert data["sourceCutsMs"] == []
    assert data["detector"]["scores"] == []


@needs_ffmpeg
def test_audio_only_input_is_probe_failed(tmp_path: Path) -> None:
    path = tmp_path / "input"
    ffmpeg("-f", "lavfi", "-i", "sine=duration=2", "-c:a", "aac", "-f", "mp4", str(path))

    error = refused(lambda: detect_shots.handle(job(), {"video": path}, ProcessRunner()))

    assert error.code == "probe_failed"


@needs_ffmpeg
def test_garbage_input_is_probe_failed(tmp_path: Path) -> None:
    path = tmp_path / "input"
    path.write_bytes(b"not a video" * 100)

    error = refused(lambda: detect_shots.handle(job(), {"video": path}, ProcessRunner()))

    assert error.code == "probe_failed"
    assert "ffprobe" in error.message


def test_a_stream_without_an_average_frame_rate_is_still_scanned(tmp_path: Path) -> None:
    class Scripted(ProcessRunner):
        def run(self, argv: list[str]) -> subprocess.CompletedProcess[bytes]:
            body = (
                b'{"format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2","duration":"3.0"},'
                b'"streams":[{"index":0,"codec_type":"video","width":320,"height":240,'
                b'"avg_frame_rate":"0/0"}]}'
            )
            stdout = body if argv[0] == "ffprobe" else b""
            return subprocess.CompletedProcess(argv, 0, stdout, b"")

    data = detect_shots.handle(job(), {"video": tmp_path / "input"}, Scripted()).data

    assert data["sourceCutsMs"] == []


class ThreeSeconds(ProcessRunner):
    """Answers ffprobe with a 3 s clip and records the ffmpeg scans."""

    def __init__(self) -> None:
        super().__init__()
        self.scans: list[list[str]] = []

    def run(self, argv: list[str]) -> subprocess.CompletedProcess[bytes]:
        if argv[0] == "ffprobe":
            body = (
                b'{"format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2","duration":"3.0"},'
                b'"streams":[{"index":0,"codec_type":"video","width":320,"height":240}]}'
            )
            return subprocess.CompletedProcess(argv, 0, body, b"")
        if "-vf" in argv:
            self.scans.append(argv)
        return subprocess.CompletedProcess(argv, 0, b"", b"")


@pytest.mark.parametrize("src_in", [3000, 5000])
def test_window_at_or_after_the_end_is_a_contract_violation(src_in: int, tmp_path: Path) -> None:
    runner = ThreeSeconds()

    error = refused(
        lambda: detect_shots.handle(
            job(srcInMs=src_in, srcOutMs=9000), {"video": tmp_path / "input"}, runner
        )
    )

    assert error.code == "contract_violation"
    assert "srcInMs" in error.message
    assert "durationMs 3000" in error.message
    assert runner.scans == []


@pytest.mark.parametrize("src_out", [9000, 1e300])
def test_window_past_the_end_is_clamped_and_reported_as_scanned(
    src_out: float, tmp_path: Path
) -> None:
    runner = ThreeSeconds()

    data = detect_shots.handle(
        job(srcInMs=1000, srcOutMs=src_out), {"video": tmp_path / "input"}, runner
    ).data

    [scan] = runner.scans
    assert scan[scan.index("-ss") + 1] == "1.000"
    assert scan[scan.index("-t") + 1] == "2.000"
    assert data["detector"]["srcOutMs"] == 3000


def test_window_inside_the_clip_is_reported_as_given(tmp_path: Path) -> None:
    data = detect_shots.handle(
        job(srcInMs=500, srcOutMs=2500), {"video": tmp_path / "input"}, ThreeSeconds()
    ).data

    assert (data["detector"]["srcInMs"], data["detector"]["srcOutMs"]) == (500, 2500)


def test_window_shorter_than_a_millisecond_is_a_contract_violation(tmp_path: Path) -> None:
    runner = ThreeSeconds()

    error = refused(
        lambda: detect_shots.handle(
            job(srcInMs=0.2, srcOutMs=0.7), {"video": tmp_path / "input"}, runner
        )
    )

    assert error.code == "contract_violation"
    assert "srcOutMs" in error.message
    assert runner.scans == []


def test_an_integer_beyond_the_float_range_is_a_contract_violation(tmp_path: Path) -> None:
    error = refused(
        lambda: detect_shots.handle(
            job(srcOutMs=10**400), {"video": tmp_path / "input"}, ThreeSeconds()
        )
    )

    assert error.code == "contract_violation"
    assert "srcOutMs" in error.message
