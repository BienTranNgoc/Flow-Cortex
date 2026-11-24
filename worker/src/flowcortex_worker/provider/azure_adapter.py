"""Azure AI Speech REST TTS adapter with automatic fallback support to Edge TTS."""

from __future__ import annotations

import http.client
import io
import logging
import math
import os
import re
import urllib.error
import urllib.request
import wave
from dataclasses import dataclass
from xml.sax.saxutils import escape, quoteattr

from ..errors import PermanentError, TransientError
from .adapter import Abort, SpeechAdapter, SpeechArtifact, SpeechIdentity, SpeechRequest
from .edge_adapter import (
    _MAX_SPEECH_MS,
    _SOFT_FIT_CEILING_MS,
    _TARGET_FIT_MS,
    DEFAULT_EDGE_RATE,
    DEFAULT_EDGE_VOICE,
    _concat_aligned_wav,
    _to_wav,
    _valid_rate,
    _validate_wav,
    resolve_edge_voice,
)
from .vendor_jobs import WORKER_USER_AGENT

log = logging.getLogger("flowcortex_provider_worker.azure_speech")

PROVIDER_AZURE = "azure"
DEFAULT_AZURE_REGION = "japaneast"
DEFAULT_AZURE_TIMEOUT_SECONDS = 30.0
_OUTPUT_FORMAT = "riff-24khz-16bit-mono-pcm"
_MAX_WAV_BYTES = 20 * 1024 * 1024
_REGION_PATTERN = re.compile(r"^[a-z0-9-]+$")


class AzureFallbackError(PermanentError):
    """Azure auth/quota/endpoint error that should trigger fallback to Edge TTS."""


@dataclass(frozen=True)
class AzureSpeechConfig:
    api_key: str
    region: str = DEFAULT_AZURE_REGION
    voice: str = DEFAULT_EDGE_VOICE
    rate: str = DEFAULT_EDGE_RATE
    timeout_seconds: float = DEFAULT_AZURE_TIMEOUT_SECONDS

    @property
    def endpoint(self) -> str:
        return f"https://{self.region}.tts.speech.microsoft.com/cognitiveservices/v1"


