from __future__ import annotations

import hashlib
import io
import json
import tempfile
import wave
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol, cast

from pydantic import ValidationError

from ..errors import PermanentError
from .adapter import (
    ScriptAdapter,
    SpeechAdapter,
    SpeechIdentity,
    SpeechRequest,
    StoryboardRequest,
    VideoAdapter,
    VideoIdentity,
    VideoRequest,
)
from .contracts.provider_job import (
    GenerateScriptJob,
    GenerateSpeechJob,
    GenerateStoryboardJob,
    GenerateVideoJob,
    ProviderJob,
)
from .contracts.provider_speech_result import ProviderSpeechData


class UnsupportedCapability(ValueError):
    pass


@dataclass(frozen=True)
class ProviderFile:
    name: str
    mime: str
    body: bytes
    stored: bool = False


@dataclass(frozen=True)
class ProviderOutcome:
    data: dict[str, Any]
    files: tuple[ProviderFile, ...] = ()


class ObjectStore(Protocol):
    def head_object(self, bucket: str, key: str) -> dict[str, Any] | None: ...

    def fetch_bytes(self, bucket: str, key: str, abort: Any) -> bytes: ...

    def put(self, key: str, body: bytes, mime: str, abort: Any) -> int: ...

    def presigned_get_url(self, bucket: str, key: str, *, expires_seconds: int = ...) -> str: ...


@dataclass(frozen=True)
class ExecutionContext:
    storage: ObjectStore
    bucket: str
    output_prefix: str
    abort: Any


Handler = Callable[
    [
        GenerateScriptJob | GenerateStoryboardJob | GenerateSpeechJob | GenerateVideoJob,
        ScriptAdapter | SpeechAdapter | VideoAdapter,
        ExecutionContext,
    ],
    ProviderOutcome,
]


def execute(
    job: ProviderJob,
    adapter: ScriptAdapter | SpeechAdapter | VideoAdapter,
    context: ExecutionContext,
) -> ProviderOutcome:
    request = job.root
    key = (request.capability, request.params.profile)
    try:
        handler = _CAPABILITIES[key]
    except KeyError as exc:
        raise UnsupportedCapability(f"Unsupported provider capability/profile: {key}") from exc
    return handler(request, adapter, context)


def _generate_script(
    job: GenerateScriptJob | GenerateStoryboardJob | GenerateSpeechJob | GenerateVideoJob,
    adapter: ScriptAdapter,
    context: ExecutionContext,
) -> ProviderOutcome:
    if not isinstance(job, GenerateScriptJob):
        raise UnsupportedCapability("generate_script received the wrong job shape")
    script = adapter.generate_script(
        job.params.payload, _fetch_reference_image(job, context)
    )
    return ProviderOutcome(
        data={"profile": "product_ad_v1", "language": "vi", "script": script}
    )


def _generate_speech(
    job: GenerateScriptJob | GenerateStoryboardJob | GenerateSpeechJob | GenerateVideoJob,
    adapter: SpeechAdapter,
    context: ExecutionContext,
) -> ProviderOutcome:
    if not isinstance(job, GenerateSpeechJob):
        raise UnsupportedCapability("generate_speech received the wrong job shape")
    payload = job.params.payload
    request = SpeechRequest(payload.script, payload.sourceRevisionId)
    identity = adapter.speech_identity()
    reused = _reuse_existing_artifact(context, request, identity)
    if reused is not None:
        return reused
    speech = adapter.generate_speech(request, context.abort)
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
    manifest = json.dumps(data, ensure_ascii=False, sort_keys=True).encode()
    return ProviderOutcome(
        data=data,
        files=(
            ProviderFile("speech.wav", speech.mime, speech.body),
            ProviderFile("manifest.json", "application/json", manifest),
        ),
    )


