"""beats: decode limits, engine classification, result shaping from the child's JSON."""

from __future__ import annotations

import json
import subprocess
import sys
from collections.abc import Callable
from pathlib import Path

import pytest
from conftest import job_body
from test_separate_audio import FakeRunner, done, write_wav

from flowcortex_worker.contracts.media_job import MediaJob
from flowcortex_worker.errors import PermanentError, TransientError
from flowcortex_worker.handlers import beats
from flowcortex_worker.runtime import ProcessRunner

Script = Callable[[list[str]], "subprocess.CompletedProcess[bytes]"]

CLICK_TRACK = {
    "beats": [i * 0.5 for i in range(16)],
    "downbeats": [i * 2.0 for i in range(4)],
    "rms": [0.1] * 400,
    "frameMs": 23.2,
}


def make_job(*keys: str) -> MediaJob:
    # The capability enum does not name `beats` yet, so the job is built with a valid value and
    # relabelled; the handler never reads it.
    job = MediaJob.model_validate(
        job_body(
            capability="separate_audio",
            inputs=[
                {"key": key, "bucket": "uploads", "objectKey": f"u-1/{key}"}
                for key in (keys or ("media",))
            ],
        )
    )
    return job.model_copy(update={"capability": "beats"})


def happy(seconds: float = 20, raw: object = CLICK_TRACK, has_audio: bool = True) -> Script:
    def script(argv: list[str]) -> subprocess.CompletedProcess[bytes]:
        if argv[0] == "ffprobe":
            return subprocess.CompletedProcess(argv, 0, b"0\n" if has_audio else b"", b"")
        if argv[0] == "ffmpeg":
            write_wav(Path(argv[-1]), seconds, rate=22050)
        else:
            Path(argv[argv.index("--output") + 1]).write_text(json.dumps(raw))
        return done(argv)

    return script


def failing(code: int, err: str = "", seconds: float = 20) -> Script:
    def script(argv: list[str]) -> subprocess.CompletedProcess[bytes]:
        if argv[0] == sys.executable:
            return done(argv, code, err)
        return happy(seconds)(argv)

    return script


@pytest.fixture
def media(tmp_path: Path) -> dict[str, Path]:
    path = tmp_path / "media"
    path.write_bytes(b"x")
    return {"media": path}


def engine_calls(runner: ProcessRunner) -> list[list[str]]:
    assert isinstance(runner, FakeRunner)
    return [c for c in runner.calls if c[0] == sys.executable]


def test_the_result_is_shaped_from_the_engine_marks(media: dict[str, Path]) -> None:
    outcome = beats.handle(make_job(), media, FakeRunner(happy()))

    assert outcome.files == []
    assert outcome.data["v"] == 1
    assert outcome.data["bpm"] == 120.0
    assert outcome.data["beats"][:3] == [0, 500, 1000]
    assert outcome.data["downbeats"] == [0, 2000, 4000, 6000]
    assert outcome.data["durationMs"] == 20_000
    assert outcome.data["engine"] == "beat-this/1.1.0+final0"


def test_the_decode_is_mono_22khz_and_bounded(media: dict[str, Path]) -> None:
    runner = FakeRunner(happy())
    beats.handle(make_job(), media, runner)

    decode = next(c for c in runner.calls if c[0] == "ffmpeg")
    assert decode[decode.index("-ar") + 1] == "22050"
    assert decode[decode.index("-ac") + 1] == "1"
    assert decode[decode.index("-t") + 1] == "1201"
    assert "-nostdin" in decode
    assert decode[decode.index("-protocol_whitelist") + 1] == "file"


def test_the_audio_input_wins_over_the_bound_upload(tmp_path: Path) -> None:
    audio, upload = tmp_path / "audio", tmp_path / "media"
    audio.write_bytes(b"a")
    upload.write_bytes(b"m")
    runner = FakeRunner(happy())

    beats.handle(make_job("media", "audio"), {"media": upload, "audio": audio}, runner)

    decode = next(c for c in runner.calls if c[0] == "ffmpeg")
    assert decode[decode.index("-i") + 1] == str(audio)