@dataclass(frozen=True)
class AzureSpeechAdapter:
    config: AzureSpeechConfig

    def speech_identity(self) -> SpeechIdentity:
        return SpeechIdentity(PROVIDER_AZURE, self.config.voice, self.config.rate, 24_000)

    def generate_speech(self, request: SpeechRequest, abort: Abort) -> SpeechArtifact:
        if not request.script.strip():
            raise PermanentError("provider_request_failed", "speech script is blank")
        effective_voice = resolve_edge_voice(request, self.config.voice)
        abort.check()
        if request.mc_hook_script and request.product_close_script:
            mc_wav = self._synthesize_wav(
                request.mc_hook_script.strip(), effective_voice, self.config.rate, abort
            )
            sp_wav = self._synthesize_wav(
                request.product_close_script.strip(), effective_voice, self.config.rate, abort
            )
            mc_ms = _validate_wav(mc_wav)
            sp_ms = _validate_wav(sp_wav)
            if 5_000 < mc_ms <= 5_750:
                mc_wav = _to_wav(mc_wav, abort, atempo=min(2.0, mc_ms / 4_850))
                mc_ms = _validate_wav(mc_wav)
            if mc_ms > 5_000:
                raise PermanentError(
                    "provider_request_failed",
                    f"MC hook speech is {mc_ms} ms, exceeding the 5000 ms scene limit",
                )
            if 5_000 < sp_ms <= 5_750:
                sp_wav = _to_wav(sp_wav, abort, atempo=min(2.0, sp_ms / 4_850))
                sp_ms = _validate_wav(sp_wav)
            if sp_ms > 5_000:
                raise PermanentError(
                    "provider_request_failed",
                    f"Product close speech is {sp_ms} ms, exceeding the 5000 ms scene limit",
                )
            wav = _concat_aligned_wav(mc_wav, sp_wav, abort)
            duration_ms = _validate_wav(wav)
            return SpeechArtifact(
                body=wav,
                duration_ms=duration_ms,
                provider=PROVIDER_AZURE,
                voice=effective_voice,
                speed=self.config.rate,
            )

        wav = self._synthesize_wav(request.script.strip(), effective_voice, self.config.rate, abort)
        duration_ms = _validate_wav(wav)
        if _MAX_SPEECH_MS < duration_ms <= _SOFT_FIT_CEILING_MS:
            abort.check()
            wav = _to_wav(wav, abort, atempo=min(2.0, duration_ms / _TARGET_FIT_MS))
            duration_ms = _validate_wav(wav)
        return SpeechArtifact(
            body=wav,
            duration_ms=duration_ms,
            provider=PROVIDER_AZURE,
            voice=effective_voice,
            speed=self.config.rate,
        )

    def _synthesize_wav(self, text: str, voice: str, rate: str, abort: Abort) -> bytes:
        abort.check()
        ssml = build_ssml(text, voice, rate).encode("utf-8")
        req = urllib.request.Request(
            self.config.endpoint,
            data=ssml,
            method="POST",
            headers={
                "Ocp-Apim-Subscription-Key": self.config.api_key,
                "Content-Type": "application/ssml+xml",
                "X-Microsoft-OutputFormat": _OUTPUT_FORMAT,
                "User-Agent": WORKER_USER_AGENT,
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=self.config.timeout_seconds) as resp:
                body = resp.read(_MAX_WAV_BYTES + 1)
                headers = getattr(resp, "headers", None)
                raw_len = headers.get("Content-Length") if headers is not None else None
        except urllib.error.HTTPError as exc:
            if exc.code in (408, 429) or 500 <= exc.code < 600:
                raise TransientError(f"Azure Speech HTTP {exc.code}") from exc
            if exc.code in (401, 403, 404):
                raise AzureFallbackError(
                    "provider_request_failed",
                    f"Azure Speech rejected credentials or endpoint with HTTP {exc.code}",
                ) from exc
            raise PermanentError(
                "provider_request_failed",
                f"Azure Speech rejected request with HTTP {exc.code}",
            ) from exc
        except (urllib.error.URLError, http.client.HTTPException, TimeoutError, OSError) as exc:
            raise TransientError(f"Azure Speech connection failed: {type(exc).__name__}") from exc
        abort.check()
        if raw_len is not None:
            try:
                expected_len = int(raw_len)
            except ValueError:
                expected_len = None
            if expected_len is not None and len(body) < expected_len:
                raise TransientError(
                    f"Azure Speech response truncated ({len(body)}/{expected_len} bytes)"
                )
        if not body or len(body) > _MAX_WAV_BYTES:
            raise TransientError("Azure Speech returned an empty or oversized audio response")
        _verify_complete_wav(body)
        return bytes(body)


def _verify_complete_wav(body: bytes) -> None:
    try:
        with wave.open(io.BytesIO(body), "rb") as audio:
            frames = audio.getnframes()
            expected_pcm = frames * audio.getnchannels() * audio.getsampwidth()
            actual_pcm = len(audio.readframes(frames))
    except (EOFError, wave.Error) as exc:
        raise TransientError("Azure Speech returned a corrupt or incomplete WAV response") from exc
    if frames <= 0 or actual_pcm < expected_pcm:
        raise TransientError(
            f"Azure Speech returned truncated WAV PCM ({actual_pcm}/{expected_pcm} bytes)"
        )


@dataclass(frozen=True)
class FallbackSpeechAdapter:
    """Calls primary speech adapter and falls back on infrastructure/quota/auth failures."""

    primary: SpeechAdapter
    fallback: SpeechAdapter

    def speech_identity(self) -> SpeechIdentity:
        return self.primary.speech_identity()

    def fallback_speech_identity(self) -> SpeechIdentity:
        return self.fallback.speech_identity()

    def generate_speech(self, request: SpeechRequest, abort: Abort) -> SpeechArtifact:
        try:
            return self.primary.generate_speech(request, abort)
        except (TransientError, AzureFallbackError) as exc:
            log.warning(
                "speech_provider_fallback",
                extra={
                    "primaryProvider": self.primary.speech_identity().provider,
                    "fallbackProvider": self.fallback.speech_identity().provider,
                    "reason": str(exc),
                },
            )
            abort.check()
            return self.fallback.generate_speech(request, abort)


def build_ssml(text: str, voice: str, rate: str) -> str:
    safe_voice = quoteattr(voice)
    safe_rate = quoteattr(rate)
    safe_text = escape(text)
    return (
        "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='vi-VN'>"
        f"<voice xml:lang='vi-VN' name={safe_voice}>"
        f"<prosody rate={safe_rate}>{safe_text}</prosody>"
        "</voice></speak>"
    )


def azure_config_from_env() -> AzureSpeechConfig:
    api_key = (
        os.environ.get("AZURE_SPEECH_KEY", "").strip()
        or os.environ.get("SPEECH_PROVIDER_API_KEY", "").strip()
    )
    if not api_key:
        raise RuntimeError(
            "SPEECH_PROVIDER=azure requires AZURE_SPEECH_KEY or SPEECH_PROVIDER_API_KEY"
        )
    region = os.environ.get("AZURE_SPEECH_REGION", DEFAULT_AZURE_REGION).strip().lower()
    if not region or not _REGION_PATTERN.match(region):
        raise RuntimeError("AZURE_SPEECH_REGION must contain only lowercase letters, digits or '-'")
    voice = os.environ.get("AZURE_SPEECH_VOICE", DEFAULT_EDGE_VOICE).strip()
    rate = os.environ.get("AZURE_SPEECH_RATE", DEFAULT_EDGE_RATE).strip()
    try:
        timeout_seconds = float(
            os.environ.get("FLOWCORTEX_PROVIDER_TIMEOUT_SECONDS", DEFAULT_AZURE_TIMEOUT_SECONDS)
        )
    except ValueError as exc:
        raise RuntimeError("FLOWCORTEX_PROVIDER_TIMEOUT_SECONDS must be a number") from exc
    if not voice:
        raise RuntimeError("AZURE_SPEECH_VOICE must not be blank")
    if not _valid_rate(rate):
        raise RuntimeError("AZURE_SPEECH_RATE must match +N% or -N%")
    if not math.isfinite(timeout_seconds) or timeout_seconds <= 0:
        raise RuntimeError("FLOWCORTEX_PROVIDER_TIMEOUT_SECONDS must be a finite positive number")
    return AzureSpeechConfig(
        api_key=api_key,
        region=region,
        voice=voice,
        rate=rate,
        timeout_seconds=timeout_seconds,
    )
