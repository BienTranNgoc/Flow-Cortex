"""Google Gemini API adapter for a portrait Veo clip normalized to five seconds."""

from __future__ import annotations

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
    SpeechArtifact,
    SpeechIdentity,
    SpeechRequest,
    VideoAbort,
    VideoArtifact,
    VideoIdentity,
    VideoRequest,
)
from .contracts.provider_job import ProductAdPayload

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

    def generate_script(self, payload: ProductAdPayload) -> str:
        raise PermanentError(
            "provider_capability_unavailable", "Veo script generation is not implemented"
        )

    def speech_identity(self) -> SpeechIdentity:
        raise PermanentError(
            "provider_capability_unavailable", "Veo speech generation is not implemented"
        )

    def generate_speech(self, request: SpeechRequest) -> SpeechArtifact:
        raise PermanentError(
            "provider_capability_unavailable", "Veo speech generation is not implemented"
        )

    def video_identity(self) -> VideoIdentity:
        return VideoIdentity(PROVIDER_VEO, self.config.model, 5, "9:16", "720p", False)

    def submit_video(self, request: VideoRequest) -> str:
        model = urllib.parse.quote(self.config.model, safe="-._")
        body = json.dumps(
            {
                "instances": [{"prompt": request.prompt}],
                "parameters": {
                    "aspectRatio": "9:16",
                    "durationSeconds": "6",
                    "resolution": "720p",
                },
            }
        ).encode("utf-8")
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
        task_url = f"{self.config.base_url.rstrip('/')}/{task_id}"
        while True:
            abort.check()
            response = self._json_request(task_url, method="GET")
            if response.get("done") is True:
                error = response.get("error")
                if isinstance(error, dict):
                    code = str(error.get("code", "unknown"))[:100]
                    log.warning("Veo operation failed", extra={"providerErrorCode": code})
                    raise PermanentError(
                        "provider_request_failed", "Veo operation completed with an error"
                    )
                video_url = _video_uri(response)
                return VideoArtifact(self._download_video(video_url), task_id)
            if time.monotonic() >= deadline:
                raise TransientError("Veo operation polling timed out")
            remaining = max(0.0, deadline - time.monotonic())
            time.sleep(min(self.config.poll_interval_seconds, remaining))

    def _json_request(self, url: str, *, method: str, body: bytes | None = None) -> dict[str, Any]:
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
            _raise_http_error(exc)
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
        parsed = urllib.parse.urlparse(url)
        if (
            parsed.scheme != "https"
            or not parsed.hostname
            or not parsed.hostname.endswith(".googleapis.com")
        ):
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
            _raise_http_error(exc)
        except (urllib.error.URLError, TimeoutError) as exc:
            raise TransientError(f"Veo video download failed: {type(exc).__name__}") from exc
        if len(body) > _MAX_VIDEO_BYTES:
            raise PermanentError("provider_response_invalid", "Veo video exceeds 100 MB")
        if len(body) < 12 or body[4:8] != b"ftyp":
            raise PermanentError("provider_response_invalid", "Veo output is not an MP4 file")
        return body


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


def _raise_http_error(exc: urllib.error.HTTPError) -> NoReturn:
    log.warning("Veo HTTP request failed", extra={"status": exc.code})
    if exc.code in (408, 429) or 500 <= exc.code < 600:
        raise TransientError(f"Veo HTTP {exc.code}") from exc
    raise PermanentError(
        "provider_request_failed", f"Veo rejected the request with HTTP {exc.code}"
    ) from exc
