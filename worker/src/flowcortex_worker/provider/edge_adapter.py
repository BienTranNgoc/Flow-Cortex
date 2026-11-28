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
import re
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
FEMALE_EDGE_VOICE = "vi-VN-HoaiMyNeural"
MALE_EDGE_VOICE = "vi-VN-NamMinhNeural"
DEFAULT_EDGE_RATE = "+10%"
DEFAULT_EDGE_TIMEOUT_SECONDS = 60.0


def resolve_edge_voice(request: SpeechRequest, default_voice: str) -> str:
    if request.voice and request.voice.strip():
        return request.voice.strip()
    if request.voice_gender == "female":
        return FEMALE_EDGE_VOICE
    if request.voice_gender == "male":
        return MALE_EDGE_VOICE
    return default_voice
_MAX_SPEECH_MS = 10_000
_SOFT_FIT_CEILING_MS = 11_500
_TARGET_FIT_MS = 9_850
_MC_GAP_MS = 300
_MC_END_MS_PATTERN = re.compile(rb"mc_end_ms=(\d{3,5})")
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
        effective_voice = resolve_edge_voice(request, self.config.voice)
        effective_config = EdgeConfig(
            voice=effective_voice,
            rate=self.config.rate,
            timeout_seconds=self.config.timeout_seconds,
        )
        abort.check()
        base_tempo = _rate_factor(self.config.rate)
        if request.mc_hook_script and request.product_close_script:
            mc_mp3 = self._download_segment(
                request.mc_hook_script.strip(), effective_config, abort
            )
            sp_mp3 = self._download_segment(
                request.product_close_script.strip(), effective_config, abort
            )
            atempo = None if abs(base_tempo - 1.0) < 1e-6 else base_tempo
            mc_wav = _to_wav(mc_mp3, abort, atempo=atempo)
            sp_wav = _to_wav(sp_mp3, abort, atempo=atempo)
            mc_ms = _validate_wav(mc_wav)
            sp_ms = _validate_wav(sp_wav)
            if 5_000 < mc_ms <= 5_750:
                mc_wav = _to_wav(mc_mp3, abort, atempo=min(2.0, base_tempo * (mc_ms / 4_850)))
                mc_ms = _validate_wav(mc_wav)
            if mc_ms > 5_000:
                raise PermanentError(
                    "speech_too_long",
                    f"MC hook speech is {mc_ms} ms, exceeding the 5000 ms scene limit",
                )
            if 5_000 < sp_ms <= 5_750:
                sp_wav = _to_wav(sp_mp3, abort, atempo=min(2.0, base_tempo * (sp_ms / 4_850)))
                sp_ms = _validate_wav(sp_wav)
            if sp_ms > 5_000:
                raise PermanentError(
                    "speech_too_long",
                    f"Product close speech is {sp_ms} ms, exceeding the 5000 ms scene limit",
                )
            wav = _concat_aligned_wav(mc_wav, sp_wav, abort)
            duration_ms = _validate_wav(wav)
            return SpeechArtifact(
                body=wav,
                duration_ms=duration_ms,
                provider=PROVIDER_EDGE,
                voice=effective_voice,
                speed=self.config.rate,
            )

        mp3 = self._download_segment(request.script, effective_config, abort)
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
            voice=effective_voice,
            speed=self.config.rate,
        )

    def _download_segment(self, script: str, config: EdgeConfig, abort: Abort) -> bytes:
        try:
            mp3 = _run_download(script, config, abort)
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
        return mp3


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


def split_mc_script_segments(script: str) -> tuple[str, str] | None:
    """Split a two-paragraph MC script separated by a blank line into (mc_hook, product_close)."""
    parts = [part.strip() for part in re.split(r"\r?\n\s*\r?\n", script.strip()) if part.strip()]
    if len(parts) == 2:
        return parts[0], parts[1]
    return None


def _extract_info_icmt(wav_bytes: bytes, start: int, end: int) -> bytes | None:
    sub_offset = start
    while sub_offset + 8 <= end:
        sub_id = wav_bytes[sub_offset : sub_offset + 4]
        sub_size = int.from_bytes(wav_bytes[sub_offset + 4 : sub_offset + 8], "little")
        sub_start = sub_offset + 8
        sub_end = sub_start + sub_size
        if sub_end > end:
            break
        if sub_id == b"ICMT":
            return wav_bytes[sub_start:sub_end].rstrip(b"\x00").strip()
        sub_offset = sub_end + (sub_size & 1)
    return None


