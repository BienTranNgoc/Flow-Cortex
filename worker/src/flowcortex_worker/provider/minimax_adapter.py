"""Replicate adapter for MiniMax H3 Lite/Highspeed multi-reference video generation."""

from __future__ import annotations

import json
import logging
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from typing import Any, NoReturn

from ..errors import PermanentError, TransientError
from .adapter import (
    Abort,
    CancelResult,
    SpeechArtifact,
    SpeechIdentity,
    SpeechRequest,
    StoryboardArtifact,
    StoryboardRequest,
    VendorProbe,
    VideoAbort,
    VideoArtifact,
    VideoIdentity,
    VideoRequest,
)
from .contracts.provider_job import ProductAdPayload
from .vendor_jobs import (
    RESULT_GONE_STATUSES,
    WORKER_USER_AGENT,
    VendorJobEnded,
    VendorJobTimeout,
    VendorResultGone,
    download_replicate_media,
    probe_json,
    probe_media,
    vendor_scope,
)
from .wan_adapter import _image_mime, _is_replicate_delivery, _string

log = logging.getLogger("flowcortex_provider_worker.minimax")

PROVIDER_MINIMAX = "minimax"
_MAX_VIDEO_BYTES = 100 * 1024 * 1024
_TERMINAL_FAILURES = {"failed", "canceled"}
_CANCEL_ATTEMPTS = 3
_CANCEL_RETRY_DELAYS = (1.0, 2.0)
_CANCEL_TIMEOUT_SECONDS = 10.0
_CANCEL_SETTLED_STATUSES = {"canceled", "cancelled", "succeeded", "failed"}
_VALID_RESOLUTIONS = {"480P", "768P"}
_VALID_RATIOS = {"21:9", "16:9", "4:3", "1:1", "3:4", "9:16"}
_MIME_EXTENSIONS = {
    "image/png": "png",
    "image/jpeg": "jpg",
    "image/gif": "gif",
    "image/webp": "webp",
}


@dataclass(frozen=True)
class MinimaxConfig:
    api_key: str
    base_url: str = "https://api.replicate.com/v1"
    model: str = "minimax/h3-lite"
    resolution: str = "768P"
    duration: int = 5
    aspect_ratio: str = "9:16"
    request_timeout_seconds: float = 60.0
    poll_interval_seconds: float = 5.0
    poll_timeout_seconds: float = 480.0


