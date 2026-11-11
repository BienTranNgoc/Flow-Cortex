"""Google Gemini API adapter for a portrait Veo clip normalized to five seconds."""

from __future__ import annotations

import base64
import json
import logging
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any, NoReturn

from ..errors import PermanentError, TransientError
from .adapter import (
    Abort,
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
    VendorJobEnded,
    VendorJobTimeout,
    VendorResultGone,
    probe_json,
    probe_media,
    vendor_scope,
)

log = logging.getLogger("flowcortex_provider_worker.veo")

PROVIDER_VEO = "veo"
_MAX_VIDEO_BYTES = 100 * 1024 * 1024


@dataclass(frozen=True)
class VeoConfig:
    api_key: str
    base_url: str = "https://generativelanguage.googleapis.com/v1beta"
    model: str = "veo-3.1-fast-generate-preview"
    request_timeout_seconds: float = 60.0
    poll_interval_seconds: float = 10.0
    poll_timeout_seconds: float = 480.0


@dataclass(frozen=True)
class VeoAdapter:
    config: VeoConfig

    def generate_script(
        self, payload: ProductAdPayload, reference_image: bytes | None = None
    ) -> str:
        raise PermanentError(
            "provider_capability_unavailable", "Veo script generation is not implemented"
        )

    def generate_storyboard(
        self, request: StoryboardRequest, reference_image: bytes | None = None
    ) -> StoryboardArtifact:
        raise PermanentError(
            "provider_capability_unavailable", "Veo storyboard generation is not implemented"
        )

    def speech_identity(self) -> SpeechIdentity:
        raise PermanentError(
            "provider_capability_unavailable", "Veo speech generation is not implemented"
        )

    def generate_speech(self, request: SpeechRequest, abort: Abort) -> SpeechArtifact:
        raise PermanentError(
            "provider_capability_unavailable", "Veo speech generation is not implemented"
        )

    def video_identity(self) -> VideoIdentity:
        return VideoIdentity(PROVIDER_VEO, self.config.model, 5, "9:16", "720p", False)

    def submit_video(self, request: VideoRequest) -> str:
        model = urllib.parse.quote(self.config.model, safe="-._")
        instance: dict[str, Any] = {"prompt": request.prompt}
        if request.reference_image_bytes:
            instance["image"] = {
                "inlineData": {
                    "mimeType": _image_mime(request.reference_image_bytes),
                    "data": base64.b64encode(request.reference_image_bytes).decode("ascii"),
                }
            }
        body = json.dumps(
            {
                "instances": [instance],
                "parameters": {
                    "aspectRatio": "9:16",
                    "durationSeconds": "6",
                    "resolution": "720p",
                },
            }
        ).encode()
        response = self._json_request(
            f"{self.config.base_url.rstrip('/')}/models/{model}:predictLongRunning",
            method="POST",
            body=body,
        )
        operation_name = response.get("name")
        if not isinstance(operation_name, str) or not operation_name.startswith("operations/"):
            raise PermanentError(
                "provider_response_invalid", "Veo submit response has no operation name"
            )
        return operation_name

    def await_video(self, task_id: str, abort: VideoAbort) -> VideoArtifact:
        if not task_id.startswith("operations/") or ".." in task_id:
            raise PermanentError("provider_response_invalid", "Veo operation name is invalid")
        deadline = time.monotonic() + self.config.poll_timeout_seconds
        task_url = self._task_url(task_id)
        while True:
            abort.check()
            response = self._json_request(task_url, method="GET", task_lookup=True)
            if response.get("done") is True:
                error = response.get("error")
                if isinstance(error, dict):
                    code = str(error.get("code", "unknown"))[:100]
                    log.warning("Veo operation failed", extra={"providerErrorCode": code})
                    raise VendorJobEnded("failed", "Veo operation completed with an error")
                video_url = _video_uri(response)
                return VideoArtifact(self._download_video(video_url), task_id)
            if time.monotonic() >= deadline:
                raise VendorJobTimeout(PROVIDER_VEO, task_id)
            remaining = max(0.0, deadline - time.monotonic())
            time.sleep(min(self.config.poll_interval_seconds, remaining))

    def probe_video(self, task_id: str) -> VendorProbe:
        if not task_id.startswith("operations/") or ".." in task_id:
            return "gone"
        headers = {"x-goog-api-key": self.config.api_key}
        body = probe_json(
            self._task_url(task_id), headers, self.config.request_timeout_seconds
        )
        if body is None:
            return "gone"
        if body.get("done") is not True:
            return "running"
        if isinstance(body.get("error"), dict):
            return "failed"
        try:
            video_url = _video_uri(body)
        except PermanentError:
            return "gone"
        if not _is_google_api_url(video_url):
            return "gone"
        return probe_media(video_url, headers, self.config.request_timeout_seconds)

    def vendor_scope(self) -> dict[str, str]:
        return vendor_scope(self.config.base_url, self.config.api_key)

    def _task_url(self, task_id: str) -> str:
        return f"{self.config.base_url.rstrip('/')}/{task_id}"

    def _json_request(
        self,
        url: str,
        *,
        method: str,
        body: bytes | None = None,
        task_lookup: bool = False,
    ) -> dict[str, Any]:
        request = urllib.request.Request(
            url,
            data=body,
            method=method,
            headers={
                "x-goog-api-key": self.config.api_key,
                "Content-Type": "application/json",
            },
        )
        try:
            with urllib.request.urlopen(
                request, timeout=self.config.request_timeout_seconds
            ) as response:
                response_body = response.read()
        except urllib.error.HTTPError as exc:
            _raise_http_error(exc, gone=frozenset({404}) if task_lookup else frozenset())
        except (urllib.error.URLError, TimeoutError) as exc:
            log.warning("Veo connection failed", extra={"errorType": type(exc).__name__})
            raise TransientError(f"Veo connection failed: {type(exc).__name__}") from exc
        try:
            parsed = json.loads(response_body)
        except json.JSONDecodeError as exc:
            raise PermanentError(
                "provider_response_invalid", "Veo response was not valid JSON"
            ) from exc
        if not isinstance(parsed, dict):
            raise PermanentError("provider_response_invalid", "Veo response was not an object")
        return parsed

    def _download_video(self, url: str) -> bytes:
        if not _is_google_api_url(url):
            raise PermanentError(
                "provider_response_invalid", "Veo video URL is outside Google APIs"
            )
        request = urllib.request.Request(
            url, method="GET", headers={"x-goog-api-key": self.config.api_key}
        )
        try:
            with urllib.request.urlopen(
                request, timeout=self.config.request_timeout_seconds
            ) as response:
                body = response.read(_MAX_VIDEO_BYTES + 1)
        except urllib.error.HTTPError as exc:
            _raise_http_error(exc, gone=RESULT_GONE_STATUSES)
        except (urllib.error.URLError, TimeoutError) as exc:
            raise TransientError(f"Veo video download failed: {type(exc).__name__}") from exc
        if len(body) > _MAX_VIDEO_BYTES:
            raise PermanentError("provider_response_invalid", "Veo video exceeds 100 MB")
        if len(body) < 12 or body[4:8] != b"ftyp":
            raise PermanentError("provider_response_invalid", "Veo output is not an MP4 file")
        return bytes(body)


