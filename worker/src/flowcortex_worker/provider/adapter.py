from __future__ import annotations

import io
import subprocess
import wave
from collections.abc import Callable
from dataclasses import dataclass
from typing import Protocol
from uuid import UUID

from .contracts.provider_job import ProductAdPayload


class Abort(Protocol):
    """The job's kill switch: subprocesses and transfers must stop when it fires."""

    def check(self) -> None: ...

    def on_kill(self, callback: Callable[[], None]) -> None: ...

    def run(self, argv: list[str]) -> subprocess.CompletedProcess[bytes]: ...


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


class ProviderAdapter(Protocol):
    def generate_script(self, payload: ProductAdPayload) -> str: ...

    def speech_identity(self) -> SpeechIdentity: ...

    def generate_speech(self, request: SpeechRequest, abort: Abort) -> SpeechArtifact: ...


@dataclass(frozen=True)
class CapabilityProviderAdapter:
    """Routes each provider capability independently.

    Script and speech have different security, cost and availability characteristics. Keeping the
    routing here lets one worker use OpenAI for copy and Edge for speech without either adapter
    pretending to support the other capability.
    """

    script_adapter: ProviderAdapter
    speech_adapter: ProviderAdapter

    def generate_script(self, payload: ProductAdPayload) -> str:
        return self.script_adapter.generate_script(payload)

    def speech_identity(self) -> SpeechIdentity:
        return self.speech_adapter.speech_identity()

    def generate_speech(self, request: SpeechRequest, abort: Abort) -> SpeechArtifact:
        return self.speech_adapter.generate_speech(request, abort)


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

    def generate_speech(self, request: SpeechRequest, abort: Abort) -> SpeechArtifact:
        duration_ms = min(30_000, max(15_000, len(request.script) * 55))
        frames = duration_ms * 16
        output = io.BytesIO()
        with wave.open(output, "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(16_000)
            wav.writeframes(b"\0\0" * frames)
        return SpeechArtifact(output.getvalue(), duration_ms, "fixture", "silence", "+0%")
