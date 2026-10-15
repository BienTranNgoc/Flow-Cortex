from __future__ import annotations

import importlib
import io
import json
import wave
from unittest.mock import MagicMock
from uuid import UUID

import pytest

from flowcortex_worker.errors import PermanentError, TransientError
from flowcortex_worker.provider import edge_adapter
from flowcortex_worker.provider.adapter import SpeechRequest
from flowcortex_worker.provider.capabilities import ExecutionContext, execute
from flowcortex_worker.provider.contracts.provider_job import ProviderJob

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


def test_edge_speech_produces_valid_wav_and_safe_manifest(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def download(script: str, config: edge_adapter.EdgeConfig) -> bytes:
        assert script == "Nội dung bí mật"
        assert config.voice == edge_adapter.DEFAULT_EDGE_VOICE
        return b"mp3"

    monkeypatch.setattr(edge_adapter, "_run_download", download)
    monkeypatch.setattr(edge_adapter, "_to_wav", lambda body: _wav())
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
    def download(script: str, config: edge_adapter.EdgeConfig) -> bytes:
        return b"mp3"

    monkeypatch.setattr(edge_adapter, "_run_download", download)
    monkeypatch.setattr(edge_adapter, "_to_wav", lambda body: _wav())

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
    def download(script: str, config: edge_adapter.EdgeConfig) -> bytes:
        raise TimeoutError

    monkeypatch.setattr(edge_adapter, "_run_download", download)

    with pytest.raises(TransientError):
        edge_adapter.EdgeSpeechAdapter(edge_adapter.EdgeConfig()).generate_speech(
            SpeechRequest("Đặt mua", REVISION_ID)
        )


def test_handshake_failure_logs_only_status(monkeypatch: pytest.MonkeyPatch) -> None:
    def download(script: str, config: edge_adapter.EdgeConfig) -> bytes:
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
        edge_adapter.EdgeSpeechAdapter(edge_adapter.EdgeConfig()).generate_speech(
            SpeechRequest("Đặt mua", REVISION_ID)
        )

    assert str(raised.value) == "Edge TTS handshake failed with HTTP 403"


def test_invalid_audio_is_permanent(monkeypatch: pytest.MonkeyPatch) -> None:
    def download(script: str, config: edge_adapter.EdgeConfig) -> bytes:
        return b"not-mp3"

    monkeypatch.setattr(edge_adapter, "_run_download", download)
    monkeypatch.setattr(
        edge_adapter,
        "_to_wav",
        lambda body: (_ for _ in ()).throw(
            PermanentError("provider_response_invalid", "invalid audio")
        ),
    )

    with pytest.raises(PermanentError) as raised:
        edge_adapter.EdgeSpeechAdapter(edge_adapter.EdgeConfig()).generate_speech(
            SpeechRequest("Đặt mua", REVISION_ID)
        )

    assert raised.value.code == "provider_response_invalid"


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
    adapter = provider_main._build_adapter()

    assert isinstance(adapter.speech_adapter, edge_adapter.EdgeSpeechAdapter)
    assert adapter.speech_adapter.config.voice == edge_adapter.DEFAULT_EDGE_VOICE