def test_audio_beyond_the_limit_names_it_and_skips_the_engine(media: dict[str, Path]) -> None:
    runner = FakeRunner(happy(seconds=1201))
    with pytest.raises(PermanentError) as refused:
        beats.handle(make_job(), media, runner)

    assert refused.value.code == "duration_exceeded"
    assert "1200" in refused.value.message
    assert engine_calls(runner) == []


def test_audio_at_the_limit_is_accepted(media: dict[str, Path]) -> None:
    runner = FakeRunner(happy(seconds=1200))

    beats.handle(make_job(), media, runner)

    assert len(engine_calls(runner)) == 1


def test_audio_under_two_seconds_has_no_beats_and_skips_the_engine(
    media: dict[str, Path],
) -> None:
    runner = FakeRunner(happy(seconds=1.5))
    with pytest.raises(PermanentError) as refused:
        beats.handle(make_job(), media, runner)

    assert refused.value.code == "no_beats_detected"
    assert engine_calls(runner) == []


def test_a_video_without_sound_is_refused_before_the_engine(media: dict[str, Path]) -> None:
    runner = FakeRunner(happy(has_audio=False))
    with pytest.raises(PermanentError) as refused:
        beats.handle(make_job(), media, runner)

    assert refused.value.code == "no_audio_stream"
    assert engine_calls(runner) == []


def test_an_engine_that_cannot_load_names_the_engine(media: dict[str, Path]) -> None:
    with pytest.raises(PermanentError) as refused:
        beats.handle(make_job(), media, FakeRunner(failing(3, "no checkpoint\n")))

    assert refused.value.code == "beat_detection_failed"
    assert "beat-this/1.1.0+final0" in refused.value.message


def test_an_engine_failure_carries_the_last_stderr_line(media: dict[str, Path]) -> None:
    with pytest.raises(PermanentError) as refused:
        beats.handle(make_job(), media, FakeRunner(failing(1, "warming up\nbad frame\n")))

    assert refused.value.code == "beat_detection_failed"
    assert "bad frame" in refused.value.message
    assert "warming up" not in refused.value.message


def test_an_engine_killed_by_the_kernel_is_transient(media: dict[str, Path]) -> None:
    with pytest.raises(TransientError):
        beats.handle(make_job(), media, FakeRunner(failing(-9)))


def test_too_few_beats_is_no_beats_detected(media: dict[str, Path]) -> None:
    raw = {**CLICK_TRACK, "beats": [0.0, 0.5, 1.0]}
    with pytest.raises(PermanentError) as refused:
        beats.handle(make_job(), media, FakeRunner(happy(raw=raw)))

    assert refused.value.code == "no_beats_detected"


def test_digital_silence_with_hallucinated_beats_is_no_beats_detected(
    media: dict[str, Path],
) -> None:
    raw = {**CLICK_TRACK, "rms": [0.0] * 400}
    with pytest.raises(PermanentError) as refused:
        beats.handle(make_job(), media, FakeRunner(happy(raw=raw)))

    assert refused.value.code == "no_beats_detected"


@pytest.mark.parametrize(
    "raw",
    [
        "not an object",
        {"beats": [0.5]},
        {**CLICK_TRACK, "frameMs": 0},
        {**CLICK_TRACK, "beats": "x"},
        {**CLICK_TRACK, "rms": [True]},
    ],
)
def test_an_unreadable_engine_result_is_beat_detection_failed(
    media: dict[str, Path], raw: object
) -> None:
    with pytest.raises(PermanentError) as refused:
        beats.handle(make_job(), media, FakeRunner(happy(raw=raw)))

    assert refused.value.code == "beat_detection_failed"


def test_an_engine_that_writes_nothing_is_beat_detection_failed(
    media: dict[str, Path],
) -> None:
    def script(argv: list[str]) -> subprocess.CompletedProcess[bytes]:
        if argv[0] == sys.executable:
            return done(argv)
        return happy()(argv)

    with pytest.raises(PermanentError) as refused:
        beats.handle(make_job(), media, FakeRunner(script))

    assert refused.value.code == "beat_detection_failed"
