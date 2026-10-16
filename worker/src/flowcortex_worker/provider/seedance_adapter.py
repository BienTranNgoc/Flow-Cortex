"""Alibaba Cloud Model Studio adapter for one five-second Seedance video clip."""

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

log = logging.getLogger("flowcortex_provider_worker.seedance")

PROVIDER_SEEDANCE = "seedance"
_MAX_VIDEO_BYTES = 100 * 1024 * 1024
_TERMINAL_FAILURES = {"FAILED", "CANCELED", "UNKNOWN"}


@dataclass(frozen=True)
class SeedanceConfig:
    api_key: str
    base_url: str
    model: str = "ByteDance-Seedance-1.5-pro"
    request_timeout_seconds: float = 60.0
    poll_interval_seconds: float = 5.0
    poll_timeout_seconds: float = 480.0


@dataclass(frozen=True)
class SeedanceAdapter:
    config: SeedanceConfig

    def generate_script(self, payload: ProductAdPayload) -> str:
        raise PermanentError(
            "provider_capability_unavailable",
            "Seedance script generation is not implemented",
        )

    def speech_identity(self) -> SpeechIdentity:
        raise PermanentError(
            "provider_capability_unavailable",
            "Seedance speech generation is not implemented",
        )

    def generate_speech(self, request: SpeechRequest) -> SpeechArtifact:
        raise PermanentError(
            "provider_capability_unavailable",
            "Seedance speech generation is not implemented",
        )

    def video_identity(self) -> VideoIdentity:
        return VideoIdentity(PROVIDER_SEEDANCE, self.config.model, 5, "9:16", "720p", False)

    def submit_video(self, request: VideoRequest) -> str:
        body = json.dumps(
            {
                "model": self.config.model,
                "input": {"prompt": request.prompt},
                "parameters": {
                    "duration": 5,
                    "resolution": "720p",
                    "ratio": "9:16",
                    "generate_audio": False,
                },
            }
        ).encode("utf-8")
        response = self._json_request(
            f"{self.config.base_url.rstrip('/')}/services/aigc/video-generation/video-synthesis",
            method="POST",
            body=body,
            asynchronous=True,
        )
        task_id = _nested_string(response, "output", "task_id")
        if not task_id:
            raise PermanentError(
                "provider_response_invalid", "Seedance submit response has no task id"
            )
        return task_id

    def await_video(self, task_id: str, abort: VideoAbort) -> VideoArtifact:
        deadline = time.monotonic() + self.config.poll_timeout_seconds
        task_url = f"{self.config.base_url.rstrip('/')}/tasks/{urllib.parse.quote(task_id)}"
        while True:
            abort.check()
            response = self._json_request(task_url, method="GET")
            status = _nested_string(response, "output", "task_status")
            if status == "SUCCEEDED":
                video_url = _nested_string(response, "output", "video_url")
                if not video_url:
                    raise PermanentError(
                        "provider_response_invalid",
                        "Seedance completed without a video URL",
                    )
                return VideoArtifact(self._download_video(video_url), task_id)
            if status in _TERMINAL_FAILURES:
                code = _nested_string(response, "output", "code") or "unknown"
                log.warning(
                    "Seedance task failed",
                    extra={"taskStatus": status, "providerErrorCode": code[:100]},
                )
                raise PermanentError(
                    "provider_request_failed", f"Seedance task ended with status {status}"
                )
            if status not in {"PENDING", "RUNNING"}:
                raise PermanentError(
                    "provider_response_invalid", "Seedance returned an unknown task status"
                )
            if time.monotonic() >= deadline:
                raise TransientError("Seedance task polling timed out")
            remaining = max(0.0, deadline - time.monotonic())
            time.sleep(min(self.config.poll_interval_seconds, remaining))

    def _json_request(
        self,
        url: str,
        *,
        method: str,
        body: bytes | None = None,
        asynchronous: bool = False,
    ) -> dict[str, Any]:
        headers = {
            "Authorization": f"Bearer {self.config.api_key}",
            "Content-Type": "application/json",
        }
        if asynchronous:
            headers["X-DashScope-Async"] = "enable"
        request = urllib.request.Request(url, data=body, method=method, headers=headers)
        try:
            with urllib.request.urlopen(
                request, timeout=self.config.request_timeout_seconds
            ) as response:
                response_body = response.read()
        except urllib.error.HTTPError as exc:
            _raise_http_error(exc)
        except (urllib.error.URLError, TimeoutError) as exc:
            log.warning("Seedance connection failed", extra={"errorType": type(exc).__name__})
            raise TransientError(f"Seedance connection failed: {type(exc).__name__}") from exc
        try:
            parsed = json.loads(response_body)
        except json.JSONDecodeError as exc:
            raise PermanentError(
                "provider_response_invalid", "Seedance response was not valid JSON"
            ) from exc
        if not isinstance(parsed, dict):
            raise PermanentError(
                "provider_response_invalid", "Seedance response was not a JSON object"
            )
        return parsed

    def _download_video(self, url: str) -> bytes:
        parsed = urllib.parse.urlparse(url)
        if (
            parsed.scheme != "https"
            or not parsed.hostname
            or not parsed.hostname.endswith(".aliyuncs.com")
        ):
            raise PermanentError(
                "provider_response_invalid", "Seedance video URL is outside Alibaba Cloud"
            )
        request = urllib.request.Request(url, method="GET")
        try:
            with urllib.request.urlopen(
                request, timeout=self.config.request_timeout_seconds
            ) as response:
                body = response.read(_MAX_VIDEO_BYTES + 1)
        except urllib.error.HTTPError as exc:
            _raise_http_error(exc)
        except (urllib.error.URLError, TimeoutError) as exc:
            raise TransientError(f"Seedance video download failed: {type(exc).__name__}") from exc
        if len(body) > _MAX_VIDEO_BYTES:
            raise PermanentError("provider_response_invalid", "Seedance video exceeds 100 MB")
        if len(body) < 12 or body[4:8] != b"ftyp":
            raise PermanentError(
                "provider_response_invalid", "Seedance output is not an MP4 file"
            )
        return body


def _nested_string(value: dict[str, Any], parent: str, child: str) -> str | None:
    nested = value.get(parent)
    result = nested.get(child) if isinstance(nested, dict) else None
    return result if isinstance(result, str) and result else None


def _raise_http_error(exc: urllib.error.HTTPError) -> NoReturn:
    code = _provider_error_code(exc)
    log.warning(
        "Seedance HTTP request failed",
        extra={"status": exc.code, "providerErrorCode": code},
    )
    if exc.code in (408, 429) or 500 <= exc.code < 600:
        raise TransientError(f"Seedance HTTP {exc.code}") from exc
    raise PermanentError(
        "provider_request_failed", f"Seedance rejected the request with HTTP {exc.code}"
    ) from exc


def _provider_error_code(exc: urllib.error.HTTPError) -> str:
    try:
        parsed = json.loads(exc.read())
        if not isinstance(parsed, dict):
            return "unknown"
        code = parsed.get("code")
        if not code and isinstance(parsed.get("output"), dict):
            code = parsed["output"].get("code")
        return str(code)[:100] if code else "unknown"
    except (json.JSONDecodeError, TypeError, UnicodeDecodeError):
        return "unknown"
