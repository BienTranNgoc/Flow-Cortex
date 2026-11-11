"""speakers: decode shape, limits, engine classification, turn shaping."""

from __future__ import annotations

import json
import struct
import subprocess
import sys
from collections.abc import Callable
from pathlib import Path

import pytest
from conftest import job_body

from flowcortex_worker.contracts.media_job import MediaJob
from flowcortex_worker.errors import PermanentError, TransientError
from flowcortex_worker.handlers import speakers
from flowcortex_worker.runtime import ProcessRunner

Script = Callable[[list[str]], "subprocess.CompletedProcess[bytes]"]
SEGMENTS = [[2.0, 3.5, 7], [0.0, 1.2, 4], [1.2001, 1.2003, 9]]


def make_job() -> MediaJob:
    return MediaJob.model_validate(job_body())


def write_wav(path: Path, seconds: float, rate: int = 16000) -> None:
    """A WAV whose header claims `seconds` of mono audio; the body is a sparse hole."""
    data = int(seconds * rate) * 2
    header = (
        b"RIFF"
        + struct.pack("<I", 36 + data)
        + b"WAVEfmt "
        + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16)
        + b"data"
        + struct.pack("<I", data)
    )
    with path.open("wb") as handle:
        handle.write(header)
        handle.truncate(len(header) + data)


def done(argv: list[str], code: int = 0, err: str = "") -> subprocess.CompletedProcess[bytes]:
    return subprocess.CompletedProcess(argv, code, b"", err.encode())


class FakeRunner(ProcessRunner):
    def __init__(self, script: Script) -> None:
        super().__init__()
        self.calls: list[list[str]] = []
        self._script = script

    def run(self, argv: list[str]) -> subprocess.CompletedProcess[bytes]:
        self.calls.append(argv)
        return self._script(argv)


def happy(seconds: float = 4, has_audio: bool = True, segments: object = SEGMENTS) -> Script:
    def script(argv: list[str]) -> subprocess.CompletedProcess[bytes]:
        if argv[0] == "ffprobe":
            return subprocess.CompletedProcess(argv, 0, b"0\n" if has_audio else b"", b"")
        if argv[0] == "ffmpeg":
            write_wav(Path(argv[-1]), seconds)
        else:
            Path(argv[argv.index("--output") + 1]).write_text(json.dumps(segments))
        return done(argv)

    return script


def is_engine(argv: list[str]) -> bool:
    return argv[0] == sys.executable


@pytest.fixture
def media(tmp_path: Path) -> dict[str, Path]:
    path = tmp_path / "media"
    path.write_bytes(b"x")
    return {"media": path}


def test_raw_labels_become_whole_millisecond_turns_without_files(
    media: dict[str, Path],
) -> None:
    outcome = speakers.handle(make_job(), media, FakeRunner(happy()))

    assert outcome.files == []
    assert outcome.data == {
        "turns": [
            {"fromMs": 0, "toMs": 1200, "label": 1},
            {"fromMs": 2000, "toMs": 3500, "label": 2},
        ],
        "count": 2,
        "durationMs": 4000,
        "separator": "3d-speaker/sherpa-onnx-1.13.8",
    }


def test_nobody_speaking_completes_with_no_turns(media: dict[str, Path]) -> None:
    outcome = speakers.handle(make_job(), media, FakeRunner(happy(segments=[])))

    assert outcome.data["turns"] == [] and outcome.data["count"] == 0


def test_the_engine_hears_16_khz_mono(media: dict[str, Path]) -> None:
    runner = FakeRunner(happy())
    speakers.handle(make_job(), media, runner)

    decode = next(c for c in runner.calls if c[0] == "ffmpeg")
    assert decode[decode.index("-ar") + 1] == "16000"
    assert decode[decode.index("-ac") + 1] == "1"
    assert decode[decode.index("-t") + 1] == "1801"


def test_the_audio_input_wins_over_the_bound_upload(tmp_path: Path) -> None:
    audio, upload = tmp_path / "audio", tmp_path / "media"
    audio.write_bytes(b"a")
    upload.write_bytes(b"m")
    runner = FakeRunner(happy())

    speakers.handle(make_job(), {"media": upload, "audio": audio}, runner)

    decode = next(c for c in runner.calls if c[0] == "ffmpeg")
    assert decode[decode.index("-i") + 1] == str(audio)


def test_a_video_without_sound_is_refused_before_the_engine(media: dict[str, Path]) -> None:
    runner = FakeRunner(happy(has_audio=False))
    with pytest.raises(PermanentError) as refused:
        speakers.handle(make_job(), media, runner)

    assert refused.value.code == "no_audio_stream"
    assert not any(is_engine(c) for c in runner.calls)


def test_audio_beyond_the_limit_names_it_and_skips_the_engine(media: dict[str, Path]) -> None:
    runner = FakeRunner(happy(seconds=1801))
    with pytest.raises(PermanentError) as refused:
        speakers.handle(make_job(), media, runner)

    assert refused.value.code == "duration_exceeded"
    assert "1800" in refused.value.message
    assert not any(is_engine(c) for c in runner.calls)


def test_audio_at_the_limit_runs_the_engine(media: dict[str, Path]) -> None:
    runner = FakeRunner(happy(seconds=1800))

    speakers.handle(make_job(), media, runner)

    assert sum(is_engine(c) for c in runner.calls) == 1


def failing(code: int, err: str = "") -> Script:
    def script(argv: list[str]) -> subprocess.CompletedProcess[bytes]:
        return done(argv, code, err) if is_engine(argv) else happy()(argv)

    return script


def test_an_engine_that_cannot_load_names_the_separator(media: dict[str, Path]) -> None:
    with pytest.raises(PermanentError) as refused:
        speakers.handle(make_job(), media, FakeRunner(failing(3, "model missing\n")))

    assert refused.value.code == "diarization_failed"
    assert "3d-speaker/sherpa-onnx-1.13.8" in refused.value.message
    assert "model missing" in refused.value.message


def test_an_engine_failure_carries_the_last_stderr_line(media: dict[str, Path]) -> None:
    with pytest.raises(PermanentError) as refused:
        speakers.handle(make_job(), media, FakeRunner(failing(1, "a\nboom\n")))

    assert refused.value.code == "diarization_failed"
    assert "boom" in refused.value.message


def test_an_engine_killed_by_the_system_is_transient(media: dict[str, Path]) -> None:
    with pytest.raises(TransientError):
        speakers.handle(make_job(), media, FakeRunner(failing(-9)))


@pytest.mark.parametrize("body", ["not json", '{"a": 1}', "[[1, 2]]", '[["a", 2, 1]]'])
def test_an_unreadable_engine_result_is_diarization_failed(
    media: dict[str, Path], body: str
) -> None:
    def script(argv: list[str]) -> subprocess.CompletedProcess[bytes]:
        if is_engine(argv):
            Path(argv[argv.index("--output") + 1]).write_text(body)
            return done(argv)
        return happy()(argv)

    with pytest.raises(PermanentError) as refused:
        speakers.handle(make_job(), media, FakeRunner(script))

    assert refused.value.code == "diarization_failed"
