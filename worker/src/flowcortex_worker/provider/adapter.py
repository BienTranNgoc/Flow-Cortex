from __future__ import annotations

import hashlib
import io
import subprocess
import tempfile
import wave
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol
from uuid import UUID

from ..errors import PermanentError
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
class StoryboardRequest:
    script: str
    source_revision_id: UUID


@dataclass(frozen=True)
class StoryboardArtifact:
    scene_1_prompt: str
    scene_2_prompt: str


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
    reference_image_url: str | None = None
    reference_image_bytes: bytes | None = None


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


class ScriptAdapter(Protocol):
    def generate_script(self, payload: ProductAdPayload) -> str: ...
    def generate_storyboard(self, request: StoryboardRequest) -> StoryboardArtifact: ...


class SpeechAdapter(Protocol):
    def speech_identity(self) -> SpeechIdentity: ...
    def generate_speech(self, request: SpeechRequest, abort: Abort) -> SpeechArtifact: ...


class VideoAdapter(Protocol):
    def video_identity(self) -> VideoIdentity: ...
    def submit_video(self, request: VideoRequest) -> str: ...
    def await_video(self, task_id: str, abort: VideoAbort) -> VideoArtifact: ...


class ProviderAdapter(ScriptAdapter, SpeechAdapter, VideoAdapter, Protocol):
    """A provider that handles every capability. Capability-scoped adapters implement
    only the relevant sub-protocol and are routed by CapabilityProviderAdapter."""


@dataclass(frozen=True)
class CapabilityProviderAdapter:
    """Routes each provider capability independently.

    Script and speech have different security, cost and availability characteristics. Keeping the
    routing here lets one worker use OpenAI for copy and Edge for speech without either adapter
    pretending to support the other capability.
    """

    script_adapter: ScriptAdapter
    speech_adapter: SpeechAdapter
    video_adapter: VideoAdapter

    def generate_script(self, payload: ProductAdPayload) -> str:
        return self.script_adapter.generate_script(payload)

    def generate_storyboard(self, request: StoryboardRequest) -> StoryboardArtifact:
        return self.script_adapter.generate_storyboard(request)

    def speech_identity(self) -> SpeechIdentity:
        return self.speech_adapter.speech_identity()

    def generate_speech(self, request: SpeechRequest, abort: Abort) -> SpeechArtifact:
        return self.speech_adapter.generate_speech(request, abort)

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

    def generate_storyboard(self, request: StoryboardRequest) -> StoryboardArtifact:
        return StoryboardArtifact(
            "Vertical 9:16 opening product hero shot, dynamic push-in, premium studio lighting, "
            "realistic movement, clean background, no text.",
            "Vertical 9:16 product-in-use benefit shot followed by a clean closing beauty shot, "
            "space for CTA overlay, realistic movement, no rendered text.",
        )

    def speech_identity(self) -> SpeechIdentity:
        return SpeechIdentity("fixture", "silence", "+0%", 16_000)

    def generate_speech(self, request: SpeechRequest, abort: Abort) -> SpeechArtifact:
        abort.check()
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
        return VideoIdentity(
            "fixture",
            "flowcortex-fixture-v1",
            5,
            "9:16",
            "480p",
            False,
        )

    def submit_video(self, request: VideoRequest) -> str:
        digest = hashlib.sha256(
            f"{request.source_revision_id}|{request.prompt}".encode("utf-8")
        ).hexdigest()[:16]
        return f"fixture-{digest}"

    def await_video(self, task_id: str, abort: VideoAbort) -> VideoArtifact:
        abort.check()
        with tempfile.TemporaryDirectory(prefix="flowcortex-fixture-video-") as directory:
            output = Path(directory) / "clip.mp4"
            completed = subprocess.run(
                [
                    "ffmpeg",
                    "-nostdin",
                    "-hide_banner",
                    "-loglevel",
                    "error",
                    "-y",
                    "-f",
                    "lavfi",
                    "-i",
                    "testsrc=duration=5:size=720x1280:rate=24",
                    "-an",
                    "-c:v",
                    "libx264",
                    "-preset",
                    "fast",
                    "-pix_fmt",
                    "yuv420p",
                    "-movflags",
                    "+faststart",
                    str(output),
                ],
                check=False,
                capture_output=True,
            )
            if completed.returncode != 0 or not output.is_file() or output.stat().st_size == 0:
                raise PermanentError(
                    "provider_response_invalid", "fixture video could not be generated"
                )
            return VideoArtifact(output.read_bytes(), task_id)
