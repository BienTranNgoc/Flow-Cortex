"""The extract_audio capability produces the canonical WAV for speech processing."""

from __future__ import annotations

import json
import subprocess
from pathlib import Path
from typing import Any, cast

import pytest
from conftest import (
    FakeConnection,
    FakeStorage,
    assert_valid,
    job_body,
    make_clip,
    published,
    run_consumer,
    settled,
)

from flowcortex_worker.contracts.media_job import MediaJob
from flowcortex_worker.errors import PermanentError
from flowcortex_worker.handlers import extract_audio
from flowcortex_worker.runtime import ProcessRunner


@pytest.fixture(scope="module")
def clip(tmp_path_factory: pytest.TempPathFactory) -> Path:
    return make_clip(tmp_path_factory.mktemp("extract-audio") / "clip.mp4")


def job() -> MediaJob:
    return MediaJob.model_validate(job_body(capability="extract_audio"))


def inspect_audio(path: Path) -> dict[str, Any]:
    completed = subprocess.run(
        [
            "ffprobe",
            "-v",
            "error",
            "-protocol_whitelist",
            "file",
            "-select_streams",
            "a:0",
            "-show_entries",
            "stream=codec_name,sample_rate,channels",
            "-of",
            "json",
            str(path),
        ],
        check=True,
        capture_output=True,
    )
    report: dict[str, Any] = json.loads(completed.stdout)
    return cast(dict[str, Any], report["streams"][0])


def test_video_audio_becomes_mono_16khz_pcm(clip: Path) -> None:
    outcome = extract_audio.handle(job(), {"media": clip}, ProcessRunner())

    assert outcome.data == {
        "sampleRate": 16000,
        "channels": 1,
        "codec": "pcm_s16le",
        "timelineOriginMs": 0,
    }
    [audio] = outcome.files
    assert (audio.name, audio.mime) == ("source-audio.wav", "audio/wav")
    assert isinstance(audio.body, Path)
    assert inspect_audio(audio.body) == {
        "codec_name": "pcm_s16le",
        "sample_rate": "16000",
        "channels": 1,
    }


def test_audio_only_input_is_supported(tmp_path: Path) -> None:
    source = tmp_path / "source.wav"
    subprocess.run(
        [
            "ffmpeg",
            "-v",
            "error",
            "-y",
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=440:duration=1",
            str(source),
        ],
        check=True,
    )

    outcome = extract_audio.handle(job(), {"media": source}, ProcessRunner())

    [audio] = outcome.files
    assert isinstance(audio.body, Path)
    assert inspect_audio(audio.body)["sample_rate"] == "16000"


def test_video_without_audio_fails_permanently(tmp_path: Path) -> None:
    source = tmp_path / "silent.mp4"
    subprocess.run(
        [
            "ffmpeg",
            "-v",
            "error",
            "-y",
            "-f",
            "lavfi",
            "-i",
            "testsrc=duration=1:size=64x64:rate=10",
            "-an",
            "-c:v",
            "libx264",
            "-pix_fmt",
            "yuv420p",
            str(source),
        ],
        check=True,
    )

    with pytest.raises(PermanentError) as failed:
        extract_audio.handle(job(), {"media": source}, ProcessRunner())

    assert failed.value.code == "audio_extraction_failed"
    assert "extractable audio" in failed.value.message


def test_invalid_media_fails_without_leaking_the_path(tmp_path: Path) -> None:
    source = tmp_path / "secret-name.mp4"
    source.write_text("not media")

    with pytest.raises(PermanentError) as failed:
        extract_audio.handle(job(), {"media": source}, ProcessRunner())

    assert failed.value.code == "audio_extraction_failed"
    assert str(source) not in failed.value.message


def test_missing_media_input_is_a_contract_violation() -> None:
    with pytest.raises(PermanentError) as failed:
        extract_audio.handle(job(), {}, ProcessRunner())

    assert failed.value.code == "contract_violation"


def test_consumer_stores_wav_at_the_step_key(clip: Path) -> None:
    conn = FakeConnection()
    body = job_body(capability="extract_audio", stepOrder=3)
    conn.deliver(body)
    storage = FakeStorage(content=clip.read_bytes())

    run_consumer(conn, storage=storage, until=settled, timeout=30)

    key = f"runs/{body['runId']}/steps/3/source-audio.wav"
    message = assert_valid(published(conn, "result")[0].body, "media-result")
    assert message["status"] == "completed"
    assert message["data"] == {
        "sampleRate": 16000,
        "channels": 1,
        "codec": "pcm_s16le",
        "timelineOriginMs": 0,
    }
    assert [output["objectKey"] for output in message["outputs"]] == [key]
    stored, mime = storage.puts[key]
    assert mime == "audio/wav"
    assert message["outputs"][0]["bytes"] == len(stored)
    assert stored.startswith(b"RIFF")
    assert conn.consume.acks == [1]


def test_consumer_answers_invalid_media_and_acks() -> None:
    conn = FakeConnection()
    conn.deliver(job_body(capability="extract_audio"))

    run_consumer(
        conn,
        storage=FakeStorage(content=b"not media"),
        until=settled,
        timeout=30,
    )

    message = assert_valid(published(conn, "result")[0].body, "media-result")
    assert message["error"] == {
        "code": "audio_extraction_failed",
        "message": "the input has no extractable audio stream",
        "retryable": False,
    }
    assert conn.consume.acks == [1]
