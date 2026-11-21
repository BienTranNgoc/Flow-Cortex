"""separate_audio: input validation, decode limits, engine classification, stem encoding."""

from __future__ import annotations

import struct
import subprocess
import sys
from collections.abc import Callable
from pathlib import Path

import pytest
from conftest import job_body

from flowcortex_worker.contracts.media_job import MediaJob
from flowcortex_worker.errors import PermanentError, TransientError
from flowcortex_worker.handlers import separate_audio
from flowcortex_worker.runtime import ProcessRunner

Script = Callable[[list[str]], "subprocess.CompletedProcess[bytes]"]


def make_job(*keys: str, **params: object) -> MediaJob:
    return MediaJob.model_validate(
        job_body(
            capability="separate_audio",
            inputs=[
                {"key": key, "bucket": "uploads", "objectKey": f"u-1/{key}"}
                for key in (keys or ("media",))
            ],
            params=params,
        )
    )


def write_wav(path: Path, seconds: float, rate: int = 44100) -> None:
    """A WAV whose header claims `seconds` of stereo audio; the body is a sparse hole."""
    data = int(seconds * rate) * 4
    header = (
        b"RIFF"
        + struct.pack("<I", 36 + data)
        + b"WAVEfmt "
        + struct.pack("<IHHIIHH", 16, 1, 2, rate, rate * 4, 4, 16)
        + b"data"
        + struct.pack("<I", data)
    )
    with path.open("wb") as handle:
        handle.write(header)
        handle.truncate(len(header) + data)


def done(argv: list[str], code: int = 0, err: str = "") -> subprocess.CompletedProcess[bytes]:
    return subprocess.CompletedProcess(argv, code, b"", err.encode())


class FakeRunner(ProcessRunner):
    """Records every argv and answers it from `script`."""

    def __init__(self, script: Script) -> None:
        super().__init__()
        self.calls: list[list[str]] = []
        self._script = script

    def run(self, argv: list[str]) -> subprocess.CompletedProcess[bytes]:
        self.calls.append(argv)
        return self._script(argv)


def happy(seconds: float = 90, has_audio: bool = True) -> Script:
    """Behaves like the real tools: creates the files each one would."""

    def script(argv: list[str]) -> subprocess.CompletedProcess[bytes]:
        if argv[0] == "ffprobe":
            return subprocess.CompletedProcess(argv, 0, b"0\n" if has_audio else b"", b"")
        if argv[0] == "ffmpeg" and argv[-1].endswith(".wav"):
            write_wav(Path(argv[-1]), seconds)
        elif argv[0] == "ffmpeg":
            Path(argv[-1]).write_bytes(b"aac")
        else:
            out = Path(argv[argv.index("--output-dir") + 1])
            (out / "vocals.wav").write_bytes(b"v")
            (out / "no_vocals.wav").write_bytes(b"n")
        return done(argv)

    return script


def failing(tool: Callable[[list[str]], bool], code: int, err: str = "") -> Script:
    def script(argv: list[str]) -> subprocess.CompletedProcess[bytes]:
        return done(argv, code, err) if tool(argv) else happy()(argv)

    return script


def is_engine(argv: list[str]) -> bool:
    return argv[0] == sys.executable


def is_decode(argv: list[str]) -> bool:
    return argv[0] == "ffmpeg" and "-t" in argv


def is_encode(argv: list[str]) -> bool:
    return argv[0] == "ffmpeg" and argv[-1].endswith(".m4a")


@pytest.fixture
def media(tmp_path: Path) -> dict[str, Path]:
    path = tmp_path / "media"
    path.write_bytes(b"x")
    return {"media": path}


def engine_calls(runner: FakeRunner) -> list[list[str]]:
    return [c for c in runner.calls if is_engine(c)]


def test_an_unknown_stem_fails_before_any_process(media: dict[str, Path]) -> None:
    runner = FakeRunner(happy())
    with pytest.raises(PermanentError) as refused:
        separate_audio.handle(make_job(stems="drums"), media, runner)

    assert refused.value.code == "contract_violation"
    for allowed in ("vocals", "no_vocals", "both"):
        assert allowed in refused.value.message
    assert runner.calls == []


def test_inputs_without_audio_or_media_name_both_keys(tmp_path: Path) -> None:
    runner = FakeRunner(happy())
    with pytest.raises(PermanentError) as refused:
        separate_audio.handle(make_job("video"), {"video": tmp_path / "video"}, runner)

    assert refused.value.code == "contract_violation"
    assert "audio" in refused.value.message and "media" in refused.value.message
    assert runner.calls == []


def test_the_audio_input_wins_over_the_bound_upload(tmp_path: Path) -> None:
    audio, upload = tmp_path / "audio", tmp_path / "media"
    audio.write_bytes(b"a")
    upload.write_bytes(b"m")
    runner = FakeRunner(happy())

    separate_audio.handle(make_job("media", "audio"), {"media": upload, "audio": audio}, runner)

    decode = next(c for c in runner.calls if is_decode(c))
    assert decode[decode.index("-i") + 1] == str(audio)


