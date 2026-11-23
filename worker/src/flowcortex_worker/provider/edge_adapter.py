"""Edge Read Aloud speech adapter used by the demo provider runtime.

Demo use only: Edge TTS calls Microsoft's undocumented Read Aloud endpoint, which has no SLA, has
broken before and receives the approved script. Production speech needs a contracted provider.

The upstream service returns MP3. This boundary converts it to the stable WAV contract consumed by
media capabilities and never logs the script or generated audio.
"""

from __future__ import annotations

import asyncio
import io
import logging
import os
import random
import tempfile
import wave
from dataclasses import dataclass
from pathlib import Path

import edge_tts
from aiohttp import ClientError, WSServerHandshakeError
from edge_tts.exceptions import (
    NoAudioReceived,
    SkewAdjustmentError,
    UnexpectedResponse,
    UnknownResponse,
    WebSocketError,
)

from ..errors import PermanentError, TransientError
from ..storage import TEMP_PREFIX
from .adapter import Abort, SpeechArtifact, SpeechIdentity, SpeechRequest
from .contracts.provider_job import ProductAdPayload

log = logging.getLogger("flowcortex_provider_worker.edge")

PROVIDER_EDGE = "edge"
DEFAULT_EDGE_VOICE = "vi-VN-NamMinhNeural"
DEFAULT_EDGE_RATE = "+10%"
DEFAULT_EDGE_TIMEOUT_SECONDS = 60.0
_MAX_SPEECH_MS = 10_000
_SOFT_FIT_CEILING_MS = 11_500
_TARGET_FIT_MS = 9_850
_MAX_DOWNLOAD_ATTEMPTS = 4
_RETRY_DELAYS_SECONDS: tuple[float, ...] = (0.5, 1.0, 1.5)
_TRANSPORT_ERRORS = (
    WebSocketError,
    SkewAdjustmentError,
    TimeoutError,
    ConnectionError,
    OSError,
    ClientError,
)


@dataclass(frozen=True)
class EdgeConfig:
    voice: str = DEFAULT_EDGE_VOICE
    rate: str = DEFAULT_EDGE_RATE
    timeout_seconds: float = DEFAULT_EDGE_TIMEOUT_SECONDS


@dataclass(frozen=True)
class EdgeSpeechAdapter:
    config: EdgeConfig

    def generate_script(
        self, payload: ProductAdPayload, reference_image: bytes | None = None
    ) -> str:
        raise PermanentError(
            "provider_capability_unavailable",
            "Edge TTS does not generate scripts",
        )

    def speech_identity(self) -> SpeechIdentity:
        return SpeechIdentity(PROVIDER_EDGE, self.config.voice, self.config.rate, 24_000)

    def generate_speech(self, request: SpeechRequest, abort: Abort) -> SpeechArtifact:
        if not request.script.strip():
            raise PermanentError("provider_request_failed", "speech script is blank")
        abort.check()
        try:
            mp3 = _run_download(request.script, self.config, abort)
        except asyncio.CancelledError as exc:
            # Only a kill cancels the download; check() turns it into the kill's reason.
            abort.check()
            raise TransientError("Edge TTS download was cancelled") from exc
        except WSServerHandshakeError as exc:
            raise TransientError(
                f"Edge TTS handshake failed with HTTP {exc.status}"
            ) from exc
        except _TRANSPORT_ERRORS as exc:
            raise TransientError(f"Edge TTS connection failed: {type(exc).__name__}") from exc
        except NoAudioReceived as exc:
            raise TransientError("Edge TTS returned no audio") from exc
        except UnexpectedResponse as exc:
            raise PermanentError(
                "provider_response_invalid", "Edge TTS returned an unexpected response"
            ) from exc
        except UnknownResponse as exc:
            raise PermanentError(
                "provider_response_invalid", "Edge TTS returned an unknown response"
            ) from exc
        except ValueError as exc:
            raise PermanentError(
                "provider_request_failed", "Edge TTS rejected the request"
            ) from exc
        except Exception as exc:
            # An unclassified failure keeps the retry budget instead of failing the run outright.
            raise TransientError(f"Edge TTS failed: {type(exc).__name__}") from exc
        abort.check()

        base_tempo = _rate_factor(self.config.rate)
        wav = (
            _to_wav(mp3, abort)
            if abs(base_tempo - 1.0) < 1e-6
            else _to_wav(mp3, abort, atempo=base_tempo)
        )
        duration_ms = _validate_wav(wav)
        if _MAX_SPEECH_MS < duration_ms <= _SOFT_FIT_CEILING_MS:
            abort.check()
            combined_tempo = min(2.0, base_tempo * (duration_ms / _TARGET_FIT_MS))
            wav = _to_wav(mp3, abort, atempo=combined_tempo)
            duration_ms = _validate_wav(wav)
        return SpeechArtifact(
            body=wav,
            duration_ms=duration_ms,
            provider=PROVIDER_EDGE,
            voice=self.config.voice,
            speed=self.config.rate,
        )


def _run_download(script: str, config: EdgeConfig, abort: Abort) -> bytes:
    # ProactorEventLoop needs named-pipe access that restricted Windows runners may deny.
    loop_factory = asyncio.SelectorEventLoop if os.name == "nt" else None
    with asyncio.Runner(loop_factory=loop_factory) as runner:
        return runner.run(_cancellable_download(script, config, abort))