def _generate_storyboard(
    job: GenerateScriptJob | GenerateStoryboardJob | GenerateSpeechJob | GenerateVideoJob,
    adapter: ScriptAdapter,
    context: ExecutionContext,
) -> ProviderOutcome:
    if not isinstance(job, GenerateStoryboardJob):
        raise UnsupportedCapability("generate_storyboard received the wrong job shape")
    payload = job.params.payload
    reference_image = _fetch_reference_image(job, context)
    storyboard = adapter.generate_storyboard(
        StoryboardRequest(payload.script, payload.sourceRevisionId),
        reference_image=reference_image,
    )
    data = {
        "profile": "product_ad_storyboard_v1",
        "sourceRevisionId": str(payload.sourceRevisionId),
        "scene1Prompt": storyboard.scene_1_prompt,
        "scene2Prompt": storyboard.scene_2_prompt,
    }
    return ProviderOutcome(data=data)


def _generate_video(
    job: GenerateScriptJob | GenerateStoryboardJob | GenerateSpeechJob | GenerateVideoJob,
    adapter: VideoAdapter,
    context: ExecutionContext,
) -> ProviderOutcome:
    if not isinstance(job, GenerateVideoJob):
        raise UnsupportedCapability("generate_video received the wrong job shape")
    payload = job.params.payload
    image_bytes, image_url = _resolve_reference_image(job, context)
    request = VideoRequest(
        payload.prompt,
        payload.sourceRevisionId,
        reference_image_url=image_url,
        reference_image_bytes=image_bytes,
    )
    identity = adapter.video_identity()
    idempotency_key = _video_idempotency_key(request, identity)
    task_id = _load_video_task(context, idempotency_key, identity)
    if task_id is None:
        task_id = adapter.submit_video(request)
        _store_video_task(context, task_id, idempotency_key, identity)
    video = adapter.await_video(task_id, context.abort)
    if video.provider_task_id != task_id:
        raise PermanentError(
            "provider_response_invalid",
            "video adapter returned a different provider task id",
        )
    data = {
        "profile": request.profile,
        "durationSeconds": identity.duration_seconds,
        "provider": identity.provider,
        "model": identity.model,
        "aspectRatio": identity.aspect_ratio,
        "resolution": identity.resolution,
        "sourceRevisionId": str(request.source_revision_id),
        "idempotencyKey": idempotency_key,
        "providerTaskId": task_id,
        "format": "mp4",
    }
    normalized = _normalize_video(video.body, context)
    manifest = json.dumps(data, ensure_ascii=False, sort_keys=True).encode()
    return ProviderOutcome(
        data=data,
        files=(
            ProviderFile("clip.mp4", video.mime, normalized),
            ProviderFile("manifest.json", "application/json", manifest),
        ),
    )


def _resolve_reference_image(
    job: GenerateVideoJob, context: ExecutionContext
) -> tuple[bytes | None, str | None]:
    """Returns (bytes, url). Exactly one is populated: bytes for byte-only adapters, URL for
    URL-based adapters. The executor decides which side to fill based on the provider."""
    body = _fetch_reference_image(job, context)
    if body is None:
        return None, None
    if not job.inputs:
        raise AssertionError("resolved reference image has no input")
    reference = job.inputs[0]
    return body, context.storage.presigned_get_url(reference.bucket, reference.objectKey)


def _fetch_reference_image(
    job: GenerateScriptJob | GenerateStoryboardJob | GenerateVideoJob, context: ExecutionContext
) -> bytes | None:
    inputs = job.inputs or ()
    if not inputs:
        return None
    if len(inputs) > 1:
        raise PermanentError(
            "contract_violation",
            f"{job.capability} accepts at most one reference image",
        )
    reference = inputs[0]
    body = context.storage.fetch_bytes(reference.bucket, reference.objectKey, context.abort)
    if not _looks_like_image(body):
        raise PermanentError(
            "input_rejected",
            f"reference image '{reference.key}' is not a recognised image format",
        )
    return body


