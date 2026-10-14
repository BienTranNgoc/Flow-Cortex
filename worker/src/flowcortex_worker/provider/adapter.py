from __future__ import annotations

import io
import wave
from dataclasses import dataclass
from typing import Protocol

from .contracts.provider_job import ProductAdPayload


@dataclass(frozen=True)
class SpeechArtifact:
    body: bytes
    duration_ms: int
    mime: str = "audio/wav"


class ProviderAdapter(Protocol):
    def generate_script(self, payload: ProductAdPayload) -> str: ...

    def generate_speech(self, script: str) -> SpeechArtifact: ...


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

    def generate_speech(self, script: str) -> SpeechArtifact:
        duration_ms = min(30_000, max(15_000, len(script) * 55))
        frames = duration_ms * 16
        output = io.BytesIO()
        with wave.open(output, "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(16_000)
            wav.writeframes(b"\0\0" * frames)
        return SpeechArtifact(output.getvalue(), duration_ms)