@dataclass(frozen=True)
class MinimaxAdapter:
    config: MinimaxConfig

    def generate_script(
        self, payload: ProductAdPayload, reference_image: bytes | None = None
    ) -> str:
        raise PermanentError(
            "provider_capability_unavailable", "MiniMax script generation is not implemented"
        )

    def generate_storyboard(
        self, request: StoryboardRequest, reference_image: bytes | None = None
    ) -> StoryboardArtifact:
        raise PermanentError(
            "provider_capability_unavailable", "MiniMax storyboard generation is not implemented"
        )

    def speech_identity(self) -> SpeechIdentity:
        raise PermanentError(
            "provider_capability_unavailable", "MiniMax speech generation is not implemented"
        )

    def generate_speech(self, request: SpeechRequest, abort: Abort) -> SpeechArtifact:
        raise PermanentError(
            "provider_capability_unavailable", "MiniMax speech generation is not implemented"
        )

    def video_identity(self) -> VideoIdentity:
        resolution = self.config.resolution.strip().upper()
        if resolution not in _VALID_RESOLUTIONS:
            raise PermanentError(
                "contract_violation", "MiniMax resolution must be 480P or 768P"
            )
        if not 4 <= self.config.duration <= 10:
            raise PermanentError(
                "contract_violation", "MiniMax duration must be between 4 and 10 seconds"
            )
        if self.config.aspect_ratio not in _VALID_RATIOS:
            raise PermanentError(
                "contract_violation",
                f"Unsupported MiniMax aspect ratio '{self.config.aspect_ratio}'",
            )
        return VideoIdentity(
            PROVIDER_MINIMAX,
            self.config.model,
            self.config.duration,
            self.config.aspect_ratio,
            resolution.lower(),
            False,
        )

    def submit_video(self, request: VideoRequest) -> str:
        identity = self.video_identity()
        duration = request.duration_seconds or identity.duration_seconds
        if not 4 <= duration <= 10:
            raise PermanentError(
                "contract_violation", "MiniMax duration must be between 4 and 10 seconds"
            )
        resolution = (request.resolution or identity.resolution).strip().upper()
        if resolution not in _VALID_RESOLUTIONS:
            raise PermanentError(
                "contract_violation", "MiniMax resolution must be 480P or 768P"
            )
        model_path = "/".join(
            urllib.parse.quote(part, safe="") for part in self.config.model.split("/")
        )
        input_body: dict[str, Any] = {
            "prompt": request.prompt,
            "duration": duration,
            "resolution": resolution,
            "ratio": identity.aspect_ratio,
        }
        # MiniMax H3 rejects first_frame_image combined with reference_image_urls (E006).
        if request.first_frame_image_bytes:
            input_body["first_frame_image"] = self._upload_file(request.first_frame_image_bytes, 0)
        else:
            reference_urls = self._resolve_reference_urls(request)
            if reference_urls:
                input_body["reference_image_urls"] = reference_urls
        body = json.dumps({"input": input_body}).encode()
        response = self._json_request(
            f"{self.config.base_url.rstrip('/')}/models/{model_path}/predictions",
            method="POST",
            body=body,
        )
        task_id = _string(response, "id")
        if not task_id:
            raise PermanentError(
                "provider_response_invalid", "MiniMax submit response has no prediction id"
            )
        return task_id

    def await_video(self, task_id: str, abort: VideoAbort) -> VideoArtifact:
        deadline = time.monotonic() + self.config.poll_timeout_seconds
        task_url = self._task_url(task_id)
        while True:
            abort.check()
            response = self._json_request(task_url, method="GET", task_lookup=True)
            status = (_string(response, "status") or "").lower()
            if status == "succeeded":
                video_url = _extract_video_url(response.get("output"))
                if not video_url:
                    raise PermanentError(
                        "provider_response_invalid", "MiniMax completed without a video URL"
                    )
                return VideoArtifact(
                    self._download_video(
                        video_url, task_id=task_id, abort=abort, deadline=deadline
                    ),
                    task_id,
                )
            if status in _TERMINAL_FAILURES:
                log.warning("MiniMax prediction failed", extra={"taskStatus": status})
                raise VendorJobEnded("failed", f"MiniMax prediction ended with status {status}")
            if status not in {"starting", "processing"}:
                raise PermanentError(
                    "provider_response_invalid", "MiniMax returned an unknown prediction status"
                )
            if time.monotonic() >= deadline:
                raise VendorJobTimeout(PROVIDER_MINIMAX, task_id)
            remaining = max(0.0, deadline - time.monotonic())
            delay = min(self.config.poll_interval_seconds, remaining)
            wait_fn = getattr(abort, "wait", None)
            if callable(wait_fn):
                wait_fn(delay)
            else:
                time.sleep(delay)

    def cancel_video(self, task_id: str) -> CancelResult:
        url = f"{self._task_url(task_id)}/cancel"
        last_reason = "cancel request failed"
        for attempt in range(1, _CANCEL_ATTEMPTS + 1):
            req = urllib.request.Request(
                url,
                data=b"",
                method="POST",
                headers={
                    "Authorization": f"Bearer {self.config.api_key}",
                    "User-Agent": WORKER_USER_AGENT,
                },
            )
            try:
                with urllib.request.urlopen(req, timeout=_CANCEL_TIMEOUT_SECONDS) as response:
                    parsed = json.loads(response.read())
                if not isinstance(parsed, dict):
                    last_reason = "cancel response was not a JSON object"
                else:
                    status = (_string(parsed, "status") or "").lower()
                    if status in _CANCEL_SETTLED_STATUSES:
                        log.info(
                            "MiniMax prediction cancel settled",
                            extra={"providerTaskId": task_id, "taskStatus": status},
                        )
                        return CancelResult(confirmed=True, status=status)
                    last_reason = f"unexpected prediction status '{status or 'missing'}'"
            except urllib.error.HTTPError as exc:
                last_reason = f"HTTP {exc.code}"
                log.warning(
                    "MiniMax prediction cancel HTTP error",
                    extra={
                        "providerTaskId": task_id,
                        "status": exc.code,
                        "cancelAttempt": attempt,
                    },
                )
                if exc.code not in (408, 429) and not (500 <= exc.code < 600):
                    return CancelResult(confirmed=False, reason=last_reason)
            except (urllib.error.URLError, TimeoutError, ValueError) as exc:
                last_reason = type(exc).__name__
                log.warning(
                    "MiniMax prediction cancel failed",
                    extra={
                        "providerTaskId": task_id,
                        "errorType": last_reason,
                        "cancelAttempt": attempt,
                    },
                )
            if attempt < _CANCEL_ATTEMPTS:
                time.sleep(_CANCEL_RETRY_DELAYS[attempt - 1])
        log.warning(
            "MiniMax prediction %s cancel unconfirmed after retries",
            task_id,
            extra={"providerTaskId": task_id, "reason": last_reason},
        )
        return CancelResult(confirmed=False, reason=last_reason)

    def probe_video(self, task_id: str) -> VendorProbe:
        body = probe_json(
            self._task_url(task_id),
            {"Authorization": f"Bearer {self.config.api_key}"},
            self.config.request_timeout_seconds,
        )
        if body is None:
            return "gone"
        status = (_string(body, "status") or "").lower()
        if status in {"starting", "processing"}:
            return "running"
        if status in _TERMINAL_FAILURES:
            return "failed"
        if status == "succeeded":
            output = _extract_video_url(body.get("output"))
            if not output or not _is_replicate_delivery(output):
                return "gone"
            return probe_media(output, {}, self.config.request_timeout_seconds)
        raise TransientError("MiniMax probe read an unknown prediction status")

    def vendor_scope(self) -> dict[str, str]:
        return vendor_scope(self.config.base_url, self.config.api_key)

    def _resolve_reference_urls(self, request: VideoRequest) -> list[str]:
        if request.reference_image_urls:
            return list(request.reference_image_urls)
        images = request.reference_images or (
            (request.reference_image_bytes,) if request.reference_image_bytes else ()
        )
        if images:
            return [
                self._upload_file(image_bytes, index)
                for index, image_bytes in enumerate(images, start=1)
            ]
        if request.reference_image_url:
            return [request.reference_image_url]
        return []

    def _upload_file(self, image_bytes: bytes, index: int) -> str:
        mime = _image_mime(image_bytes)
        ext = _MIME_EXTENSIONS.get(mime, "jpg")
        boundary = f"----FlowCortexBoundary{uuid.uuid4().hex}"
        disposition = (
            f'Content-Disposition: form-data; name="content"; filename="reference_{index}.{ext}"'
        )
        body = (
            f"--{boundary}\r\n"
            f"{disposition}\r\n"
            f"Content-Type: {mime}\r\n\r\n"
        ).encode() + image_bytes + f"\r\n--{boundary}--\r\n".encode()
        req = urllib.request.Request(
            f"{self.config.base_url.rstrip('/')}/files",
            data=body,
            method="POST",
            headers={
                "Authorization": f"Bearer {self.config.api_key}",
                "Content-Type": f"multipart/form-data; boundary={boundary}",
                "User-Agent": WORKER_USER_AGENT,
            },
        )
        try:
            with urllib.request.urlopen(
                req, timeout=self.config.request_timeout_seconds
            ) as response:
                response_body = response.read()
        except urllib.error.HTTPError as exc:
            _raise_http_error(exc)
        except (urllib.error.URLError, TimeoutError) as exc:
            log.warning("MiniMax file upload failed", extra={"errorType": type(exc).__name__})
            raise TransientError(f"MiniMax file upload failed: {type(exc).__name__}") from exc
        try:
            parsed = json.loads(response_body)
        except json.JSONDecodeError as exc:
            raise PermanentError(
                "provider_response_invalid", "MiniMax file upload response was not valid JSON"
            ) from exc
        urls = parsed.get("urls") if isinstance(parsed, dict) else None
        file_url = _string(urls, "get") if isinstance(urls, dict) else None
        if not file_url:
            raise PermanentError(
                "provider_response_invalid", "MiniMax file upload response has no urls.get"
            )
        return file_url

    def _task_url(self, task_id: str) -> str:
        return f"{self.config.base_url.rstrip('/')}/predictions/{urllib.parse.quote(task_id)}"

    def _json_request(
        self,
        url: str,
        *,
        method: str,
        body: bytes | None = None,
        task_lookup: bool = False,
    ) -> dict[str, Any]:
        req = urllib.request.Request(
            url,
            data=body,
            method=method,
            headers={
                "Authorization": f"Bearer {self.config.api_key}",
                "Content-Type": "application/json",
                "User-Agent": WORKER_USER_AGENT,
            },
        )
        try:
            with urllib.request.urlopen(
                req, timeout=self.config.request_timeout_seconds
            ) as response:
                response_body = response.read()
        except urllib.error.HTTPError as exc:
            _raise_http_error(exc, gone=frozenset({404}) if task_lookup else frozenset())
        except (urllib.error.URLError, TimeoutError) as exc:
            log.warning("MiniMax connection failed", extra={"errorType": type(exc).__name__})
            raise TransientError(f"MiniMax connection failed: {type(exc).__name__}") from exc
        try:
            parsed = json.loads(response_body)
        except json.JSONDecodeError as exc:
            raise PermanentError(
                "provider_response_invalid", "MiniMax response was not valid JSON"
            ) from exc
        if not isinstance(parsed, dict):
            raise PermanentError(
                "provider_response_invalid", "MiniMax response was not a JSON object"
            )
        return parsed

    def _download_video(
        self, url: str, *, task_id: str, abort: VideoAbort, deadline: float
    ) -> bytes:
        if not _is_replicate_delivery(url):
            raise PermanentError(
                "provider_response_invalid", "MiniMax video URL is outside Replicate"
            )
        body = download_replicate_media(
            url,
            provider=PROVIDER_MINIMAX,
            label="MiniMax video",
            task_id=task_id,
            max_bytes=_MAX_VIDEO_BYTES,
            request_timeout_seconds=self.config.request_timeout_seconds,
            abort=abort,
            deadline=deadline,
            on_http_error=lambda exc: _raise_http_error(exc, gone=RESULT_GONE_STATUSES),
            logger=log,
        )
        if len(body) > _MAX_VIDEO_BYTES:
            raise PermanentError("provider_response_invalid", "MiniMax video exceeds 100 MB")
        if len(body) < 12 or body[4:8] != b"ftyp":
            raise PermanentError("provider_response_invalid", "MiniMax output is not an MP4 file")
        return body


def _extract_video_url(output: Any) -> str | None:
    if isinstance(output, str) and output:
        return output
    if isinstance(output, list):
        for item in output:
            if isinstance(item, str) and item:
                return item
    return None


def _raise_http_error(
    exc: urllib.error.HTTPError, *, gone: frozenset[int] = frozenset()
) -> NoReturn:
    log.warning("MiniMax HTTP request failed", extra={"status": exc.code})
    if exc.code in (408, 429) or 500 <= exc.code < 600:
        raise TransientError(f"MiniMax HTTP {exc.code}") from exc
    if exc.code in gone:
        raise VendorResultGone(f"MiniMax rejected the request with HTTP {exc.code}") from exc
    raise PermanentError(
        "provider_request_failed", f"MiniMax rejected the request with HTTP {exc.code}"
    ) from exc
