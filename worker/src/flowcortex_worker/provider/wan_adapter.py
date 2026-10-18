"""Replicate adapter for a portrait Wan 2.2 5B Fast video clip."""

from __future__ import annotations

import base64
import json
import logging
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any, NoReturn, cast

from ..errors import PermanentError, TransientError
from .adapter import (
    Abort,
    SpeechArtifact,
    SpeechIdentity,
    SpeechRequest,
    StoryboardArtifact,
    StoryboardRequest,
    VideoAbort,
    VideoArtifact,
    VideoIdentity,
    VideoRequest,
)
from .contracts.provider_job import ProductAdPayload

log = logging.getLogger("flowcortex_provider_worker.wan")

PROVIDER_WAN = "wan"
_MAX_VIDEO_BYTES = 100 * 1024 * 1024
_DOWNLOAD_ATTEMPTS = 4
_TERMINAL_FAILURES = {"failed", "canceled"}


@dataclass(frozen=True)
class WanConfig:
    api_key: str
    base_url: str = "https://api.replicate.com/v1"
    model: str = "wan-video/wan-2.2-5b-fast"
    resolution: str = "480p"
    frames_per_second: int = 24
    num_frames: int = 121
    request_timeout_seconds: float = 60.0
    poll_interval_seconds: float = 5.0
    poll_timeout_seconds: float = 480.0


