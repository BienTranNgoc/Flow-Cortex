from __future__ import annotations

import io
import wave
from dataclasses import dataclass
from typing import Protocol
from uuid import UUID

from ..errors import PermanentError
from .contracts.provider_job import ProductAdPayload


@dataclass(frozen=True)
class SpeechArtifact:
    body: bytes
    duration_ms: int
    provider: str
    voice: str
    speed: str
    mime: str = "audio/wav"


@dataclass(frozen=True)
class SpeechRequest:
    script: str
    source_revision_id: UUID
    profile: str = "vi_voice_v1"


@dataclass(frozen=True)
class SpeechIdentity:
    provider: str
    voice: str
    speed: str
    sample_rate: int


@dataclass(frozen=True)
class VideoRequest:
    prompt: str
    source_revision_id: UUID
    profile: str = "vertical_ad_clip_v1"


@dataclass(frozen=True)
class VideoIdentity:
    provider: str
    model: str
    duration_seconds: int
    aspect_ratio: str
    resolution: str
    generate_audio: bool


@dataclass(frozen=True)
class VideoArtifact:
    body: bytes
    provider_task_id: str
    mime: str = "video/mp4"


class VideoAbort(Protocol):
    def check(self) -> None: ...


class ProviderAdapter(Protocol):
    def generate_script(self, payload: ProductAdPayload) -> str: ...

    def speech_identity(self) -> SpeechIdentity: ...

    def generate_speech(self, request: SpeechRequest) -> SpeechArtifact: ...

    def video_identity(self) -> VideoIdentity: ...

    def submit_video(self, request: VideoRequest) -> str: ...

    def await_video(self, task_id: str, abort: VideoAbort) -> VideoArtifact: ...


@dataclass(frozen=True)
class CapabilityProviderAdapter:
    """Routes each provider capability independently.

    Script and speech have different security, cost and availability characteristics. Keeping the
    routing here lets one worker use OpenAI for copy and Edge for speech without either adapter
    pretending to support the other capability.
    """

    script_adapter: ProviderAdapter
    speech_adapter: ProviderAdapter
    video_adapter: ProviderAdapter

    def generate_script(self, payload: ProductAdPayload) -> str:
        return self.script_adapter.generate_script(payload)

    def speech_identity(self) -> SpeechIdentity:
        return self.speech_adapter.speech_identity()

    def generate_speech(self, request: SpeechRequest) -> SpeechArtifact:
        return self.speech_adapter.generate_speech(request)

    def video_identity(self) -> VideoIdentity:
        return self.video_adapter.video_identity()

    def submit_video(self, request: VideoRequest) -> str:
        return self.video_adapter.submit_video(request)

    def await_video(self, task_id: str, abort: VideoAbort) -> VideoArtifact:
        return self.video_adapter.await_video(task_id, abort)


class FixtureProviderAdapter:
    """Deterministic local adapter for development and contract tests."""

    def generate_script(self, payload: ProductAdPayload) -> str:
        parts = [
            f"Khám phá {payload.productName}.",
            payload.description,
        ]
        if payload.price:
            parts.append(f"Giá: {payload.price}.")
        if payload.promotion:
            parts.append(f"Ưu đãi: {payload.promotion}.")
        parts.append(payload.cta)
        return " ".join(parts)

    def speech_identity(self) -> SpeechIdentity:
        return SpeechIdentity("fixture", "silence", "+0%", 16_000)

    def generate_speech(self, request: SpeechRequest) -> SpeechArtifact:
        duration_ms = min(30_000, max(15_000, len(request.script) * 55))
        frames = duration_ms * 16
        output = io.BytesIO()
        with wave.open(output, "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(16_000)
            wav.writeframes(b"\0\0" * frames)
        return SpeechArtifact(output.getvalue(), duration_ms, "fixture", "silence", "+0%")

    def video_identity(self) -> VideoIdentity:
        raise PermanentError(
            "provider_capability_unavailable", "fixture video generation is not implemented"
        )

    def submit_video(self, request: VideoRequest) -> str:
        raise PermanentError(
            "provider_capability_unavailable", "fixture video generation is not implemented"
        )

    def await_video(self, task_id: str, abort: VideoAbort) -> VideoArtifact:
        raise PermanentError(
            "provider_capability_unavailable", "fixture video generation is not implemented"
        )
