from __future__ import annotations

import hashlib
import io
import json
import wave
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any, Protocol

from pydantic import ValidationError

from .adapter import ProviderAdapter, SpeechIdentity, SpeechRequest
from .contracts.provider_job import GenerateScriptJob, GenerateSpeechJob, ProviderJob
from .contracts.provider_speech_result import ProviderSpeechData


class UnsupportedCapability(ValueError):
    pass


@dataclass(frozen=True)
class ProviderFile:
    name: str
    mime: str
    body: bytes


@dataclass(frozen=True)
class ProviderOutcome:
    data: dict[str, Any]
    files: tuple[ProviderFile, ...] = ()


class ObjectStore(Protocol):
    def head_object(self, bucket: str, key: str) -> dict[str, Any] | None: ...

    def fetch_bytes(self, bucket: str, key: str, abort: Any) -> bytes: ...


@dataclass(frozen=True)
class ExecutionContext:
    storage: ObjectStore
    bucket: str
    output_prefix: str
    abort: Any


Handler = Callable[
    [GenerateScriptJob | GenerateSpeechJob, ProviderAdapter, ExecutionContext], ProviderOutcome
]


def execute(
    job: ProviderJob, adapter: ProviderAdapter, context: ExecutionContext
) -> ProviderOutcome:
    request = job.root
    key = (request.capability, request.params.profile)
    try:
        handler = _CAPABILITIES[key]
    except KeyError as exc:
        raise UnsupportedCapability(f"Unsupported provider capability/profile: {key}") from exc
    return handler(request, adapter, context)


def _generate_script(
    job: GenerateScriptJob | GenerateSpeechJob, adapter: ProviderAdapter, context: ExecutionContext
) -> ProviderOutcome:
    if not isinstance(job, GenerateScriptJob):
        raise UnsupportedCapability("generate_script received the wrong job shape")
    script = adapter.generate_script(job.params.payload)
    return ProviderOutcome(
        data={"profile": "product_ad_v1", "language": "vi", "script": script}
    )


def _generate_speech(
    job: GenerateScriptJob | GenerateSpeechJob, adapter: ProviderAdapter, context: ExecutionContext
) -> ProviderOutcome:
    if not isinstance(job, GenerateSpeechJob):
        raise UnsupportedCapability("generate_speech received the wrong job shape")
    payload = job.params.payload
    request = SpeechRequest(payload.script, payload.sourceRevisionId)
    identity = adapter.speech_identity()
    reused = _reuse_existing_artifact(context, request, identity)
    if reused is not None:
        return reused
    speech = adapter.generate_speech(request)
    if (speech.provider, speech.voice, speech.speed) != (
        identity.provider,
        identity.voice,
        identity.speed,
    ):
        raise ValueError("speech adapter returned an identity different from its configuration")
    data = {
        "profile": request.profile,
        "durationMs": speech.duration_ms,
        "provider": speech.provider,
        "voice": speech.voice,
        "speed": speech.speed,
        "sourceRevisionId": str(request.source_revision_id),
        "idempotencyKey": _speech_idempotency_key(request, identity),
        "format": "wav",
    }
    manifest = json.dumps(data, ensure_ascii=False, sort_keys=True).encode("utf-8")
    return ProviderOutcome(
        data=data,
        files=(
            ProviderFile("speech.wav", speech.mime, speech.body),
            ProviderFile("manifest.json", "application/json", manifest),
        ),
    )


def _reuse_existing_artifact(
    context: ExecutionContext, request: SpeechRequest, identity: SpeechIdentity
) -> ProviderOutcome | None:
    wav_key = f"{context.output_prefix}speech.wav"
    manifest_key = f"{context.output_prefix}manifest.json"
    if context.storage.head_object(context.bucket, wav_key) is None:
        return None
    if context.storage.head_object(context.bucket, manifest_key) is None:
        return None
    raw_manifest = context.storage.fetch_bytes(context.bucket, manifest_key, context.abort)
    try:
        stored = ProviderSpeechData.model_validate_json(raw_manifest)
    except ValidationError:
        return None
    expected_key = _speech_idempotency_key(request, identity)
    if str(stored.sourceRevisionId) != str(request.source_revision_id):
        return None
    if (stored.provider, stored.voice, stored.speed) != (
        identity.provider,
        identity.voice,
        identity.speed,
    ):
        return None
    if stored.idempotencyKey != expected_key:
        return None
    wav_body = context.storage.fetch_bytes(context.bucket, wav_key, context.abort)
    if not _valid_wav(wav_body, stored.durationMs, identity.sample_rate):
        return None
    return ProviderOutcome(
        data=stored.model_dump(mode="json"),
        files=(
            ProviderFile("speech.wav", "audio/wav", wav_body),
            ProviderFile("manifest.json", "application/json", raw_manifest),
        ),
    )


def _valid_wav(body: bytes, duration_ms: int, sample_rate: int) -> bool:
    try:
        with wave.open(io.BytesIO(body), "rb") as audio:
            frames = audio.getnframes()
            actual_rate = audio.getframerate()
            valid = (
                audio.getnchannels() == 1
                and audio.getsampwidth() == 2
                and actual_rate == sample_rate
            )
    except (EOFError, wave.Error):
        return False
    actual_duration = max(1, round(frames * 1000 / actual_rate)) if actual_rate else 0
    return valid and frames > 0 and actual_duration == duration_ms


def _speech_idempotency_key(request: SpeechRequest, identity: SpeechIdentity) -> str:
    value = "\0".join((
        str(request.source_revision_id),
        request.profile,
        identity.provider,
        identity.voice,
        identity.speed,
    ))
    return hashlib.sha256(value.encode()).hexdigest()


_CAPABILITIES: dict[tuple[str, str], Handler] = {
    ("generate_script", "product_ad_v1"): _generate_script,
    ("generate_speech", "vi_voice_v1"): _generate_speech,
}