@dataclass(frozen=True)
class WanAdapter:
    config: WanConfig

    def generate_script(
        self, payload: ProductAdPayload, reference_image: bytes | None = None
    ) -> str:
        raise PermanentError(
            "provider_capability_unavailable", "Wan script generation is not implemented"
        )

    def generate_storyboard(self, request: StoryboardRequest) -> StoryboardArtifact:
        raise PermanentError(
            "provider_capability_unavailable", "Wan storyboard generation is not implemented"
        )

    def speech_identity(self) -> SpeechIdentity:
        raise PermanentError(
            "provider_capability_unavailable", "Wan speech generation is not implemented"
        )

    def generate_speech(self, request: SpeechRequest, abort: Abort) -> SpeechArtifact:
        raise PermanentError(
            "provider_capability_unavailable", "Wan speech generation is not implemented"
        )

    def video_identity(self) -> VideoIdentity:
        if self.config.resolution not in {"480p", "720p"}:
            raise PermanentError(
                "contract_violation", "Wan resolution must be 480p or 720p"
            )
        if not 81 <= self.config.num_frames <= 121:
            raise PermanentError(
                "contract_violation", "Wan num_frames must be between 81 and 121"
            )
        if not 5 <= self.config.frames_per_second <= 30:
            raise PermanentError(
                "contract_violation", "Wan FPS must be between 5 and 30"
            )
        return VideoIdentity(
            PROVIDER_WAN,
            self.config.model,
            5,
            "9:16",
            self.config.resolution,
            False,
        )

    def submit_video(self, request: VideoRequest) -> str:
        model_path = "/".join(
            urllib.parse.quote(part, safe="") for part in self.config.model.split("/")
        )
        input_body: dict[str, Any] = {
            "prompt": request.prompt,
            "go_fast": True,
            "num_frames": self.config.num_frames,
            "resolution": self.config.resolution,
            "aspect_ratio": "9:16",
            "sample_shift": 12,
            "frames_per_second": self.config.frames_per_second,
            "disable_safety_checker": False,
        }
        if request.reference_image_bytes:
            mime = _image_mime(request.reference_image_bytes)
            encoded = base64.b64encode(request.reference_image_bytes).decode("ascii")
            input_body["image"] = f"data:{mime};base64,{encoded}"
        elif request.reference_image_url:
            input_body["image"] = request.reference_image_url
        body = json.dumps({"input": input_body}).encode()
        response = self._json_request(
            f"{self.config.base_url.rstrip('/')}/models/{model_path}/predictions",
            method="POST",
            body=body,
        )
        task_id = _string(response, "id")
        if not task_id:
            raise PermanentError(
                "provider_response_invalid", "Wan submit response has no prediction id"
            )
        return task_id

    def await_video(self, task_id: str, abort: VideoAbort) -> VideoArtifact:
        deadline = time.monotonic() + self.config.poll_timeout_seconds
        task_url = f"{self.config.base_url.rstrip('/')}/predictions/{urllib.parse.quote(task_id)}"
        while True:
            abort.check()
            response = self._json_request(task_url, method="GET")
            status = (_string(response, "status") or "").lower()
            if status == "succeeded":
                output = response.get("output")
                video_url = output if isinstance(output, str) else None
                if not video_url:
                    raise PermanentError(
                        "provider_response_invalid", "Wan completed without a video URL"
                    )
                return VideoArtifact(self._download_video(video_url), task_id)
            if status in _TERMINAL_FAILURES:
                error_detail = _string(response, "error")
                log.warning(
                    "Wan prediction failed",
                    extra={"taskStatus": status, "error": error_detail},
                )
                message = (
                    f"Wan prediction ended with status {status}: {error_detail}"
                    if error_detail
                    else f"Wan prediction ended with status {status}"
                )
                raise PermanentError("provider_request_failed", message)
            if status not in {"starting", "processing"}:
                raise PermanentError(
                    "provider_response_invalid", "Wan returned an unknown prediction status"
                )
            if time.monotonic() >= deadline:
                raise TransientError("Wan prediction polling timed out")
            remaining = max(0.0, deadline - time.monotonic())
            time.sleep(min(self.config.poll_interval_seconds, remaining))

    def _json_request(
        self, url: str, *, method: str, body: bytes | None = None
    ) -> dict[str, Any]:
        request = urllib.request.Request(
            url,
            data=body,
            method=method,
            headers={
                "Authorization": f"Bearer {self.config.api_key}",
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
            log.warning("Wan connection failed", extra={"errorType": type(exc).__name__})
            raise TransientError(f"Wan connection failed: {type(exc).__name__}") from exc
        try:
            parsed = json.loads(response_body)
        except json.JSONDecodeError as exc:
            raise PermanentError(
                "provider_response_invalid", "Wan response was not valid JSON"
            ) from exc
        if not isinstance(parsed, dict):
            raise PermanentError(
                "provider_response_invalid", "Wan response was not a JSON object"
            )
        return parsed

    def _download_video(self, url: str) -> bytes:
        parsed = urllib.parse.urlparse(url)
        hostname = parsed.hostname or ""
        if parsed.scheme != "https" or not (
            hostname == "replicate.delivery" or hostname.endswith(".replicate.delivery")
        ):
            raise PermanentError(
                "provider_response_invalid", "Wan video URL is outside Replicate"
            )
        last_error: TransientError | None = None
        for attempt in range(1, _DOWNLOAD_ATTEMPTS + 1):
            try:
                body = self._download_once(url)
                break
            except TransientError as exc:
                last_error = exc
                if attempt == _DOWNLOAD_ATTEMPTS:
                    raise
                log.warning(
                    "Wan video download will retry",
                    extra={"downloadAttempt": attempt, "errorType": type(exc.__cause__).__name__},
                )
                time.sleep(float(2 ** (attempt - 1)))
        else:  # pragma: no cover - the loop either returns bytes or raises
            raise last_error or TransientError("Wan video download failed")
        if len(body) > _MAX_VIDEO_BYTES:
            raise PermanentError("provider_response_invalid", "Wan video exceeds 100 MB")
        if len(body) < 12 or body[4:8] != b"ftyp":
            raise PermanentError("provider_response_invalid", "Wan output is not an MP4 file")
        return body

    def _download_once(self, url: str) -> bytes:
        try:
            with urllib.request.urlopen(
                urllib.request.Request(url, method="GET"),
                timeout=self.config.request_timeout_seconds,
            ) as response:
                return cast(bytes, response.read(_MAX_VIDEO_BYTES + 1))
        except urllib.error.HTTPError as exc:
            _raise_http_error(exc)
        except (urllib.error.URLError, TimeoutError) as exc:
            raise TransientError(f"Wan video download failed: {type(exc).__name__}") from exc


def _string(value: dict[str, Any], field: str) -> str | None:
    result = value.get(field)
    return result if isinstance(result, str) and result else None


def _raise_http_error(exc: urllib.error.HTTPError) -> NoReturn:
    log.warning("Wan HTTP request failed", extra={"status": exc.code})
    if exc.code in (408, 429) or 500 <= exc.code < 600:
        raise TransientError(f"Wan HTTP {exc.code}") from exc
    raise PermanentError(
        "provider_request_failed", f"Wan rejected the request with HTTP {exc.code}"
    ) from exc


def _image_mime(body: bytes) -> str:
    if body[:8] == b"\x89PNG\r\n\x1a\n":
        return "image/png"
    if body[:3] == b"\xff\xd8\xff":
        return "image/jpeg"
    if body[:6] in (b"GIF87a", b"GIF89a"):
        return "image/gif"
    if body[:4] == b"RIFF" and body[8:12] == b"WEBP":
        return "image/webp"
    raise PermanentError("input_rejected", "product image format is not supported")

