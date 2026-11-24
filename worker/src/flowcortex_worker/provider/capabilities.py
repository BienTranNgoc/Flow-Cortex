from __future__ import annotations

import hashlib
import io
import json
import logging
import tempfile
import wave
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Literal, Protocol, cast

from pydantic import ValidationError

from ..contracts.media_progress import VendorTaskReport
from ..errors import PermanentError, StaleDelivery
from .adapter import (
    CancelResult,
    FixtureProviderAdapter,
    KeyframeAdapter,
    KeyframeRequest,
    LipsyncAdapter,
    LipsyncRequest,
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
    GenerateKeyframeJob,
    GenerateScriptJob,
    GenerateSpeechJob,
    GenerateStoryboardJob,
    GenerateVideoJob,
    LipsyncVideoJob,
    ProviderJob,
    VendorCandidate,
    VendorLedger,
)
from .contracts.provider_speech_result import ProviderSpeechData
from .control import ControlClient, JobControlSession, SiblingFailedSignal
from .edge_adapter import resolve_edge_voice
from .seedance_adapter import PROVIDER_SEEDANCE
from .vendor_jobs import (
    ProbeRegistry,
    VendorJobEnded,
    VendorJobTimeout,
    VendorResultGone,
    select_attach,
    vendor_fingerprint,
)

# Providers that fetch the reference image themselves from a URL instead of taking its bytes.
_URL_REFERENCE_PROVIDERS = frozenset({PROVIDER_SEEDANCE})
_CLIP_WIDTH = 480
_CLIP_HEIGHT = 854
_CLIP_CRF = "18"

log = logging.getLogger("flowcortex_provider_worker.vendor_jobs")


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


def _discard_report(report: VendorTaskReport) -> None:
    return None


@dataclass(frozen=True)
class ExecutionContext:
    storage: ObjectStore
    bucket: str
    output_prefix: str
    abort: Any
    report: Callable[[VendorTaskReport], None] = _discard_report
    probes: ProbeRegistry = field(default_factory=dict)
    control: ControlClient | None = None
    deadline_monotonic: float | None = None


AnyJob = (
    GenerateScriptJob
    | GenerateStoryboardJob
    | GenerateSpeechJob
    | GenerateKeyframeJob
    | GenerateVideoJob
    | LipsyncVideoJob
)
AnyAdapter = ScriptAdapter | SpeechAdapter | VideoAdapter | KeyframeAdapter | LipsyncAdapter

Handler = Callable[
    [
        AnyJob,
        AnyAdapter,
        ExecutionContext,
    ],
    ProviderOutcome,
]


def execute(
    job: ProviderJob,
    adapter: AnyAdapter,
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
        job.params.payload,
        _fetch_reference_image(job, context),
        abort=context.abort,
        deadline_monotonic=context.deadline_monotonic,
    )
    return ProviderOutcome(
        data={"profile": "product_ad_v1", "language": "vi", "script": script}
    )


def _resolve_speech_identity(base: SpeechIdentity, request: SpeechRequest) -> SpeechIdentity:
    if base.provider not in {"azure", "edge"}:
        return base
    resolved_voice = resolve_edge_voice(request, base.voice)
    return SpeechIdentity(base.provider, resolved_voice, base.speed, base.sample_rate)


def _fallback_speech_identity(adapter: SpeechAdapter) -> SpeechIdentity | None:
    getter = getattr(adapter, "fallback_speech_identity", None)
    if callable(getter):
        return cast(SpeechIdentity, getter())
    inner = getattr(adapter, "speech_adapter", None)
    inner_getter = getattr(inner, "fallback_speech_identity", None) if inner is not None else None
    if callable(inner_getter):
        return cast(SpeechIdentity, inner_getter())
    return None