def _video_uri(response: dict[str, Any]) -> str:
    try:
        uri = response["response"]["generateVideoResponse"]["generatedSamples"][0]["video"]["uri"]
    except (KeyError, IndexError, TypeError) as exc:
        raise PermanentError(
            "provider_response_invalid", "Veo completed without a video URI"
        ) from exc
    if not isinstance(uri, str) or not uri:
        raise PermanentError("provider_response_invalid", "Veo video URI is invalid")
    return uri


def _image_mime(body: bytes) -> str:
    if body[:8] == b"\x89PNG\r\n\x1a\n":
        return "image/png"
    if body[:3] == b"\xff\xd8\xff":
        return "image/jpeg"
    if body[:6] in (b"GIF87a", b"GIF89a"):
        return "image/gif"
    if body[:4] == b"RIFF" and body[8:12] == b"WEBP":
        return "image/webp"
    # Veo only accepts the four formats above; the capability layer already rejected unknowns.
    raise PermanentError("provider_response_invalid", "reference image MIME is unknown")


def _is_google_api_url(url: str) -> bool:
    parsed = urllib.parse.urlparse(url)
    return bool(
        parsed.scheme == "https"
        and parsed.hostname
        and parsed.hostname.endswith(".googleapis.com")
    )


def _raise_http_error(
    exc: urllib.error.HTTPError, *, gone: frozenset[int] = frozenset()
) -> NoReturn:
    log.warning("Veo HTTP request failed", extra={"status": exc.code})
    if exc.code in (408, 429) or 500 <= exc.code < 600:
        raise TransientError(f"Veo HTTP {exc.code}") from exc
    if exc.code in gone:
        raise VendorResultGone(f"Veo rejected the request with HTTP {exc.code}") from exc
    raise PermanentError(
        "provider_request_failed", f"Veo rejected the request with HTTP {exc.code}"
    ) from exc
