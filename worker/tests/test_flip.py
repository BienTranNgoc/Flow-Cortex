"""The flip capability: a left-to-right mirror that keeps the audio, and what it refuses."""

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
from flowcortex_worker.handlers import flip
from flowcortex_worker.runtime import ProcessKilled, ProcessRunner

needs_ffmpeg = pytest.mark.skipif(
    shutil.which("ffmpeg") is None, reason="ffmpeg is installed in the worker image"
)

HLS_PLAYLIST = b"#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:1.0,\nsegment.ts\n#EXT-X-ENDLIST\n"


class RecordingRunner(ProcessRunner):
    """Runs everything for real, except an ffmpeg call `fail_when` picks, which exits 1."""

    def __init__(self, fail_when: Callable[[list[str]], bool] = lambda argv: False) -> None:
        super().__init__()
        self.argvs: list[list[str]] = []
        self._fail_when = fail_when

    def run(self, argv: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess[bytes]:
        self.argvs.append(argv)
        if argv[0] == "ffmpeg" and self._fail_when(argv):
            return subprocess.CompletedProcess(argv, 1, b"", b"Error: simulated muxer failure\n")
        return super().run(argv, cwd)

    @property
    def encodes(self) -> list[list[str]]:
        return [argv for argv in self.argvs if argv[0] == "ffmpeg"]


def job() -> MediaJob:
    return MediaJob.model_validate(job_body())


def make_clip(path: Path, *, audio: bool = True) -> Path:
    """2 s, 320x180: white left half, black right half, an AAC sine when `audio`."""
    command = [
        "ffmpeg", "-v", "error", "-y",
        "-f", "lavfi", "-i", "color=white:size=160x180:rate=25:duration=2",
        "-f", "lavfi", "-i", "color=black:size=160x180:rate=25:duration=2",
    ]  # fmt: skip
    if audio:
        command += ["-f", "lavfi", "-i", "sine=duration=2"]
    command += ["-filter_complex", "[0][1]hstack[v]", "-map", "[v]"]
    if audio:
        command += ["-map", "2:a", "-c:a", "aac"]
    subprocess.run(
        [*command, "-c:v", "libx264", "-pix_fmt", "yuv420p", "-f", "mp4", str(path)], check=True
    )
    return path


def half_luma(path: Path, *, left: bool) -> float:
    """Mean luma of one half of the first frame."""
    x = 0 if left else 160
    raw = subprocess.run(
        [
            "ffmpeg", "-v", "error", "-i", str(path), "-frames:v", "1",
            "-vf", f"crop=160:180:{x}:0,format=gray", "-f", "rawvideo", "-",
        ],
        check=True, capture_output=True,
    ).stdout  # fmt: skip
    return sum(raw) / len(raw)


def stream_codecs(path: Path) -> dict[str, str]:
    out = subprocess.run(
        [
            "ffprobe", "-v", "error", "-show_entries", "stream=codec_type,codec_name",
            "-of", "json", str(path),
        ],
        check=True, capture_output=True,
    )  # fmt: skip
    return {s["codec_type"]: s["codec_name"] for s in json.loads(out.stdout)["streams"]}


def refused(call: Callable[[], object]) -> PermanentError:
    with pytest.raises(PermanentError) as caught:
        call()
    return caught.value


def run_flip(inputs: dict[str, Path], runner: ProcessRunner) -> Any:
    return flip.handle(job(), inputs, runner)


def test_the_input_key_must_be_video() -> None:
    runner = RecordingRunner()

    error = refused(lambda: run_flip({"image": Path("/nonexistent")}, runner))

    assert error.code == "contract_violation"
    assert "video" in error.message
    assert runner.argvs == []


def test_no_second_input_is_accepted() -> None:
    error = refused(
        lambda: run_flip({"video": Path("/nonexistent"), "extra": Path("/x")}, RecordingRunner())
    )

    assert error.code == "contract_violation"
    assert "video" in error.message


@needs_ffmpeg
def test_a_playlist_saved_without_an_extension_is_probe_failed_before_any_decode(
    tmp_path: Path,
) -> None:
    playlist = tmp_path / "input"
    playlist.write_bytes(HLS_PLAYLIST)
    runner = RecordingRunner()

    error = refused(lambda: run_flip({"video": playlist}, runner))

    assert error.code == "probe_failed"
    assert runner.encodes == []


@needs_ffmpeg
def test_random_bytes_are_probe_failed_naming_ffprobe(tmp_path: Path) -> None:
    noise = tmp_path / "input"
    noise.write_bytes(bytes(range(256)) * 16)

    error = refused(lambda: run_flip({"video": noise}, RecordingRunner()))

    assert error.code == "probe_failed"
    assert "ffprobe" in error.message


@needs_ffmpeg
def test_the_left_half_becomes_the_right_half_and_the_audio_codec_is_kept(
    tmp_path: Path,
) -> None:
    clip = make_clip(tmp_path / "input")
    runner = RecordingRunner()

    outcome = run_flip({"video": clip}, runner)

    made = tmp_path / "flip.mp4"
    assert [(f.name, f.mime, f.body) for f in outcome.files] == [("flip.mp4", "video/mp4", made)]
    assert half_luma(made, left=True) < 40
    assert half_luma(made, left=False) > 200
    assert stream_codecs(made) == {"video": "h264", "audio": "aac"}
    assert outcome.data["flip"] == "hflip"
    assert (outcome.data["width"], outcome.data["height"]) == (320, 180)
    assert abs(outcome.data["durationMs"] - 2000) <= 100
    assert set(outcome.data) == {"width", "height", "durationMs", "flip"}
    [encode] = runner.encodes
    assert encode[encode.index("-protocol_whitelist") + 1] == "file"
    assert encode[encode.index("-c:a") + 1] == "copy"
    assert encode[encode.index("-map") + 1] == "0:0"
    assert "0:a:0?" in encode


@needs_ffmpeg
def test_a_clip_without_audio_is_flipped_without_an_audio_stream(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input", audio=False)

    run_flip({"video": clip}, RecordingRunner())

    assert stream_codecs(tmp_path / "flip.mp4") == {"video": "h264"}


@needs_ffmpeg
def test_audio_that_cannot_be_copied_is_encoded_as_aac(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input")
    runner = RecordingRunner(lambda argv: "copy" in argv)

    outcome = run_flip({"video": clip}, runner)

    first, second = runner.encodes
    assert first[first.index("-c:a") + 1] == "copy"
    audio_at = second.index("-c:a")
    assert second[audio_at : audio_at + 4] == ["-c:a", "aac", "-b:a", "128k"]
    assert outcome.data["flip"] == "hflip"
    assert stream_codecs(tmp_path / "flip.mp4")["audio"] == "aac"


@needs_ffmpeg
def test_both_encodes_failing_is_probe_failed_naming_ffmpeg_and_its_last_line(
    tmp_path: Path,
) -> None:
    clip = make_clip(tmp_path / "input")
    runner = RecordingRunner(lambda argv: True)

    error = refused(lambda: run_flip({"video": clip}, runner))

    assert error.code == "probe_failed"
    assert "ffmpeg" in error.message
    assert "simulated muxer failure" in error.message
    assert len(runner.encodes) == 2


@needs_ffmpeg
def test_a_kill_during_the_encode_propagates_without_a_retry(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input")

    class Killed(RecordingRunner):
        def run(
            self, argv: list[str], cwd: Path | None = None
        ) -> subprocess.CompletedProcess[bytes]:
            if argv[0] == "ffmpeg":
                self.argvs.append(argv)
                raise ProcessKilled("run_timeout")
            return super().run(argv, cwd)

    runner = Killed()
    with pytest.raises(ProcessKilled):
        run_flip({"video": clip}, runner)

    assert len(runner.encodes) == 1