def _extract_riff_icmt(wav_bytes: bytes) -> bytes | None:
    """Read raw RIFF WAVE LIST/INFO/ICMT metadata bounded strictly by declared RIFF size."""
    if len(wav_bytes) < 12 or wav_bytes[:4] != b"RIFF" or wav_bytes[8:12] != b"WAVE":
        return None
    riff_end = 8 + int.from_bytes(wav_bytes[4:8], "little")
    if riff_end < 12 or riff_end > len(wav_bytes):
        return None
    offset = 12
    while offset + 8 <= riff_end:
        chunk_id = wav_bytes[offset : offset + 4]
        chunk_size = int.from_bytes(wav_bytes[offset + 4 : offset + 8], "little")
        body_start = offset + 8
        body_end = body_start + chunk_size
        if body_end > riff_end:
            break
        is_info_list = (
            chunk_id == b"LIST"
            and chunk_size >= 4
            and wav_bytes[body_start : body_start + 4] == b"INFO"
        )
        if is_info_list:
            found = _extract_info_icmt(wav_bytes, body_start + 4, body_end)
            if found is not None:
                return found
        offset = body_end + (chunk_size & 1)
    return None


def extract_mc_end_ms(wav_bytes: bytes) -> int | None:
    """Read `mc_end_ms` strictly from a RIFF WAVE LIST/INFO/ICMT metadata chunk."""
    raw_comment = _extract_riff_icmt(wav_bytes)
    if raw_comment is None:
        return None
    match = _MC_END_MS_PATTERN.fullmatch(raw_comment)
    if not match:
        return None
    value = int(match.group(1))
    return value if 500 <= value <= 5_000 else None


def extract_audio_mode(wav_bytes: bytes) -> str | None:
    """Read `audio_mode` marker (`music_only`) from a RIFF WAVE LIST/INFO/ICMT chunk."""
    raw_comment = _extract_riff_icmt(wav_bytes)
    if raw_comment == b"audio_mode=music_only":
        return "music_only"
    return None


def make_music_only_wav(duration_ms: int = 10_000, *, sample_rate: int = 24_000) -> bytes:
    """Generate a silent mono PCM WAV tagged with `audio_mode=music_only` for BGM-only showroom."""
    buf = io.BytesIO()
    nframes = max(1, round(sample_rate * duration_ms / 1000))
    with wave.open(buf, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(sample_rate)
        wav.writeframes(b"\x00\x00" * nframes)
    raw = buf.getvalue()
    payload = b"audio_mode=music_only\x00"
    if len(payload) % 2:
        payload += b"\x00"
    icmt = b"ICMT" + len(payload).to_bytes(4, "little") + payload
    info = b"INFO" + icmt
    list_chunk = b"LIST" + len(info).to_bytes(4, "little") + info
    new_size = (int.from_bytes(raw[4:8], "little") + len(list_chunk)).to_bytes(4, "little")
    return raw[:4] + new_size + raw[8:] + list_chunk


def trim_wav(wav_bytes: bytes, duration_ms: int) -> bytes:
    """Return a clean mono PCM WAV trimmed to at most `duration_ms` milliseconds."""
    if duration_ms <= 0:
        raise PermanentError("contract_violation", "trim duration must be positive")
    try:
        with wave.open(io.BytesIO(wav_bytes), "rb") as src:
            params = src.getparams()
            max_frames = max(1, round(duration_ms * params.framerate / 1000))
            frames = src.readframes(min(params.nframes, max_frames))
    except (EOFError, wave.Error) as exc:
        raise PermanentError("provider_response_invalid", "audio input is not a valid WAV") from exc
    out = io.BytesIO()
    with wave.open(out, "wb") as dst:
        dst.setparams(params)
        dst.writeframes(frames)
    return out.getvalue()


def _concat_aligned_wav(mc_wav: bytes, sp_wav: bytes, abort: Abort) -> bytes:
    """Concatenates mc_wav + natural 300ms pause + sp_wav and embeds mc_end_ms in WAV metadata."""
    mc_ms = _validate_wav(mc_wav)
    mc_end_ms = min(5_000, max(2_000, mc_ms + _MC_GAP_MS))
    mc_end_s = mc_end_ms / 1000.0
    with tempfile.TemporaryDirectory(prefix=TEMP_PREFIX) as directory:
        root = Path(directory)
        mc_path = root / "mc.wav"
        sp_path = root / "sp.wav"
        out_path = root / "speech.wav"
        mc_path.write_bytes(mc_wav)
        sp_path.write_bytes(sp_wav)
        try:
            completed = abort.run(
                [
                    "ffmpeg", "-v", "error", "-nostdin", "-y",
                    "-i", str(mc_path),
                    "-i", str(sp_path),
                    "-filter_complex",
                    f"[0:a]apad=whole_dur={mc_end_s:.3f},atrim=duration={mc_end_s:.3f},"
                    "asetpts=PTS-STARTPTS[a0];"
                    "[1:a]asetpts=PTS-STARTPTS[a1];"
                    "[a0][a1]concat=n=2:v=0:a=1[a]",
                    "-map", "[a]",
                    "-metadata", f"comment=mc_end_ms={mc_end_ms}",
                    "-ac", "1", "-ar", "24000", "-c:a", "pcm_s16le",
                    str(out_path),
                ]
            )
        except OSError as exc:
            raise PermanentError(
                "provider_response_invalid", "Edge TTS segments could not be aligned"
            ) from exc
        if completed.returncode != 0 or not out_path.is_file():
            raise PermanentError(
                "provider_response_invalid", "Edge TTS segments could not be aligned"
            )
        return out_path.read_bytes()


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
