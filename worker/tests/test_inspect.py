"""`inspect` measures black runs, freezes and scene cuts of one video input."""

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
from flowcortex_worker.handlers import inspect
from flowcortex_worker.runtime import ProcessKilled, ProcessRunner

needs_ffmpeg = pytest.mark.skipif(
    shutil.which("ffmpeg") is None, reason="ffmpeg is installed in the worker image"
)


class Recording(ProcessRunner):
    def __init__(self) -> None:
        super().__init__()
        self.calls: list[list[str]] = []

    def run(self, argv: list[str]) -> subprocess.CompletedProcess[bytes]:
        self.calls.append(argv)
        return super().run(argv)

    @property
    def decodes(self) -> list[list[str]]:
        return [argv for argv in self.calls if argv[0] == "ffmpeg"]


def job(**params: Any) -> MediaJob:
    return MediaJob.model_validate(job_body(params=params))


def refused(call: Callable[[], object]) -> PermanentError:
    with pytest.raises(PermanentError) as caught:
        call()
    return caught.value


def ffmpeg(*args: str) -> None:
    subprocess.run(["ffmpeg", "-v", "error", "-y", *args], check=True)


@pytest.fixture(scope="module")
def take(tmp_path_factory: pytest.TempPathFactory) -> Path:
    path = tmp_path_factory.mktemp("take") / "input"
    ffmpeg(
        "-f", "lavfi", "-i", "color=c=white:s=320x240:r=25:d=2",
        "-f", "lavfi", "-i", "color=c=black:s=320x240:r=25:d=1",
        "-f", "lavfi", "-i", "testsrc=s=320x240:r=25:d=3",
        "-filter_complex", "concat=n=3:v=1:a=0",
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-f", "mp4", str(path),
    )  # fmt: skip
    return path


@pytest.mark.parametrize("keys", [["media"], ["video", "extra"]])
def test_input_keys_must_be_exactly_video(keys: list[str], tmp_path: Path) -> None:
    inputs = {key: tmp_path / "x" for key in keys}

    error = refused(lambda: inspect.handle(job(), inputs, Recording()))

    assert error.code == "contract_violation"
    assert "video" in error.message


def test_faces_must_be_boolean(tmp_path: Path) -> None:
    error = refused(
        lambda: inspect.handle(job(faces="yes"), {"video": tmp_path / "x"}, Recording())
    )

    assert error.code == "contract_violation"
    assert "faces" in error.message


def test_faces_requested_is_refused_before_any_process(tmp_path: Path) -> None:
    processes = Recording()

    error = refused(lambda: inspect.handle(job(faces=True), {"video": tmp_path / "x"}, processes))

    assert error.code == "provider_capability_unavailable"
    assert "face" in error.message
    assert processes.calls == []


@needs_ffmpeg
def test_filter_chain_and_measurements_of_a_25_fps_take(take: Path) -> None:
    processes = Recording()

    outcome = inspect.handle(job(faces=False), {"video": take}, processes)

    data = outcome.data
    assert outcome.files == []
    assert data["checker"] == "video.inspect/1"
    assert data["hasVideo"] is True
    assert data["fps"] == 25.0
    assert (data["width"], data["height"]) == (320, 240)
    [black] = data["black"]
    assert abs(black["fromMs"] - 2000) <= 40 and abs(black["toMs"] - 3000) <= 40
    assert data["freeze"][0]["fromMs"] == 0
    assert any(abs(at - 2000) <= 80 for at in data["cutsMs"])
    assert any(abs(at - 3000) <= 80 for at in data["cutsMs"])
    [decode] = processes.decodes
    chain = decode[decode.index("-vf") + 1]
    assert "blackdetect=d=1/25:pic_th=0.98:pix_th=0.1" in chain
    assert "freezedetect=n=-60dB:d=2" in chain
    assert "scdet=threshold=10" in chain
    assert decode[decode.index("-protocol_whitelist") + 1] == "file"


