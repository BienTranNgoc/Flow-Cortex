"""Replicate adapter for kwaivgi/kling-lip-sync with automatic local 720p upscale."""

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
from typing import Any

from ..errors import PermanentError, TransientError
from .adapter import LipsyncRequest, VendorProbe, VideoAbort, VideoArtifact
from .vendor_jobs import (
    RESULT_GONE_STATUSES,
    WORKER_USER_AGENT,
    VendorJobEnded,
    VendorJobTimeout,
    VendorResultGone,
    probe_json,
    probe_media,
    vendor_scope,
)
from .wan_adapter import _is_replicate_delivery, _string

log = logging.getLogger("flowcortex_provider_worker.kling_lipsync")

PROVIDER_KLING_LIPSYNC = "kling_lipsync"
_MAX_VIDEO_BYTES = 100 * 1024 * 1024
_TERMINAL_FAILURES = {"failed", "canceled"}


@dataclass(frozen=True)
class KlingLipsyncConfig:
    api_key: str
    base_url: str = "https://api.replicate.com/v1"
    model: str = "kwaivgi/kling-lip-sync"
    request_timeout_seconds: float = 60.0
    poll_interval_seconds: float = 5.0
    poll_timeout_seconds: float = 480.0


@dataclass(frozen=True)
class KlingLipsyncAdapter:
    config: KlingLipsyncConfig

    def submit_lipsync(self, request: LipsyncRequest) -> str:
        model_path = "/".join(
            urllib.parse.quote(part, safe="") for part in self.config.model.split("/")
        )
        video_720p = _upscale_to_720p(request.video_bytes)
        video_url = self._upload_binary(video_720p, "clip_720p.mp4", "video/mp4")
        audio_url = self._upload_binary(request.audio_bytes, "mc_hook.wav", "audio/wav")
        body = json.dumps({"input": {"video_url": video_url, "audio_file": audio_url}}).encode()
        response = self._json_request(
            f"{self.config.base_url.rstrip('/')}/models/{model_path}/predictions",
            method="POST",
            body=body,
        )
        task_id = _string(response, "id")
        if not task_id:
            raise PermanentError(
                "provider_response_invalid", "Kling LipSync submit response has no prediction id"
            )
        return task_id

    def await_lipsync(self, task_id: str, abort: VideoAbort) -> VideoArtifact:
        deadline = time.monotonic() + self.config.poll_timeout_seconds
        task_url = f"{self.config.base_url.rstrip('/')}/predictions/{urllib.parse.quote(task_id)}"
        while True:
            abort.check()
            response = self._json_request(task_url, method="GET", task_lookup=True)
            status = (_string(response, "status") or "").lower()
            if status == "succeeded":
                output = response.get("output")
                video_url = output if isinstance(output, str) and output else None
                if not video_url:
                    raise PermanentError(
                        "provider_response_invalid", "Kling LipSync completed without a video URL"
                    )
                return VideoArtifact(self._download_video(video_url), task_id)
            if status in _TERMINAL_FAILURES:
                log.warning("Kling LipSync prediction failed", extra={"taskStatus": status})
                raise VendorJobEnded(
                    "failed", f"Kling LipSync prediction ended with status {status}"
                )
            if status not in {"starting", "processing"}:
                raise PermanentError(
                    "provider_response_invalid", "Kling LipSync returned an unknown status"
                )
            if time.monotonic() >= deadline:
                raise VendorJobTimeout(PROVIDER_KLING_LIPSYNC, task_id)
            time.sleep(
                min(self.config.poll_interval_seconds, max(0.0, deadline - time.monotonic()))
            )

    def probe_lipsync(self, task_id: str) -> VendorProbe:
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
            output = body.get("output")
            if not isinstance(output, str) or not _is_replicate_delivery(output):
                return "gone"
            return probe_media(output, {}, self.config.request_timeout_seconds)
        raise TransientError("Kling LipSync probe read an unknown status")

    def vendor_scope(self) -> dict[str, str]:
        return vendor_scope(self.config.base_url, self.config.api_key)

    def _upload_binary(self, payload: bytes, filename: str, mime: str) -> str:
        boundary = f"----FlowCortexBoundary{uuid.uuid4().hex}"
        body = (
            (
                f"--{boundary}\r\n"
                f'Content-Disposition: form-data; name="content"; filename="{filename}"\r\n'
                f"Content-Type: {mime}\r\n\r\n"
            ).encode()
            + payload
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
            raise TransientError(f"Kling LipSync upload failed: {type(exc).__name__}") from exc
        except json.JSONDecodeError as exc:
            raise PermanentError(
                "provider_response_invalid", "Kling LipSync upload invalid JSON"
            ) from exc
        urls = parsed.get("urls") if isinstance(parsed, dict) else None
        file_url = _string(urls, "get") if isinstance(urls, dict) else None
        if not file_url:
            raise PermanentError(
                "provider_response_invalid", "Kling LipSync upload missing urls.get"
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
            raise TransientError(f"Kling LipSync connection failed: {type(exc).__name__}") from exc
        except json.JSONDecodeError as exc:
            raise PermanentError(
                "provider_response_invalid", "Kling LipSync response invalid JSON"
            ) from exc
        if not isinstance(parsed, dict):
            raise PermanentError(
                "provider_response_invalid", "Kling LipSync response not an object"
            )
        return parsed

    def _download_video(self, url: str) -> bytes:
        if not _is_replicate_delivery(url):
            raise PermanentError("provider_response_invalid", "Kling LipSync URL outside Replicate")
        req = urllib.request.Request(url, method="GET", headers={"User-Agent": WORKER_USER_AGENT})
        try:
            with urllib.request.urlopen(req, timeout=self.config.request_timeout_seconds) as resp:
                body = resp.read(_MAX_VIDEO_BYTES + 1)
        except urllib.error.HTTPError as exc:
            _raise_http_error(exc, gone=RESULT_GONE_STATUSES)
        except (urllib.error.URLError, TimeoutError) as exc:
            raise TransientError(f"Kling LipSync download failed: {type(exc).__name__}") from exc
        if len(body) > _MAX_VIDEO_BYTES or len(body) < 12 or body[4:8] != b"ftyp":
            raise PermanentError(
                "provider_response_invalid", "Kling LipSync output is not a valid MP4"
            )
        return bytes(body)


def _upscale_to_720p(video_bytes: bytes) -> bytes:
    """kwaivgi/kling-lip-sync requires >= 720p input; local ffmpeg upscale is free."""
    with tempfile.TemporaryDirectory(prefix="flowcortex-lipsync-") as directory:
        src = Path(directory) / "in.mp4"
        dst = Path(directory) / "up720p.mp4"
        src.write_bytes(video_bytes)
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
                "scale=720:1280:flags=lanczos:force_original_aspect_ratio=decrease,"
                "pad=720:1280:(ow-iw)/2:(oh-ih)/2:black,setsar=1",
                "-an",
                "-c:v",
                "libx264",
                "-preset",
                "fast",
                "-pix_fmt",
                "yuv420p",
                str(dst),
            ],
            capture_output=True,
            check=False,
        )
        if completed.returncode != 0 or not dst.is_file() or dst.stat().st_size == 0:
            raise PermanentError(
                "provider_response_invalid", "video could not be upscaled for lip-sync"
            )
        return dst.read_bytes()


def _raise_http_error(exc: urllib.error.HTTPError, *, gone: frozenset[int] = frozenset()) -> None:
    if exc.code in (408, 429) or 500 <= exc.code < 600:
        raise TransientError(f"Kling LipSync HTTP {exc.code}") from exc
    if exc.code in gone:
        raise VendorResultGone(f"Kling LipSync rejected request with HTTP {exc.code}") from exc
    raise PermanentError(
        "provider_request_failed", f"Kling LipSync rejected request with HTTP {exc.code}"
    ) from exc
