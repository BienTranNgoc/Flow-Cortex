"""Edge Read Aloud speech adapter used by the demo provider runtime.

The upstream service returns MP3. This boundary converts it to the stable WAV contract consumed by
media capabilities and never logs the script or generated audio.
"""

from __future__ import annotations

import asyncio
import io
import os
import subprocess
import tempfile
import wave
from dataclasses import dataclass
from pathlib import Path

import edge_tts
from aiohttp import ClientError, WSServerHandshakeError
from edge_tts.exceptions import NoAudioReceived

from ..errors import PermanentError, TransientError
from .adapter import (
    FixtureProviderAdapter,
    SpeechArtifact,
    SpeechIdentity,
    SpeechRequest,
    VideoAbort,
    VideoArtifact,
    VideoIdentity,
    VideoRequest,
)
from .contracts.provider_job import ProductAdPayload

PROVIDER_EDGE = "edge"
DEFAULT_EDGE_VOICE = "vi-VN-HoaiMyNeural"
DEFAULT_EDGE_RATE = "+0%"
DEFAULT_EDGE_TIMEOUT_SECONDS = 60.0
_FFMPEG_TIMEOUT_SECONDS = 30.0


@dataclass(frozen=True)
class EdgeConfig:
    voice: str = DEFAULT_EDGE_VOICE
    rate: str = DEFAULT_EDGE_RATE
    timeout_seconds: float = DEFAULT_EDGE_TIMEOUT_SECONDS


@dataclass(frozen=True)
class EdgeSpeechAdapter:
    config: EdgeConfig

    def generate_script(self, payload: ProductAdPayload) -> str:
        # Edge is a speech provider. Keep Product Video runnable in demo mode by retaining the
        # deterministic script implementation until a separately selectable LLM adapter lands.
        return FixtureProviderAdapter().generate_script(payload)

    def speech_identity(self) -> SpeechIdentity:
        return SpeechIdentity(PROVIDER_EDGE, self.config.voice, self.config.rate, 24_000)

    def generate_speech(self, request: SpeechRequest) -> SpeechArtifact:
        if not request.script.strip():
            raise PermanentError("provider_request_failed", "speech script is blank")
        try:
            mp3 = _run_download(request.script, self.config)
        except WSServerHandshakeError as exc:
            raise TransientError(
                f"Edge TTS handshake failed with HTTP {exc.status}"
            ) from exc
        except (TimeoutError, ConnectionError, OSError, ClientError) as exc:
            raise TransientError(f"Edge TTS connection failed: {type(exc).__name__}") from exc
        except NoAudioReceived as exc:
            raise TransientError("Edge TTS returned no audio") from exc
        except ValueError as exc:
            raise PermanentError(
                "provider_request_failed", "Edge TTS rejected the request"
            ) from exc
        except TransientError:
            raise
        except Exception as exc:
            raise PermanentError("provider_response_invalid", "Edge TTS returned no audio") from exc

        wav = _to_wav(mp3)
        duration_ms = _validate_wav(wav)
        return SpeechArtifact(
            body=wav,
            duration_ms=duration_ms,
            provider=PROVIDER_EDGE,
            voice=self.config.voice,
            speed=self.config.rate,
        )

    def video_identity(self) -> VideoIdentity:
        raise PermanentError(
            "provider_capability_unavailable", "Edge video generation is not implemented"
        )

    def submit_video(self, request: VideoRequest) -> str:
        raise PermanentError(
            "provider_capability_unavailable", "Edge video generation is not implemented"
        )

    def await_video(self, task_id: str, abort: VideoAbort) -> VideoArtifact:
        raise PermanentError(
            "provider_capability_unavailable", "Edge video generation is not implemented"
        )


def _run_download(script: str, config: EdgeConfig) -> bytes:
    download = asyncio.wait_for(_download_mp3(script, config), config.timeout_seconds)
    if os.name != "nt":
        return asyncio.run(download)
    # ProactorEventLoop needs named-pipe access that restricted Windows runners may deny.
    loop = asyncio.SelectorEventLoop()
    try:
        return loop.run_until_complete(download)
    finally:
        loop.close()


async def _download_mp3(script: str, config: EdgeConfig) -> bytes:
    chunks = bytearray()
    communicate = edge_tts.Communicate(script, voice=config.voice, rate=config.rate)
    async for chunk in communicate.stream():
        if chunk.get("type") == "audio":
            chunks.extend(chunk["data"])
    if not chunks:
        raise NoAudioReceived("Edge TTS produced no audio")
    return bytes(chunks)


def _to_wav(mp3: bytes) -> bytes:
    if not mp3:
        raise PermanentError("provider_response_invalid", "Edge TTS produced empty audio")
    with tempfile.TemporaryDirectory(prefix="flowcortex-edge-") as directory:
        root = Path(directory)
        source = root / "speech.mp3"
        output = root / "speech.wav"
        source.write_bytes(mp3)
        try:
            subprocess.run(
                [
                    "ffmpeg",
                    "-v",
                    "error",
                    "-nostdin",
                    "-y",
                    "-i",
                    str(source),
                    "-ac",
                    "1",
                    "-ar",
                    "24000",
                    "-c:a",
                    "pcm_s16le",
                    str(output),
                ],
                check=True,
                capture_output=True,
                timeout=_FFMPEG_TIMEOUT_SECONDS,
            )
        except subprocess.TimeoutExpired as exc:
            raise TransientError("FFmpeg timed out while normalizing speech") from exc
        except (subprocess.CalledProcessError, OSError) as exc:
            raise PermanentError(
                "provider_response_invalid", "Edge TTS audio could not be normalized"
            ) from exc
        return output.read_bytes()


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