def _looks_like_image(body: bytes) -> bool:
    if len(body) < 12:
        return False
    if body[:8] == b"\x89PNG\r\n\x1a\n":
        return True
    if body[:3] == b"\xff\xd8\xff":
        return True
    if body[:6] in (b"GIF87a", b"GIF89a"):
        return True
    return bool(body[:4] == b"RIFF" and body[8:12] == b"WEBP")


def _load_video_task(
    context: ExecutionContext, idempotency_key: str, identity: VideoIdentity
) -> str | None:
    key = f"{context.output_prefix}manifest.json"
    if context.storage.head_object(context.bucket, key) is None:
        return None
    raw = context.storage.fetch_bytes(context.bucket, key, context.abort)
    try:
        checkpoint = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise PermanentError(
            "provider_response_invalid", "stored video task checkpoint is invalid"
        ) from exc
    expected = {
        "idempotencyKey": idempotency_key,
        "model": identity.model,
        "provider": identity.provider,
    }
    if not isinstance(checkpoint, dict) or any(checkpoint.get(k) != v for k, v in expected.items()):
        raise PermanentError(
            "provider_response_invalid",
            "stored video task checkpoint does not match this request",
        )
    task_id = checkpoint.get("providerTaskId")
    if not isinstance(task_id, str) or not task_id:
        raise PermanentError(
            "provider_response_invalid", "stored video task checkpoint has no provider task id"
        )
    return task_id


def _store_video_task(
    context: ExecutionContext,
    task_id: str,
    idempotency_key: str,
    identity: VideoIdentity,
) -> None:
    checkpoint = json.dumps(
        {
            "idempotencyKey": idempotency_key,
            "model": identity.model,
            "provider": identity.provider,
            "providerTaskId": task_id,
        },
            ensure_ascii=True,
            sort_keys=True,
        ).encode()
    context.storage.put(
        f"{context.output_prefix}manifest.json",
        checkpoint,
        "application/json",
        context.abort,
    )


def _normalize_video(body: bytes, context: ExecutionContext) -> bytes:
    with tempfile.TemporaryDirectory(prefix="flowcortex-video-") as directory:
        source = Path(directory) / "source.mp4"
        output = Path(directory) / "clip.mp4"
        source.write_bytes(body)
        completed = context.abort.run([
            "ffmpeg",
            "-nostdin",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            str(source),
            "-t",
            "5",
            "-vf",
            "scale=360:640:force_original_aspect_ratio=decrease,"
            "pad=360:640:(ow-iw)/2:(oh-ih)/2:black",
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
        ])
        if completed.returncode != 0 or not output.is_file() or output.stat().st_size == 0:
            raise PermanentError(
                "provider_response_invalid", "generated video could not be normalized"
            )
        return output.read_bytes()


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
            ProviderFile("speech.wav", "audio/wav", wav_body, stored=True),
            ProviderFile("manifest.json", "application/json", raw_manifest, stored=True),
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


def _video_idempotency_key(request: VideoRequest, identity: VideoIdentity) -> str:
    image_digest = (
        hashlib.sha256(request.reference_image_bytes or b"").hexdigest()
        if request.reference_image_bytes or request.reference_image_url
        else ""
    )
    value = "\0".join((
        str(request.source_revision_id),
        request.profile,
        identity.provider,
        identity.model,
        str(identity.duration_seconds),
        identity.aspect_ratio,
        identity.resolution,
        str(identity.generate_audio).lower(),
        hashlib.sha256(request.prompt.encode()).hexdigest(),
        image_digest,
    ))
    return hashlib.sha256(value.encode()).hexdigest()


_CAPABILITIES: dict[tuple[str, str], Handler] = cast(
    dict[tuple[str, str], Handler],
    {
        ("generate_script", "product_ad_v1"): _generate_script,
        ("generate_storyboard", "product_ad_storyboard_v1"): _generate_storyboard,
        ("generate_speech", "vi_voice_v1"): _generate_speech,
        ("generate_video", "vertical_ad_clip_v1"): _generate_video,
    },
)