def test_a_video_without_sound_is_refused_before_the_engine(media: dict[str, Path]) -> None:
    runner = FakeRunner(happy(has_audio=False))
    with pytest.raises(PermanentError) as refused:
        separate_audio.handle(make_job(), media, runner)

    assert refused.value.code == "no_audio_stream"
    assert engine_calls(runner) == []


def test_audio_beyond_the_limit_names_it_and_skips_the_engine(media: dict[str, Path]) -> None:
    runner = FakeRunner(happy(seconds=660))
    with pytest.raises(PermanentError) as refused:
        separate_audio.handle(make_job(), media, runner)

    assert refused.value.code == "duration_exceeded"
    assert "600" in refused.value.message
    assert engine_calls(runner) == []


def test_audio_at_the_limit_runs_the_engine(media: dict[str, Path]) -> None:
    runner = FakeRunner(happy(seconds=600))

    separate_audio.handle(make_job(), media, runner)

    assert len(engine_calls(runner)) == 1


def test_audio_without_a_sample_is_no_audio_stream(media: dict[str, Path]) -> None:
    runner = FakeRunner(happy(seconds=0))
    with pytest.raises(PermanentError) as refused:
        separate_audio.handle(make_job(), media, runner)

    assert refused.value.code == "no_audio_stream"
    assert engine_calls(runner) == []


def test_a_corrupt_file_is_audio_extraction_failed(media: dict[str, Path]) -> None:
    err = f"{media['media']}: Invalid data found\n"
    with pytest.raises(PermanentError) as refused:
        separate_audio.handle(make_job(), media, FakeRunner(failing(is_decode, 1, err)))

    assert refused.value.code == "audio_extraction_failed"
    assert "Invalid data found" in refused.value.message
    assert str(media["media"].parent) not in refused.value.message


def test_a_decoder_killed_by_the_system_is_transient(media: dict[str, Path]) -> None:
    with pytest.raises(TransientError):
        separate_audio.handle(make_job(), media, FakeRunner(failing(is_decode, -9)))


def test_an_engine_that_cannot_load_names_the_model(media: dict[str, Path]) -> None:
    with pytest.raises(PermanentError) as refused:
        separate_audio.handle(make_job(), media, FakeRunner(failing(is_engine, 3)))

    assert refused.value.code == "separation_failed"
    assert "htdemucs" in refused.value.message


def test_an_engine_failure_carries_the_last_stderr_line(media: dict[str, Path]) -> None:
    script = failing(is_engine, 1, "warming up\nout of memory\n")
    with pytest.raises(PermanentError) as refused:
        separate_audio.handle(make_job(), media, FakeRunner(script))

    assert refused.value.code == "separation_failed"
    assert "out of memory" in refused.value.message
    assert "warming up" not in refused.value.message


def test_an_engine_killed_by_the_kernel_is_transient(media: dict[str, Path]) -> None:
    with pytest.raises(TransientError):
        separate_audio.handle(make_job(), media, FakeRunner(failing(is_engine, -9)))


def test_an_engine_that_writes_no_stem_fails_separation(media: dict[str, Path]) -> None:
    with pytest.raises(PermanentError) as refused:
        separate_audio.handle(make_job(), media, FakeRunner(failing(is_engine, 0)))

    assert refused.value.code == "separation_failed"


def test_the_default_is_the_background_stem_only(media: dict[str, Path]) -> None:
    outcome = separate_audio.handle(make_job(), media, FakeRunner(happy()))

    assert [(f.name, f.mime) for f in outcome.files] == [("no_vocals.m4a", "audio/mp4")]
    assert outcome.data == {"stems": ["no_vocals"], "durationMs": 90_000}


def test_both_stems_come_back_in_a_fixed_order(media: dict[str, Path]) -> None:
    outcome = separate_audio.handle(make_job(stems="both"), media, FakeRunner(happy(30)))

    assert [f.name for f in outcome.files] == ["vocals.m4a", "no_vocals.m4a"]
    assert outcome.data == {"stems": ["vocals", "no_vocals"], "durationMs": 30_000}


def test_a_stem_encode_failure_is_separation_failed(media: dict[str, Path]) -> None:
    with pytest.raises(PermanentError) as refused:
        separate_audio.handle(
            make_job(), media, FakeRunner(failing(is_encode, 1, "encoder error\n"))
        )

    assert refused.value.code == "separation_failed"
    assert "encoder error" in refused.value.message


def test_the_decode_is_sandboxed_and_bounded(media: dict[str, Path]) -> None:
    runner = FakeRunner(happy())
    separate_audio.handle(make_job(), media, runner)

    decode = next(c for c in runner.calls if is_decode(c))
    assert "-nostdin" in decode
    assert decode[decode.index("-protocol_whitelist") + 1] == "file"
    assert decode[decode.index("-t") + 1] == "601"
    assert decode[decode.index("-ar") + 1] == "44100"
    assert decode[decode.index("-ac") + 1] == "2"
    encode = next(c for c in runner.calls if is_encode(c))
    assert encode[encode.index("-b:a") + 1] == "128k"