@needs_ffmpeg
def test_audio_only_mp4_has_no_video_and_no_decode(tmp_path: Path) -> None:
    path = tmp_path / "input"
    ffmpeg("-f", "lavfi", "-i", "sine=duration=2", "-c:a", "aac", "-f", "mp4", str(path))
    processes = Recording()

    data = inspect.handle(job(), {"video": path}, processes).data

    assert data["hasVideo"] is False
    assert 1900 <= data["durationMs"] <= 2200
    assert (data["width"], data["height"], data["fps"]) == (None, None, None)
    assert (data["black"], data["freeze"], data["cutsMs"]) == ([], [], [])
    assert processes.decodes == []


@needs_ffmpeg
def test_mp3_is_refused_without_a_decode(tmp_path: Path) -> None:
    path = tmp_path / "input"
    ffmpeg("-f", "lavfi", "-i", "sine=duration=2", "-c:a", "libmp3lame", "-f", "mp3", str(path))
    processes = Recording()

    error = refused(lambda: inspect.handle(job(), {"video": path}, processes))

    assert error.code == "probe_failed"
    assert processes.decodes == []


@needs_ffmpeg
def test_playlist_is_refused_without_a_decode(tmp_path: Path) -> None:
    path = tmp_path / "input"
    path.write_text("#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:1,\nsegment.ts\n#EXT-X-ENDLIST\n")
    processes = Recording()

    error = refused(lambda: inspect.handle(job(), {"video": path}, processes))

    assert error.code == "probe_failed"
    assert processes.decodes == []


@needs_ffmpeg
def test_random_bytes_are_probe_failed(tmp_path: Path) -> None:
    path = tmp_path / "input"
    path.write_bytes(bytes(range(256)) * 16)

    error = refused(lambda: inspect.handle(job(), {"video": path}, Recording()))

    assert error.code == "probe_failed"
    assert "ffprobe" in error.message


@needs_ffmpeg
def test_a_kill_propagates(take: Path) -> None:
    processes = ProcessRunner()
    processes.kill("watchdog")

    with pytest.raises(ProcessKilled):
        inspect.handle(job(), {"video": take}, processes)


class Scripted(ProcessRunner):
    """Answers ffprobe with a fixed report and ffmpeg with `frames_text` in the metadata file."""

    def __init__(self, frames_text: str | None, rate: str = "25/1") -> None:
        super().__init__()
        self.frames_text = frames_text
        self.rate = rate

    def run(self, argv: list[str]) -> subprocess.CompletedProcess[bytes]:
        if argv[0] == "ffprobe":
            body = (
                '{"format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2","duration":"3.0"},'
                '"streams":[{"index":0,"codec_type":"video","width":320,"height":240,'
                f'"avg_frame_rate":"{self.rate}"}}]}}'
            )
            return subprocess.CompletedProcess(argv, 0, body.encode(), b"")
        if self.frames_text is not None:
            chain = argv[argv.index("-vf") + 1]
            Path(chain.rsplit("file=", 1)[1]).write_text(self.frames_text, encoding="utf-8")
        return subprocess.CompletedProcess(argv, 0, b"", b"")


@pytest.mark.parametrize("frames_text", [None, ""])
def test_no_decoded_frame_is_probe_failed(frames_text: str | None, tmp_path: Path) -> None:
    error = refused(
        lambda: inspect.handle(job(), {"video": tmp_path / "input"}, Scripted(frames_text))
    )

    assert error.code == "probe_failed"
    assert "no frame" in error.message


def test_black_runs_shorter_than_one_frame_are_dropped(tmp_path: Path) -> None:
    text = (
        "frame:0    pts:0       pts_time:0\n"
        "lavfi.black_start=1\n"
        "frame:1    pts:1       pts_time:1.039\n"
        "lavfi.black_end=1.039\n"
        "frame:2    pts:2       pts_time:2\n"
        "lavfi.black_start=2\n"
        "frame:3    pts:3       pts_time:2.04\n"
        "lavfi.black_end=2.04\n"
    )

    data = inspect.handle(job(), {"video": tmp_path / "input"}, Scripted(text)).data

    assert data["black"] == [{"fromMs": 2000, "toMs": 2040}]


def test_zero_frame_rate_is_probe_failed_in_inspect(tmp_path: Path) -> None:
    error = refused(
        lambda: inspect.handle(job(), {"video": tmp_path / "input"}, Scripted("", rate="0/0"))
    )

    assert error.code == "probe_failed"
    assert "avg_frame_rate" in error.message