def _generate_speech(
    job: GenerateScriptJob | GenerateStoryboardJob | GenerateSpeechJob | GenerateVideoJob,
    adapter: SpeechAdapter,
    context: ExecutionContext,
) -> ProviderOutcome:
    if not isinstance(job, GenerateSpeechJob):
        raise UnsupportedCapability("generate_speech received the wrong job shape")
    payload = job.params.payload
    request = SpeechRequest(
        payload.script,
        payload.sourceRevisionId,
        voice=payload.voice,
        voice_gender=payload.voiceGender,
        mc_hook_script=payload.mcHookScript,
        product_close_script=payload.productCloseScript,
    )
    identity = _resolve_speech_identity(adapter.speech_identity(), request)
    fallback_base = _fallback_speech_identity(adapter)
    fallback_identity = (
        _resolve_speech_identity(fallback_base, request)
        if fallback_base is not None
        else None
    )
    reused = _reuse_existing_artifact(context, request, identity)
    if reused is None and fallback_identity is not None:
        reused = _reuse_existing_artifact(context, request, fallback_identity)
    if reused is not None:
        return reused
    speech = adapter.generate_speech(request, context.abort)
    if (speech.provider, speech.voice, speech.speed) == (
        identity.provider,
        identity.voice,
        identity.speed,
    ):
        effective_identity = identity
    elif fallback_identity is not None and (speech.provider, speech.voice, speech.speed) == (
        fallback_identity.provider,
        fallback_identity.voice,
        fallback_identity.speed,
    ):
        effective_identity = fallback_identity
    else:
        raise ValueError("speech adapter returned an identity different from its configuration")
    data = {
        "profile": request.profile,
        "durationMs": speech.duration_ms,
        "provider": speech.provider,
        "voice": speech.voice,
        "speed": speech.speed,
        "sourceRevisionId": str(request.source_revision_id),
        "idempotencyKey": _speech_idempotency_key(request, effective_identity),
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
    reference_images = _fetch_reference_images(job, context)
    storyboard = adapter.generate_storyboard(
        StoryboardRequest(
            payload.script,
            payload.sourceRevisionId,
            template_id=payload.templateId,
            scene_preset=payload.scenePreset,
            scene_note=payload.sceneNote,
            mc_hook_script=payload.mcHookScript,
            product_close_script=payload.productCloseScript,
        ),
        reference_image=reference_images[0] if reference_images else None,
        reference_images=reference_images,
        abort=context.abort,
        deadline_monotonic=context.deadline_monotonic,
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
    base_identity = adapter.video_identity()
    has_v2_override = (
        (
            payload.durationSeconds is not None
            and payload.durationSeconds != base_identity.duration_seconds
        )
        or (
            payload.resolution is not None
            and payload.resolution.lower() != base_identity.resolution.lower()
        )
        or payload.firstFrameKey is not None
    )
    if has_v2_override and base_identity.provider not in {"minimax", "wan", "fixture"}:
        raise PermanentError(
            "provider_capability_unavailable",
            f"Provider '{base_identity.provider}' does not support vertical_ad_clip_v2 "
            "overrides (durationSeconds/resolution/firstFrameKey)",
        )
    effective_duration = payload.durationSeconds or base_identity.duration_seconds
    effective_resolution = payload.resolution or base_identity.resolution
    identity = VideoIdentity(
        base_identity.provider,
        base_identity.model,
        effective_duration,
        base_identity.aspect_ratio,
        effective_resolution,
        base_identity.generate_audio,
    )
    images, image_urls = _resolve_reference_images(
        job, context, by_url=identity.provider in _URL_REFERENCE_PROVIDERS
    )
    first_frame_bytes: bytes | None = None
    ref_images = images
    ref_urls = image_urls
    if job.inputs and payload.firstFrameKey:
        for idx, ref in enumerate(job.inputs):
            if ref.key == payload.firstFrameKey and idx < len(images):
                first_frame_bytes = images[idx]
                ref_images = tuple(img for i, img in enumerate(images) if i != idx)
                ref_urls = tuple(u for i, u in enumerate(image_urls) if i != idx)
                break
    request = VideoRequest(
        payload.prompt,
        payload.sourceRevisionId,
        profile=job.params.profile,
        reference_image_url=ref_urls[0] if ref_urls else None,
        reference_image_bytes=ref_images[0] if ref_images else None,
        reference_images=ref_images,
        reference_image_urls=ref_urls,
        duration_seconds=payload.durationSeconds,
        resolution=payload.resolution,
        first_frame_image_bytes=first_frame_bytes,
    )
    idempotency_key = _video_idempotency_key(request, identity)
    ledger = job.vendorLedger
    fingerprint = (
        vendor_fingerprint(identity, adapter.vendor_scope(), request) if ledger else None
    )
    session: JobControlSession | None = (
        context.control.open_session(job.runId, job.jobId, job.attempt, abort=context.abort)
        if context.control is not None
        else None
    )
    try:
        task_id = _load_video_task(context, idempotency_key, identity)
        if session is not None:
            initial = session.await_initial()
            if initial == "discard_stale":
                raise StaleDelivery(f"Job {job.jobId} attempt {job.attempt} is stale before submit")
            if initial == "cancel_sibling_failed":
                if task_id is not None:
                    _cancel_on_sibling_failed(
                        adapter, identity, ledger, task_id, context, job,
                        "Sibling clip failed before worker resumed",
                    )
                raise PermanentError("sibling_failed", "Sibling clip failed before submit")
            session.start_polling()
        active_abort = session.wrapped_abort() if session is not None else context.abort
        attached: VendorCandidate | None = None
        if task_id is None:
            if ledger is not None and fingerprint is not None:
                attached = select_attach(
                    ledger.candidates,
                    fingerprint,
                    context.probes,
                    lambda candidate, status: _report_verdict(context, candidate, status),
                )
            if attached is not None:
                task_id = attached.vendorJobId
            else:
                task_id = _submit_recorded(adapter, request, identity, ledger, fingerprint, context)
            _store_video_task(context, task_id, idempotency_key, identity)
            if session is not None and attached is None:
                _guard_post_submit(session, adapter, identity, ledger, task_id, context, job)
        try:
            video = adapter.await_video(task_id, active_abort)
        except SiblingFailedSignal:
            _cancel_on_sibling_failed(
                adapter, identity, ledger, task_id, context, job,
                "Sibling clip failed while polling video", attached=attached,
            )
        except VendorJobEnded as exc:
            if attached is not None:
                _report_verdict(context, attached, exc.status)
            elif ledger is not None:
                context.report(
                    VendorTaskReport(
                        vendor=identity.provider,
                        model=identity.model,
                        vendorJobId=task_id,
                        status=exc.status,
                    )
                )
            raise
        except VendorJobTimeout as exc:
            if attached is not None:
                raise exc.with_ledger(str(attached.vendorTaskId)) from exc
            raise
        except VendorResultGone:
            if attached is None:
                raise
            _report_verdict(context, attached, "gone")
            attached = None
            task_id = _submit_recorded(adapter, request, identity, ledger, fingerprint, context)
            _store_video_task(context, task_id, idempotency_key, identity)
            if session is not None:
                _guard_post_submit(session, adapter, identity, ledger, task_id, context, job)
            try:
                video = adapter.await_video(task_id, active_abort)
            except SiblingFailedSignal:
                _cancel_on_sibling_failed(
                    adapter, identity, ledger, task_id, context, job,
                    "Sibling clip failed while polling video",
                )
    finally:
        if session is not None:
            session.close()
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
    if ledger is not None and fingerprint is not None:
        data["fingerprint"] = fingerprint
        data["requestKey"] = ledger.requestKey
    normalized = (
        _normalize_video(video.body, context)
        if effective_duration == 5
        else _normalize_video(video.body, context, duration_seconds=effective_duration)
    )
    manifest = json.dumps(data, ensure_ascii=False, sort_keys=True).encode()
    return ProviderOutcome(
        data=data,
        files=(
            ProviderFile("clip.mp4", video.mime, normalized),
            ProviderFile("manifest.json", "application/json", manifest),
        ),
    )


def _submit_recorded(
    adapter: VideoAdapter,
    request: VideoRequest,
    identity: VideoIdentity,
    ledger: VendorLedger | None,
    fingerprint: str | None,
    context: ExecutionContext,
) -> str:
    """Submits a new vendor job and, when the backend runs a ledger, records it before any wait."""
    task_id = adapter.submit_video(request)
    if ledger is not None and fingerprint is not None:
        context.report(
            VendorTaskReport(
                vendor=identity.provider,
                model=identity.model,
                fingerprint=fingerprint,
                requestKey=ledger.requestKey,
                vendorJobId=task_id,
                handle={},
                status="running",
                vendorCreatedAt=datetime.now(UTC),
            )
        )
        log.info(
            "vendor_job outcome=submit ledger=- vendor=%s job=%s candidates=%d",
            identity.provider,
            task_id,
            len(ledger.candidates),
        )
    return task_id


def _guard_post_submit(
    session: JobControlSession,
    adapter: VideoAdapter,
    identity: VideoIdentity,
    ledger: VendorLedger | None,
    task_id: str,
    context: ExecutionContext,
    job: GenerateVideoJob,
) -> None:
    if session.is_stale():
        raise StaleDelivery(f"Job {job.jobId} attempt {job.attempt} became stale during submit")
    if session.is_sibling_failed():
        _cancel_on_sibling_failed(
            adapter, identity, ledger, task_id, context, job,
            "Sibling clip failed during submit",
        )


def _cancel_on_sibling_failed(
    adapter: VideoAdapter,
    identity: VideoIdentity,
    ledger: VendorLedger | None,
    task_id: str,
    context: ExecutionContext,
    job: GenerateVideoJob,
    reason: str,
    *,
    attached: VendorCandidate | None = None,
) -> Any:
    cancel_result: CancelResult = adapter.cancel_video(task_id)
    if attached is not None:
        _report_verdict(context, attached, "failed")
    elif ledger is not None:
        context.report(
            VendorTaskReport(
                vendor=identity.provider,
                model=identity.model,
                vendorJobId=task_id,
                status="failed",
            )
        )
    if cancel_result.confirmed:
        log.info(
            "vendor_job outcome=cancelled_sibling_failed vendor=%s "
            "vendorTaskId=%s runId=%s jobId=%s attempt=%s",
            identity.provider,
            task_id,
            job.runId,
            job.jobId,
            job.attempt,
        )
        raise PermanentError("sibling_failed", f"{reason} (cancelled provider task {task_id})")
    log.warning(
        "vendor_job outcome=cancel_unconfirmed vendor=%s "
        "vendorTaskId=%s runId=%s jobId=%s attempt=%s detail=%s",
        identity.provider,
        task_id,
        job.runId,
        job.jobId,
        job.attempt,
        cancel_result.reason,
    )
    raise PermanentError(
        "sibling_failed",
        f"{reason}; cancel unconfirmed for provider task {task_id}: {cancel_result.reason}",
    )


def _report_verdict(
    context: ExecutionContext,
    candidate: VendorCandidate,
    status: Literal["failed", "expired", "gone"],
) -> None:
    context.report(
        VendorTaskReport(
            vendorTaskId=candidate.vendorTaskId,
            vendor=candidate.vendor,
            model=candidate.model,
            vendorJobId=candidate.vendorJobId,
            status=status,
        )
    )


def _resolve_reference_images(
    job: GenerateVideoJob, context: ExecutionContext, *, by_url: bool
) -> tuple[tuple[bytes, ...], tuple[str, ...]]:
    """Returns (images, urls) of the job's reference images (0..3)."""
    images = _fetch_reference_images(job, context)
    if not images:
        return (), ()
    if not by_url or not job.inputs:
        return images, ()
    urls = tuple(
        context.storage.presigned_get_url(ref.bucket, ref.objectKey)
        for ref in job.inputs
    )
    return images, urls


def _fetch_reference_images(
    job: GenerateStoryboardJob | GenerateVideoJob | GenerateKeyframeJob,
    context: ExecutionContext,
    *,
    max_images: int = 4,
) -> tuple[bytes, ...]:
    inputs = job.inputs or ()
    if not inputs:
        return ()
    if len(inputs) > max_images:
        raise PermanentError(
            "contract_violation",
            f"{job.capability} accepts at most {max_images} reference images",
        )
    bodies: list[bytes] = []
    for reference in inputs:
        body = context.storage.fetch_bytes(reference.bucket, reference.objectKey, context.abort)
        if not _looks_like_image(body):
            raise PermanentError(
                "input_rejected",
                f"reference image '{reference.key}' is not a recognised image format",
            )
        bodies.append(body)
    return tuple(bodies)


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


def _normalize_video(
    body: bytes, context: ExecutionContext, *, duration_seconds: int = 5
) -> bytes:
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
            str(duration_seconds),
            "-vf",
            f"scale={_CLIP_WIDTH}:{_CLIP_HEIGHT}:force_original_aspect_ratio=decrease,"
            f"pad={_CLIP_WIDTH}:{_CLIP_HEIGHT}:(ow-iw)/2:(oh-ih)/2:black",
            "-an",
            "-c:v",
            "libx264",
            "-preset",
            "fast",
            "-crf",
            _CLIP_CRF,
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
    if len(request.reference_images) > 1:
        image_digest = hashlib.sha256(
            b"\0".join(hashlib.sha256(img).digest() for img in request.reference_images)
        ).hexdigest()
    elif request.reference_image_bytes or request.reference_image_url:
        image_digest = hashlib.sha256(request.reference_image_bytes or b"").hexdigest()
    else:
        image_digest = ""
    first_frame_digest = (
        hashlib.sha256(request.first_frame_image_bytes).hexdigest()
        if request.first_frame_image_bytes
        else ""
    )
    parts = [
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
    ]
    if first_frame_digest:
        parts.append(first_frame_digest)
    return hashlib.sha256("\0".join(parts).encode()).hexdigest()


def _load_task_checkpoint(
    context: ExecutionContext, idempotency_key: str, provider: str, model: str
) -> str | None:
    key = f"{context.output_prefix}manifest.json"
    if context.storage.head_object(context.bucket, key) is None:
        return None
    raw = context.storage.fetch_bytes(context.bucket, key, context.abort)
    try:
        checkpoint = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise PermanentError(
            "provider_response_invalid", "stored task checkpoint is invalid"
        ) from exc
    expected = {
        "idempotencyKey": idempotency_key,
        "model": model,
        "provider": provider,
    }
    if not isinstance(checkpoint, dict) or any(
        checkpoint.get(k) != v for k, v in expected.items()
    ):
        raise PermanentError(
            "provider_response_invalid",
            "stored task checkpoint does not match this request",
        )
    task_id = checkpoint.get("providerTaskId")
    if not isinstance(task_id, str) or not task_id:
        raise PermanentError(
            "provider_response_invalid", "stored task checkpoint has no provider task id"
        )
    return task_id


def _store_task_checkpoint(
    context: ExecutionContext,
    task_id: str,
    idempotency_key: str,
    provider: str,
    model: str,
) -> None:
    checkpoint = json.dumps(
        {
            "idempotencyKey": idempotency_key,
            "model": model,
            "provider": provider,
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


def _probe_duration_seconds(body: bytes, context: ExecutionContext) -> float | None:
    with tempfile.TemporaryDirectory(prefix="flowcortex-probe-") as directory:
        path = Path(directory) / "clip.mp4"
        path.write_bytes(body)
        if not hasattr(context.abort, "run"):
            return None
        completed = context.abort.run([
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "format=duration",
            "-of",
            "default=noprint_wrappers=1:nokey=1",
            str(path),
        ])
        if completed.returncode != 0:
            return None
        try:
            value = float((completed.stdout or b"").decode().strip())
            return value if value > 0 else None
        except (ValueError, UnicodeDecodeError):
            return None


def _generate_keyframe(
    job: AnyJob,
    adapter: KeyframeAdapter,
    context: ExecutionContext,
) -> ProviderOutcome:
    if not isinstance(job, GenerateKeyframeJob):
        raise UnsupportedCapability("generate_keyframe received the wrong job shape")
    payload = job.params.payload
    images = _fetch_reference_images(job, context, max_images=5)
    request = KeyframeRequest(
        prompt=payload.prompt,
        source_revision_id=payload.sourceRevisionId,
        reference_images=images,
        scene_preset=payload.scenePreset,
        scene_note=payload.sceneNote,
    )
    is_fixture = isinstance(adapter, FixtureProviderAdapter) or isinstance(
        getattr(adapter, "keyframe_adapter", None), FixtureProviderAdapter
    )
    provider = "fixture" if is_fixture else "seedream"
    model = "flowcortex-fixture-v1" if is_fixture else "bytedance/seedream-4"
    image_digest = hashlib.sha256(
        b"\0".join(hashlib.sha256(img).digest() for img in images)
    ).hexdigest()
    idempotency_key = hashlib.sha256(
        "\0".join((
            str(payload.sourceRevisionId),
            job.params.profile,
            payload.scenePreset or "",
            payload.sceneNote or "",
            hashlib.sha256(payload.prompt.encode()).hexdigest(),
            image_digest,
        )).encode()
    ).hexdigest()
    ledger = job.vendorLedger
    fingerprint = idempotency_key if ledger else None
    task_id = _load_task_checkpoint(context, idempotency_key, provider, model)
    if task_id is None:
        attached: VendorCandidate | None = None
        if ledger is not None and fingerprint is not None:
            attached = select_attach(
                ledger.candidates,
                fingerprint,
                context.probes,
                lambda candidate, status: _report_verdict(context, candidate, status),
            )
        if attached is not None:
            task_id = attached.vendorJobId
        else:
            task_id = adapter.submit_keyframe(request)
            if ledger is not None and fingerprint is not None:
                context.report(
                    VendorTaskReport(
                        vendor=provider,
                        model=model,
                        fingerprint=fingerprint,
                        requestKey=ledger.requestKey,
                        vendorJobId=task_id,
                        handle={},
                        status="running",
                        vendorCreatedAt=datetime.now(UTC),
                    )
                )
        _store_task_checkpoint(context, task_id, idempotency_key, provider, model)
    artifact = adapter.await_keyframe(task_id, context.abort)
    data: dict[str, Any] = {
        "profile": job.params.profile,
        "provider": provider,
        "model": model,
        "width": artifact.width,
        "height": artifact.height,
        "aspectRatio": "9:16",
        "format": "jpg",
        "sourceRevisionId": str(payload.sourceRevisionId),
        "idempotencyKey": idempotency_key,
        "providerTaskId": artifact.provider_task_id,
    }
    if ledger is not None and fingerprint is not None:
        data["fingerprint"] = fingerprint
        data["requestKey"] = ledger.requestKey
    manifest = json.dumps(data, ensure_ascii=False, sort_keys=True).encode()
    return ProviderOutcome(
        data=data,
        files=(
            ProviderFile("keyframe.jpg", artifact.mime, artifact.body),
            ProviderFile("manifest.json", "application/json", manifest),
        ),
    )


def _lipsync_video(
    job: AnyJob,
    adapter: LipsyncAdapter,
    context: ExecutionContext,
) -> ProviderOutcome:
    if not isinstance(job, LipsyncVideoJob):
        raise UnsupportedCapability("lipsync_video received the wrong job shape")
    payload = job.params.payload
    by_key = {ref.key: ref for ref in job.inputs}
    video_ref = by_key.get("video")
    audio_ref = by_key.get("audio")
    if video_ref is None or audio_ref is None:
        raise PermanentError(
            "contract_violation",
            "lipsync_video requires both 'video' and 'audio' inputs",
        )
    video_bytes = context.storage.fetch_bytes(video_ref.bucket, video_ref.objectKey, context.abort)
    audio_bytes = context.storage.fetch_bytes(audio_ref.bucket, audio_ref.objectKey, context.abort)
    effective_duration = payload.durationSeconds or 5
    is_fixture = isinstance(adapter, FixtureProviderAdapter) or isinstance(
        getattr(adapter, "lipsync_adapter", None), FixtureProviderAdapter
    )
    provider = "fixture" if is_fixture else "kling_lipsync"
    model = "flowcortex-fixture-v1" if is_fixture else "kwaivgi/kling-lip-sync"
    request = LipsyncRequest(
        video_bytes=video_bytes,
        audio_bytes=audio_bytes,
        source_revision_id=payload.sourceRevisionId,
    )
    idempotency_key = hashlib.sha256(
        "\0".join((
            str(payload.sourceRevisionId),
            job.params.profile,
            str(effective_duration),
            hashlib.sha256(video_bytes).hexdigest(),
            hashlib.sha256(audio_bytes).hexdigest(),
        )).encode()
    ).hexdigest()
    ledger = job.vendorLedger
    fingerprint = idempotency_key if ledger else None
    task_id = _load_task_checkpoint(context, idempotency_key, provider, model)
    if task_id is None:
        attached: VendorCandidate | None = None
        if ledger is not None and fingerprint is not None:
            attached = select_attach(
                ledger.candidates,
                fingerprint,
                context.probes,
                lambda candidate, status: _report_verdict(context, candidate, status),
            )
        if attached is not None:
            task_id = attached.vendorJobId
        else:
            task_id = adapter.submit_lipsync(request)
            if ledger is not None and fingerprint is not None:
                context.report(
                    VendorTaskReport(
                        vendor=provider,
                        model=model,
                        fingerprint=fingerprint,
                        requestKey=ledger.requestKey,
                        vendorJobId=task_id,
                        handle={},
                        status="running",
                        vendorCreatedAt=datetime.now(UTC),
                    )
                )
        _store_task_checkpoint(context, task_id, idempotency_key, provider, model)
    video = adapter.await_lipsync(task_id, context.abort)
    normalized = _normalize_video(video.body, context, duration_seconds=effective_duration)
    probed = _probe_duration_seconds(normalized, context)
    reported_duration = (
        max(2, min(10, round(probed))) if probed is not None else effective_duration
    )
    data: dict[str, Any] = {
        "profile": job.params.profile,
        "durationSeconds": reported_duration,
        "provider": provider,
        "model": model,
        "aspectRatio": "9:16",
        "resolution": "480p",
        "sourceRevisionId": str(payload.sourceRevisionId),
        "idempotencyKey": idempotency_key,
        "providerTaskId": video.provider_task_id,
        "format": "mp4",
    }
    if ledger is not None and fingerprint is not None:
        data["fingerprint"] = fingerprint
        data["requestKey"] = ledger.requestKey
    manifest = json.dumps(data, ensure_ascii=False, sort_keys=True).encode()
    return ProviderOutcome(
        data=data,
        files=(
            ProviderFile("clip.mp4", video.mime, normalized),
            ProviderFile("manifest.json", "application/json", manifest),
        ),
    )


_CAPABILITIES: dict[tuple[str, str], Handler] = cast(
    dict[tuple[str, str], Handler],
    {
        ("generate_script", "product_ad_v1"): _generate_script,
        ("generate_storyboard", "product_ad_storyboard_v1"): _generate_storyboard,
        ("generate_speech", "vi_voice_v1"): _generate_speech,
        ("generate_keyframe", "vertical_ad_keyframe_v1"): _generate_keyframe,
        ("generate_video", "vertical_ad_clip_v1"): _generate_video,
        ("generate_video", "vertical_ad_clip_v2"): _generate_video,
        ("lipsync_video", "vertical_ad_lipsync_v1"): _lipsync_video,
    },
)
