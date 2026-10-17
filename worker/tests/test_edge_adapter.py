from __future__ import annotations

import asyncio
import importlib
import io
import json
import subprocess
import threading
import time
import wave
from pathlib import Path
from typing import Any
from unittest.mock import MagicMock
from uuid import UUID

import pytest
from conftest import captured_logs
from edge_tts import exceptions as edge_errors

from flowcortex_worker.errors import PermanentError, TransientError
from flowcortex_worker.provider import edge_adapter
from flowcortex_worker.provider.adapter import FixtureProviderAdapter, SpeechRequest
from flowcortex_worker.provider.capabilities import ExecutionContext, execute
from flowcortex_worker.provider.contracts.provider_job import ProductAdPayload, ProviderJob
from flowcortex_worker.runtime import ProcessKilled, ProcessRunner
from flowcortex_worker.storage import TEMP_PREFIX

REVISION_ID = UUID("a71ea3ba-d72a-4c2c-985c-3a7fd98853c4")
RUN_ID = "0b7e1d2c-3a4b-4c5d-8e6f-7a8b9c0d1e2f"
BUCKET = "flowcortex-media"


def _empty_context() -> ExecutionContext:
    storage = MagicMock()
    storage.head_object.return_value = None
    storage.fetch_bytes.return_value = b""
    return ExecutionContext(
        storage=storage,
        bucket=BUCKET,
        output_prefix=f"runs/{RUN_ID}/steps/3/attempts/1/",
        abort=MagicMock(),
    )


