"""Replicate adapter for 9:16 keyframe compositing via bytedance/seedream-4."""

from __future__ import annotations

import json
import logging
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any, NoReturn

from ..errors import PermanentError, TransientError
from .adapter import KeyframeArtifact, KeyframeRequest, VendorProbe, VideoAbort
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

log = logging.getLogger("flowcortex_provider_worker.seedream")

PROVIDER_SEEDREAM = "seedream"
_MAX_IMAGE_BYTES = 25 * 1024 * 1024
_TERMINAL_FAILURES = {"failed", "canceled"}
_KEYFRAME_WIDTH = 720
_KEYFRAME_HEIGHT = 1280
_MIME_EXTENSIONS = {
    "image/png": "png",
    "image/jpeg": "jpg",
    "image/gif": "gif",
    "image/webp": "webp",
}


@dataclass(frozen=True)
class SeedreamConfig:
    api_key: str
    base_url: str = "https://api.replicate.com/v1"
    model: str = "bytedance/seedream-4"
    size: str = "1K"
    request_timeout_seconds: float = 60.0
    poll_interval_seconds: float = 3.0
    poll_timeout_seconds: float = 180.0


@dataclass(frozen=True)
class SeedreamKeyframeAdapter:
    config: SeedreamConfig

    def submit_keyframe(self, request: KeyframeRequest) -> str:
        model_path = "/".join(
            urllib.parse.quote(part, safe="") for part in self.config.model.split("/")
        )
        uploaded_urls = [
            self._upload_file(img, idx) for idx, img in enumerate(request.reference_images, start=1)
        ]
        input_body: dict[str, Any] = {
            "prompt": request.prompt,
            "aspect_ratio": "9:16",
            "size": self.config.size,
            "max_images": 1,
        }
        if uploaded_urls:
            input_body["image_input"] = uploaded_urls
        response = self._json_request(
            f"{self.config.base_url.rstrip('/')}/models/{model_path}/predictions",
            method="POST",
            body=json.dumps({"input": input_body}).encode(),
        )
        task_id = _string(response, "id")
        if not task_id:
            raise PermanentError(
                "provider_response_invalid", "Seedream submit response has no prediction id"
            )
        return task_id

    def await_keyframe(self, task_id: str, abort: VideoAbort) -> KeyframeArtifact:
        deadline = time.monotonic() + self.config.poll_timeout_seconds
        task_url = f"{self.config.base_url.rstrip('/')}/predictions/{urllib.parse.quote(task_id)}"
        while True:
            abort.check()
            response = self._json_request(task_url, method="GET", task_lookup=True)
            status = (_string(response, "status") or "").lower()
            if status == "succeeded":
                image_url = _extract_output_url(response.get("output"))
                if not image_url:
                    raise PermanentError(
                        "provider_response_invalid", "Seedream completed without an image URL"
                    )
                raw = self._download_image(
                    image_url, task_id=task_id, abort=abort, deadline=deadline
                )
                normalized = _normalize_keyframe_jpeg(raw)
                return KeyframeArtifact(
                    body=normalized,
                    width=_KEYFRAME_WIDTH,
                    height=_KEYFRAME_HEIGHT,
                    provider_task_id=task_id,
                )
            if status in _TERMINAL_FAILURES:
                raise VendorJobEnded("failed", f"Seedream prediction ended with status {status}")
            if status not in {"starting", "processing"}:
                raise PermanentError(
                    "provider_response_invalid", "Seedream returned an unknown prediction status"
                )
            if time.monotonic() >= deadline:
                raise VendorJobTimeout(PROVIDER_SEEDREAM, task_id)
            time.sleep(
                min(self.config.poll_interval_seconds, max(0.0, deadline - time.monotonic()))
            )

    def probe_keyframe(self, task_id: str) -> VendorProbe:
        task_url = f"{self.config.base_url.rstrip('/')}/predictions/{urllib.parse.quote(task_id)}"
        body = probe_json(
            task_url,
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
            url = _extract_output_url(body.get("output"))
            if not url or not _is_replicate_delivery(url):
                return "gone"
            return probe_media(url, {}, self.config.request_timeout_seconds)
        raise TransientError("Seedream probe read an unknown prediction status")

    def vendor_scope(self) -> dict[str, str]:
        return vendor_scope(self.config.base_url, self.config.api_key)

    def _upload_file(self, image_bytes: bytes, index: int) -> str:
        mime = _image_mime(image_bytes)
        ext = _MIME_EXTENSIONS.get(mime, "jpg")
        boundary = f"----FlowCortexBoundary{uuid.uuid4().hex}"
        body = (
            (
                f"--{boundary}\r\n"
                f'Content-Disposition: form-data; name="content"; filename="ref_{index}.{ext}"\r\n'
                f"Content-Type: {mime}\r\n\r\n"
            ).encode()
            + image_bytes
            + f"\r\n--{boundary}--\r\n".encode()
        )
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
            with urllib.request.urlopen(req, timeout=self.config.request_timeout_seconds) as resp:
                parsed = json.loads(resp.read())
        except urllib.error.HTTPError as exc:
            _raise_http_error(exc)
        except (urllib.error.URLError, TimeoutError) as exc:
            raise TransientError(f"Seedream file upload failed: {type(exc).__name__}") from exc
        except json.JSONDecodeError as exc:
            raise PermanentError(
                "provider_response_invalid", "Seedream upload response invalid JSON"
            ) from exc
        urls = parsed.get("urls") if isinstance(parsed, dict) else None
        file_url = _string(urls, "get") if isinstance(urls, dict) else None
        if not file_url:
            raise PermanentError(
                "provider_response_invalid", "Seedream upload response missing urls.get"
            )
        return file_url

    def _json_request(
        self, url: str, *, method: str, body: bytes | None = None, task_lookup: bool = False
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
            with urllib.request.urlopen(req, timeout=self.config.request_timeout_seconds) as resp:
                parsed = json.loads(resp.read())
        except urllib.error.HTTPError as exc:
            _raise_http_error(exc, gone=frozenset({404}) if task_lookup else frozenset())
        except (urllib.error.URLError, TimeoutError) as exc:
            raise TransientError(f"Seedream connection failed: {type(exc).__name__}") from exc
        except json.JSONDecodeError as exc:
            raise PermanentError(
                "provider_response_invalid", "Seedream response invalid JSON"
            ) from exc
        if not isinstance(parsed, dict):
            raise PermanentError("provider_response_invalid", "Seedream response not a JSON object")
        return parsed

    def _download_image(
        self, url: str, *, task_id: str, abort: VideoAbort, deadline: float
    ) -> bytes:
        if not _is_replicate_delivery(url):
            raise PermanentError(
                "provider_response_invalid", "Seedream output URL outside Replicate"
            )
        body = download_replicate_media(
            url,
            provider=PROVIDER_SEEDREAM,
            label="Seedream",
            task_id=task_id,
            max_bytes=_MAX_IMAGE_BYTES,
            request_timeout_seconds=self.config.request_timeout_seconds,
            abort=abort,
            deadline=deadline,
            on_http_error=lambda exc: _raise_http_error(exc, gone=RESULT_GONE_STATUSES),
            logger=log,
        )
        if len(body) > _MAX_IMAGE_BYTES or len(body) < 12:
            raise PermanentError("provider_response_invalid", "Seedream output image size invalid")
        return bytes(body)


def _extract_output_url(output: Any) -> str | None:
    if isinstance(output, str) and output:
        return output
    if isinstance(output, list):
        for item in output:
            if isinstance(item, str) and item:
                return item
    return None


def _normalize_keyframe_jpeg(raw: bytes) -> bytes:
    with tempfile.TemporaryDirectory(prefix="flowcortex-kf-") as directory:
        src = Path(directory) / "in.img"
        dst = Path(directory) / "keyframe.jpg"
        src.write_bytes(raw)
        completed = subprocess.run(
            [
                "ffmpeg",
                "-nostdin",
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-i",
                str(src),
                "-vf",
                f"scale={_KEYFRAME_WIDTH}:{_KEYFRAME_HEIGHT}:force_original_aspect_ratio=increase,"
                f"crop={_KEYFRAME_WIDTH}:{_KEYFRAME_HEIGHT}",
                "-q:v",
                "3",
                str(dst),
            ],
            capture_output=True,
            check=False,
        )
        if completed.returncode != 0 or not dst.is_file() or dst.stat().st_size == 0:
            raise PermanentError(
                "provider_response_invalid", "keyframe image could not be normalized"
            )
        return dst.read_bytes()


def _raise_http_error(
    exc: urllib.error.HTTPError, *, gone: frozenset[int] = frozenset()
) -> NoReturn:
    if exc.code in (408, 429) or 500 <= exc.code < 600:
        raise TransientError(f"Seedream HTTP {exc.code}") from exc
    if exc.code in gone:
        raise VendorResultGone(f"Seedream rejected request with HTTP {exc.code}") from exc
    raise PermanentError(
        "provider_request_failed", f"Seedream rejected request with HTTP {exc.code}"
    ) from exc