async def _cancellable_download(script: str, config: EdgeConfig, abort: Abort) -> bytes:
    loop = asyncio.get_running_loop()
    task = asyncio.current_task()
    assert task is not None

    def cancel() -> None:
        # The runner keeps callbacks after the download ends; a closed loop has nothing to stop.
        if not loop.is_closed():
            loop.call_soon_threadsafe(task.cancel)

    abort.on_kill(cancel)
    return await asyncio.wait_for(_download_with_retries(script, config), config.timeout_seconds)


async def _download_with_retries(script: str, config: EdgeConfig) -> bytes:
    # Edge Read Aloud frequently drops WebSocket turns with 1006/NoAudioReceived in short bursts;
    # absorb transient drops in-process before burning a broker redelivery and its 5-20 s backoff.
    for attempt in range(1, _MAX_DOWNLOAD_ATTEMPTS):
        try:
            return await _download_mp3(script, config)
        except (NoAudioReceived, *_TRANSPORT_ERRORS) as exc:
            delay = _retry_delay_seconds(attempt)
            log.warning(
                "Edge TTS transient failure; retrying in-process",
                extra={
                    "downloadAttempt": attempt,
                    "maxDownloadAttempts": _MAX_DOWNLOAD_ATTEMPTS,
                    "voice": config.voice,
                    "errorType": type(exc).__name__,
                    "delaySeconds": round(delay, 3),
                },
            )
            if delay > 0:
                await asyncio.sleep(delay)
    return await _download_mp3(script, config)


def _retry_delay_seconds(attempt: int) -> float:
    index = min(attempt - 1, len(_RETRY_DELAYS_SECONDS) - 1)
    base = _RETRY_DELAYS_SECONDS[index]
    return 0.0 if base <= 0 else base * random.uniform(0.8, 1.2)


async def _download_mp3(script: str, config: EdgeConfig) -> bytes:
    chunks = bytearray()
    # Edge rejects non-zero SSML prosody rates on vi-VN voices with NoAudioReceived, so tempo
    # adjustments run locally via ffmpeg while preserving the declared speed in speech metadata.
    communicate = edge_tts.Communicate(script, voice=config.voice, rate="+0%")
    async for chunk in communicate.stream():
        if chunk.get("type") == "audio":
            chunks.extend(chunk["data"])
    if not chunks:
        raise NoAudioReceived("Edge TTS produced no audio")
    return bytes(chunks)


def _to_wav(mp3: bytes, abort: Abort, *, atempo: float | None = None) -> bytes:
    if not mp3:
        raise PermanentError("provider_response_invalid", "Edge TTS produced empty audio")
    with tempfile.TemporaryDirectory(prefix=TEMP_PREFIX) as directory:
        root = Path(directory)
        source = root / "speech.mp3"
        output = root / "speech.wav"
        source.write_bytes(mp3)
        command = [
            "ffmpeg",
            "-v",
            "error",
            "-nostdin",
            "-y",
            "-i",
            str(source),
        ]
        if atempo is not None:
            command.extend(["-filter:a", f"atempo={atempo:.6f}"])
        command.extend(
            [
                "-ac",
                "1",
                "-ar",
                "24000",
                "-c:a",
                "pcm_s16le",
                str(output),
            ]
        )
        try:
            completed = abort.run(command)
        except OSError as exc:
            raise PermanentError(
                "provider_response_invalid", "Edge TTS audio could not be normalized"
            ) from exc
        if completed.returncode != 0:
            raise PermanentError(
                "provider_response_invalid", "Edge TTS audio could not be normalized"
            )
        return output.read_bytes()


def _rate_factor(rate: str) -> float:
    if not _valid_rate(rate):
        raise PermanentError("provider_request_failed", "EDGE_TTS_RATE must match +N% or -N%")
    return max(0.5, min(2.0, 1.0 + int(rate[:-1]) / 100.0))


def _validate_wav(body: bytes) -> int:
    try:
        with wave.open(io.BytesIO(body), "rb") as audio:
            channels = audio.getnchannels()
            sample_width = audio.getsampwidth()
            sample_rate = audio.getframerate()
            frames = audio.getnframes()
    except (EOFError, wave.Error) as exc:
        raise PermanentError("provider_response_invalid", "normalized speech is not WAV") from exc
    if channels != 1 or sample_width != 2 or sample_rate != 24_000 or frames <= 0:
        raise PermanentError("provider_response_invalid", "normalized speech has an invalid format")
    return max(1, round(frames * 1000 / sample_rate))


def config_from_env() -> EdgeConfig:
    voice = os.environ.get("EDGE_TTS_VOICE", DEFAULT_EDGE_VOICE).strip()
    rate = os.environ.get("EDGE_TTS_RATE", DEFAULT_EDGE_RATE).strip()
    try:
        timeout_seconds = float(
            os.environ.get("FLOWCORTEX_PROVIDER_TIMEOUT_SECONDS", DEFAULT_EDGE_TIMEOUT_SECONDS)
        )
    except ValueError as exc:
        raise RuntimeError("FLOWCORTEX_PROVIDER_TIMEOUT_SECONDS must be a number") from exc
    if not voice:
        raise RuntimeError("EDGE_TTS_VOICE must not be blank")
    if not _valid_rate(rate):
        raise RuntimeError("EDGE_TTS_RATE must match +N% or -N%")
    if timeout_seconds <= 0:
        raise RuntimeError("FLOWCORTEX_PROVIDER_TIMEOUT_SECONDS must be positive")
    return EdgeConfig(voice=voice, rate=rate, timeout_seconds=timeout_seconds)


def _valid_rate(rate: str) -> bool:
    return (
        len(rate) >= 3
        and rate[0] in "+-"
        and rate[-1] == "%"
        and rate[1:-1].isdigit()
        and int(rate[1:-1]) <= 100
    )
