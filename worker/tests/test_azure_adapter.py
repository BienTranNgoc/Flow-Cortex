"""Unit tests for Azure AI Speech adapter and Edge TTS fallback."""

from __future__ import annotations

import io
import urllib.error
import urllib.request
import wave
from uuid import UUID

import pytest

from flowcortex_worker.errors import PermanentError
from flowcortex_worker.provider.adapter import SpeechArtifact, SpeechIdentity, SpeechRequest
from flowcortex_worker.provider.azure_adapter import (
    PROVIDER_AZURE,
    AzureSpeechAdapter,
    AzureSpeechConfig,
    FallbackSpeechAdapter,
    azure_config_from_env,
    build_ssml,
)
from flowcortex_worker.provider.capabilities import ExecutionContext, execute
from flowcortex_worker.provider.contracts.provider_job import ProviderJob
from flowcortex_worker.provider.contracts.provider_speech_result import ProviderSpeechData

REV_ID = UUID("a71ea3ba-d72a-4c2c-985c-3a7fd98853c4")


def _make_wav(duration_ms: int) -> bytes:
    frames = int(24_000 * duration_ms / 1000)
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(24_000)
        wav.writeframes(b"\x00\x00" * frames)
    return buf.getvalue()


class _FakeResp:
    def __init__(self, body: bytes) -> None:
        self._body = body

    def read(self, _n: int = -1) -> bytes:
        return self._body

    def __enter__(self) -> _FakeResp:
        return self

    def __exit__(self, *args: object) -> None:
        return None


class _NoAbort:
    def check(self) -> None:
        return None


def test_build_ssml_escapes_xml_and_sets_voice() -> None:
    ssml = build_ssml("Giảm <50%> & tặng quà!", "vi-VN-HoaiMyNeural", "+10%")
    assert 'name="vi-VN-HoaiMyNeural"' in ssml
    assert 'rate="+10%"' in ssml
    assert "Giảm &lt;50%&gt; &amp; tặng quà!" in ssml


def test_azure_adapter_selects_male_and_female_voices(monkeypatch: pytest.MonkeyPatch) -> None:
    captured_ssml: list[str] = []
    wav_3s = _make_wav(3_000)

    def fake_urlopen(req: urllib.request.Request, timeout: float = 0) -> _FakeResp:
        assert req.full_url == "https://japaneast.tts.speech.microsoft.com/cognitiveservices/v1"
        assert req.get_header("Ocp-apim-subscription-key") == "az-key"
        assert req.get_header("X-microsoft-outputformat") == "riff-24khz-16bit-mono-pcm"
        captured_ssml.append(req.data.decode("utf-8"))  # type: ignore[union-attr]
        return _FakeResp(wav_3s)

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)
    adapter = AzureSpeechAdapter(AzureSpeechConfig(api_key="az-key", region="japaneast"))

    female = adapter.generate_speech(
        SpeechRequest("Chào bạn", REV_ID, voice_gender="female"),
        _NoAbort(),  # type: ignore[arg-type]
    )
    male = adapter.generate_speech(
        SpeechRequest("Chào bạn", REV_ID, voice_gender="male"),
        _NoAbort(),  # type: ignore[arg-type]
    )
    assert female.provider == PROVIDER_AZURE
    assert female.voice == "vi-VN-HoaiMyNeural"
    assert female.duration_ms == 3_000
    assert male.voice == "vi-VN-NamMinhNeural"
    assert 'name="vi-VN-HoaiMyNeural"' in captured_ssml[0]
    assert 'name="vi-VN-NamMinhNeural"' in captured_ssml[1]


def test_azure_adapter_rejects_oversized_mc_scene2(monkeypatch: pytest.MonkeyPatch) -> None:
    wavs = iter([_make_wav(4_000), _make_wav(6_000)])
    monkeypatch.setattr(
        urllib.request, "urlopen", lambda req, timeout=0: _FakeResp(next(wavs))
    )
    adapter = AzureSpeechAdapter(AzureSpeechConfig(api_key="az-key"))
    with pytest.raises(PermanentError, match="Product close speech is 6000 ms"):
        adapter.generate_speech(
            SpeechRequest(
                "Hook. Close.",
                REV_ID,
                mc_hook_script="Hook.",
                product_close_script="Close too long.",
            ),
            _NoAbort(),  # type: ignore[arg-type]
        )