def _wav(duration_ms: int = 1000) -> bytes:
    output = io.BytesIO()
    with wave.open(output, "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(24_000)
        audio.writeframes(b"\0\0" * (duration_ms * 24))
    return output.getvalue()


def _job(script: str = "Đặt mua ngay") -> ProviderJob:
    return ProviderJob.model_validate({
        "schemaVersion": "1",
        "jobId": "5f0c7c1e-8f7e-4a53-9a38-2f6d6f3f9c11",
        "runId": RUN_ID,
        "correlationId": RUN_ID,
        "stepOrder": 3,
        "capability": "generate_speech",
        "params": {
            "profile": "vi_voice_v1",
            "payload": {"script": script, "sourceRevisionId": str(REVISION_ID)},
        },
        "outputPrefix": f"runs/{RUN_ID}/steps/3/attempts/1/",
        "attempt": 1,
        "maxRuntimeSeconds": 120,
        "issuedAt": "2026-10-14T00:00:00Z",
    })


def _speak(abort: Any = None) -> None:
    edge_adapter.EdgeSpeechAdapter(edge_adapter.EdgeConfig()).generate_speech(
        SpeechRequest("Đặt mua", REVISION_ID), abort if abort is not None else MagicMock()
    )


def test_edge_speech_produces_valid_wav_and_safe_manifest(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def download(script: str, config: edge_adapter.EdgeConfig, abort: Any) -> bytes:
        assert script == "Nội dung bí mật"
        assert config.voice == edge_adapter.DEFAULT_EDGE_VOICE
        return b"mp3"

    monkeypatch.setattr(edge_adapter, "_run_download", download)
    monkeypatch.setattr(edge_adapter, "_to_wav", lambda body, abort: _wav())
    adapter = edge_adapter.EdgeSpeechAdapter(edge_adapter.EdgeConfig())

    outcome = execute(_job("Nội dung bí mật"), adapter, _empty_context())

    assert outcome.data["provider"] == "edge"
    assert outcome.data["sourceRevisionId"] == str(REVISION_ID)
    assert len(outcome.data["idempotencyKey"]) == 64
    assert [item.name for item in outcome.files] == ["speech.wav", "manifest.json"]
    manifest = json.loads(outcome.files[1].body)
    assert manifest == outcome.data
    assert "Nội dung bí mật" not in outcome.files[1].body.decode()
    with wave.open(io.BytesIO(outcome.files[0].body), "rb") as audio:
        assert (audio.getnchannels(), audio.getsampwidth(), audio.getframerate()) == (1, 2, 24_000)


def test_idempotency_key_changes_with_voice(monkeypatch: pytest.MonkeyPatch) -> None:
    def download(script: str, config: edge_adapter.EdgeConfig, abort: Any) -> bytes:
        return b"mp3"

    monkeypatch.setattr(edge_adapter, "_run_download", download)
    monkeypatch.setattr(edge_adapter, "_to_wav", lambda body, abort: _wav())

    first = execute(
        _job(),
        edge_adapter.EdgeSpeechAdapter(edge_adapter.EdgeConfig(voice="vi-VN-HoaiMyNeural")),
        _empty_context(),
    )
    second = execute(
        _job(),
        edge_adapter.EdgeSpeechAdapter(edge_adapter.EdgeConfig(voice="vi-VN-NamMinhNeural")),
        _empty_context(),
    )

    assert first.data["idempotencyKey"] != second.data["idempotencyKey"]


def test_network_failure_is_transient(monkeypatch: pytest.MonkeyPatch) -> None:
    def download(script: str, config: edge_adapter.EdgeConfig, abort: Any) -> bytes:
        raise TimeoutError

    monkeypatch.setattr(edge_adapter, "_run_download", download)

    with pytest.raises(TransientError):
        _speak()


def test_handshake_failure_logs_only_status(monkeypatch: pytest.MonkeyPatch) -> None:
    def download(script: str, config: edge_adapter.EdgeConfig, abort: Any) -> bytes:
        from aiohttp import WSServerHandshakeError
        raise WSServerHandshakeError(
            request_info=None,  # type: ignore[arg-type]
            history=(),
            status=403,
            message="Invalid response status",
            headers=None,
        )

    monkeypatch.setattr(edge_adapter, "_run_download", download)

    with pytest.raises(TransientError, match="handshake failed with HTTP 403") as raised:
        _speak()

    assert str(raised.value) == "Edge TTS handshake failed with HTTP 403"


@pytest.mark.parametrize(
    "error",
    [
        edge_errors.WebSocketError("Đặt mua"),
        edge_errors.SkewAdjustmentError("Đặt mua"),
        RuntimeError("Đặt mua"),
    ],
    ids=["websocket-error", "skew-adjustment", "unknown-failure"],
)
def test_transport_and_unknown_failures_are_transient_and_omit_the_script(
    monkeypatch: pytest.MonkeyPatch, error: Exception
) -> None:
    def download(script: str, config: edge_adapter.EdgeConfig, abort: Any) -> bytes:
        raise error

    monkeypatch.setattr(edge_adapter, "_run_download", download)

    with pytest.raises(TransientError) as raised:
        _speak()

    assert type(error).__name__ in str(raised.value)
    assert "Đặt mua" not in str(raised.value)


@pytest.mark.parametrize(
    ("error", "code", "message"),
    [
        (
            edge_errors.NoAudioReceived("x"),
            "provider_response_invalid",
            "Edge TTS returned no audio",
        ),
        (
            edge_errors.UnexpectedResponse("x"),
            "provider_response_invalid",
            "Edge TTS returned an unexpected response",
        ),
        (
            edge_errors.UnknownResponse("x"),
            "provider_response_invalid",
            "Edge TTS returned an unknown response",
        ),
        (ValueError("x"), "provider_request_failed", "Edge TTS rejected the request"),
    ],
    ids=["no-audio", "unexpected-response", "unknown-response", "rejected-request"],
)
def test_response_failures_are_permanent_with_their_own_message(
    monkeypatch: pytest.MonkeyPatch, error: Exception, code: str, message: str
) -> None:
    def download(script: str, config: edge_adapter.EdgeConfig, abort: Any) -> bytes:
        raise error

    monkeypatch.setattr(edge_adapter, "_run_download", download)

    with pytest.raises(PermanentError) as raised:
        _speak()

    assert raised.value.code == code
    assert raised.value.message == message


def test_stream_without_audio_chunks_is_permanent(monkeypatch: pytest.MonkeyPatch) -> None:
    class SilentCommunicate:
        def __init__(self, *args: Any, **kwargs: Any) -> None:
            pass

        async def stream(self) -> Any:
            yield {"type": "WordBoundary"}

    monkeypatch.setattr("edge_tts.Communicate", SilentCommunicate)

    with pytest.raises(PermanentError) as raised:
        _speak()

    assert raised.value.message == "Edge TTS returned no audio"


def test_kill_during_download_stops_the_stream(monkeypatch: pytest.MonkeyPatch) -> None:
    streaming = threading.Event()

    async def stalled(script: str, config: edge_adapter.EdgeConfig) -> bytes:
        streaming.set()
        await asyncio.sleep(30)
        return b"mp3"

    monkeypatch.setattr(edge_adapter, "_download_mp3", stalled)
    runner = ProcessRunner()

    def kill_once_streaming() -> None:
        if streaming.wait(5):
            runner.kill("shutdown")

    threading.Thread(target=kill_once_streaming, daemon=True).start()
    began = time.monotonic()

    # ProcessKilled, not TransientError: the consumer settles a killed job by its kill reason.
    with pytest.raises(ProcessKilled, match="shutdown"):
        _speak(runner)

    assert time.monotonic() - began < 5


def test_killed_job_does_not_start_the_download(monkeypatch: pytest.MonkeyPatch) -> None:
    download = MagicMock()
    monkeypatch.setattr(edge_adapter, "_run_download", download)
    runner = ProcessRunner()
    runner.kill("shutdown")

    with pytest.raises(ProcessKilled):
        _speak(runner)

    download.assert_not_called()


def test_ffmpeg_runs_through_the_job_runner_in_a_job_temp_dir() -> None:
    abort = MagicMock()

    def run(argv: list[str]) -> subprocess.CompletedProcess[bytes]:
        output = Path(argv[-1])
        assert output.parent.name.startswith(TEMP_PREFIX)
        output.write_bytes(_wav())
        return subprocess.CompletedProcess(argv, 0, b"", b"")

    abort.run.side_effect = run

    assert edge_adapter._to_wav(b"mp3", abort) == _wav()

    [argv] = abort.run.call_args.args
    assert argv[0] == "ffmpeg"
    assert "-nostdin" in argv


def test_ffmpeg_failure_is_permanent() -> None:
    abort = MagicMock()
    abort.run.side_effect = lambda argv: subprocess.CompletedProcess(argv, 1, b"", b"bad input")

    with pytest.raises(PermanentError) as raised:
        edge_adapter._to_wav(b"mp3", abort)

    assert raised.value.code == "provider_response_invalid"


def test_invalid_audio_is_permanent(monkeypatch: pytest.MonkeyPatch) -> None:
    def download(script: str, config: edge_adapter.EdgeConfig, abort: Any) -> bytes:
        return b"not-mp3"

    monkeypatch.setattr(edge_adapter, "_run_download", download)
    monkeypatch.setattr(
        edge_adapter,
        "_to_wav",
        lambda body, abort: (_ for _ in ()).throw(
            PermanentError("provider_response_invalid", "invalid audio")
        ),
    )

    with pytest.raises(PermanentError) as raised:
        _speak()

    assert raised.value.code == "provider_response_invalid"


def test_edge_does_not_generate_scripts() -> None:
    payload = ProductAdPayload(productName="Bình", description="Giữ nhiệt", cta="Mua ngay")

    with pytest.raises(PermanentError) as raised:
        edge_adapter.EdgeSpeechAdapter(edge_adapter.EdgeConfig()).generate_script(payload)

    assert raised.value.code == "provider_capability_unavailable"


@pytest.mark.parametrize("rate", ["", "0%", "+101%", "fast"])
def test_invalid_rate_is_rejected(monkeypatch: pytest.MonkeyPatch, rate: str) -> None:
    monkeypatch.setenv("EDGE_TTS_RATE", rate)

    with pytest.raises(RuntimeError, match="EDGE_TTS_RATE"):
        edge_adapter.config_from_env()


def test_build_adapter_selects_edge_without_an_api_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("PROVIDER_ADAPTER", "edge")
    monkeypatch.delenv("SCRIPT_PROVIDER", raising=False)
    monkeypatch.delenv("SPEECH_PROVIDER", raising=False)
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)

    from flowcortex_worker.provider import __main__ as provider_main

    importlib.reload(provider_main)
    with captured_logs() as lines:
        adapter = provider_main._build_adapter()

    assert isinstance(adapter.speech_adapter, edge_adapter.EdgeSpeechAdapter)
    assert adapter.speech_adapter.config.voice == edge_adapter.DEFAULT_EDGE_VOICE
    assert isinstance(adapter.script_adapter, FixtureProviderAdapter)
    [warning] = [line for line in lines if line["level"] == "WARNING"]
    assert "fixture" in warning["message"]


def test_build_adapter_rejects_edge_as_explicit_script_provider(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("PROVIDER_ADAPTER", raising=False)
    monkeypatch.setenv("SCRIPT_PROVIDER", "edge")
    monkeypatch.setenv("SPEECH_PROVIDER", "edge")

    from flowcortex_worker.provider import __main__ as provider_main

    importlib.reload(provider_main)
    with pytest.raises(RuntimeError, match=r"SCRIPT_PROVIDER 'edge'.*fixture, openai$"):
        provider_main._build_adapter()