def test_fallback_adapter_falls_back_to_edge_on_http_429_and_401(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    wav_3s = _make_wav(3_000)

    class _FakeEdge:
        def speech_identity(self) -> SpeechIdentity:
            return SpeechIdentity("edge", "vi-VN-NamMinhNeural", "+10%", 24_000)

        def generate_speech(self, request: SpeechRequest, abort: object) -> SpeechArtifact:
            voice = (
                "vi-VN-HoaiMyNeural"
                if request.voice_gender == "female"
                else "vi-VN-NamMinhNeural"
            )
            return SpeechArtifact(wav_3s, 3_000, "edge", voice, "+10%")

    for status in (429, 503, 401):
        def _fail(req: urllib.request.Request, timeout: float = 0, code: int = status) -> _FakeResp:
            raise urllib.error.HTTPError(req.full_url, code, "err", {}, None)  # type: ignore[arg-type]

        monkeypatch.setattr(urllib.request, "urlopen", _fail)
        wrapper = FallbackSpeechAdapter(
            primary=AzureSpeechAdapter(AzureSpeechConfig(api_key="az-key")),
            fallback=_FakeEdge(),  # type: ignore[arg-type]
        )
        out = wrapper.generate_speech(
            SpeechRequest("Xin chào", REV_ID, voice_gender="female"),
            _NoAbort(),  # type: ignore[arg-type]
        )
        assert out.provider == "edge"
        assert out.voice == "vi-VN-HoaiMyNeural"


def test_fallback_adapter_does_not_swallow_content_permanent_error(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    edge_called = False

    class _NeverEdge:
        def speech_identity(self) -> SpeechIdentity:
            return SpeechIdentity("edge", "vi-VN-NamMinhNeural", "+10%", 24_000)

        def generate_speech(self, request: SpeechRequest, abort: object) -> SpeechArtifact:
            nonlocal edge_called
            edge_called = True
            return SpeechArtifact(_make_wav(2_000), 2_000, "edge", "vi-VN-NamMinhNeural", "+10%")

    wrapper = FallbackSpeechAdapter(
        primary=AzureSpeechAdapter(AzureSpeechConfig(api_key="az-key")),
        fallback=_NeverEdge(),  # type: ignore[arg-type]
    )
    with pytest.raises(PermanentError, match="speech script is blank"):
        wrapper.generate_speech(SpeechRequest("   ", REV_ID), _NoAbort())  # type: ignore[arg-type]
    assert not edge_called


def test_capabilities_accepts_azure_primary_and_edge_fallback(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    wav_3s = _make_wav(3_000)

    class _EmptyStorage:
        def head_object(self, bucket: str, key: str) -> None:
            return None

        def fetch_bytes(self, bucket: str, key: str, abort: object) -> bytes:
            return b""

        def put(self, key: str, body: bytes, mime: str, abort: object) -> int:
            return len(body)

        def presigned_get_url(self, bucket: str, key: str, *, expires_seconds: int = 300) -> str:
            return f"https://example.test/{key}"

    class _FakeEdge:
        def speech_identity(self) -> SpeechIdentity:
            return SpeechIdentity("edge", "vi-VN-NamMinhNeural", "+10%", 24_000)

        def generate_speech(self, request: SpeechRequest, abort: object) -> SpeechArtifact:
            return SpeechArtifact(wav_3s, 3_000, "edge", "vi-VN-HoaiMyNeural", "+10%")

    def _fail_500(req: urllib.request.Request, timeout: float = 0) -> _FakeResp:
        raise urllib.error.HTTPError(req.full_url, 500, "boom", {}, None)  # type: ignore[arg-type]

    monkeypatch.setattr(urllib.request, "urlopen", _fail_500)
    wrapper = FallbackSpeechAdapter(
        primary=AzureSpeechAdapter(AzureSpeechConfig(api_key="az-key")),
        fallback=_FakeEdge(),  # type: ignore[arg-type]
    )
    job = ProviderJob.model_validate(
        {
            "schemaVersion": "1",
            "jobId": str(REV_ID),
            "runId": str(REV_ID),
            "correlationId": str(REV_ID),
            "stepOrder": 3,
            "capability": "generate_speech",
            "params": {
                "profile": "vi_voice_v1",
                "payload": {
                    "script": "Sản phẩm siêu đẹp!",
                    "sourceRevisionId": str(REV_ID),
                    "voiceGender": "female",
                },
            },
            "outputPrefix": f"runs/{REV_ID}/steps/3/attempts/1/",
            "attempt": 1,
            "maxRuntimeSeconds": 60,
            "issuedAt": "2026-11-23T00:00:00Z",
        }
    )
    ctx = ExecutionContext(
        storage=_EmptyStorage(),
        bucket="b",
        output_prefix=f"runs/{REV_ID}/steps/3/attempts/1/",
        abort=_NoAbort(),
    )
    outcome = execute(job, wrapper, ctx)
    validated = ProviderSpeechData.model_validate(outcome.data)
    assert validated.provider == "edge"
    assert validated.voice == "vi-VN-HoaiMyNeural"


def test_build_speech_adapter_azure_with_and_without_fallback(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from flowcortex_worker.provider.__main__ import _build_speech_adapter

    monkeypatch.setenv("AZURE_SPEECH_KEY", "az-secret")
    monkeypatch.setenv("AZURE_SPEECH_REGION", "japaneast")
    monkeypatch.setenv("SPEECH_FALLBACK_PROVIDER", "edge")
    with_fb = _build_speech_adapter("azure", "", "", "")
    assert isinstance(with_fb, FallbackSpeechAdapter)
    assert isinstance(with_fb.primary, AzureSpeechAdapter)

    monkeypatch.setenv("SPEECH_FALLBACK_PROVIDER", "none")
    no_fb = _build_speech_adapter("azure", "", "", "")
    assert isinstance(no_fb, AzureSpeechAdapter)

    cfg = azure_config_from_env()
    assert cfg.endpoint == "https://japaneast.tts.speech.microsoft.com/cognitiveservices/v1"
