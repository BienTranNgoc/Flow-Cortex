from __future__ import annotations

import io
import json
import urllib.request
from typing import Any
from uuid import uuid4

import pytest

from flowcortex_worker.errors import PermanentError
from flowcortex_worker.provider.__main__ import _build_adapter
from flowcortex_worker.provider.adapter import VideoRequest
from flowcortex_worker.provider.minimax_adapter import (
    PROVIDER_MINIMAX,
    MinimaxAdapter,
    MinimaxConfig,
)
from flowcortex_worker.provider.vendor_jobs import VendorJobEnded

_PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 32
_JPG = b"\xff\xd8\xff\xe0" + b"\x00" * 32
_MP4 = b"\x00\x00\x00\x18ftypmp42" + b"\x00" * 32


class _FakeAbort:
    def check(self) -> None:
        return None


class _FakeResponse:
    def __init__(self, body: bytes) -> None:
        self._stream = io.BytesIO(body)

    def read(self, amt: int = -1) -> bytes:
        return self._stream.read(amt)

    def __enter__(self) -> _FakeResponse:
        return self

    def __exit__(self, *args: Any) -> None:
        return None


def test_minimax_video_identity_validates_config() -> None:
    adapter = MinimaxAdapter(MinimaxConfig(api_key="tok", resolution="768p", duration=5))
    ident = adapter.video_identity()
    assert ident.provider == PROVIDER_MINIMAX
    assert ident.model == "minimax/h3-lite"
    assert ident.resolution == "768p"
    assert ident.duration_seconds == 5
    assert ident.aspect_ratio == "9:16"

    with pytest.raises(PermanentError, match="480P or 768P"):
        MinimaxAdapter(MinimaxConfig(api_key="tok", resolution="1080P")).video_identity()

    assert (
        MinimaxAdapter(MinimaxConfig(api_key="tok", duration=10))
        .video_identity()
        .duration_seconds
        == 10
    )

    with pytest.raises(PermanentError, match="between 4 and 10 seconds"):
        MinimaxAdapter(MinimaxConfig(api_key="tok", duration=11)).video_identity()


def test_minimax_submit_uploads_multiple_reference_images(monkeypatch: pytest.MonkeyPatch) -> None:
    calls: list[tuple[str, str, str, bytes | None]] = []
    file_counter = 0

    def fake_urlopen(req: urllib.request.Request, timeout: float = 0) -> _FakeResponse:
        nonlocal file_counter
        content_type = req.get_header("Content-type") or ""
        assert req.data is None or isinstance(req.data, bytes)
        calls.append((req.get_method(), req.full_url, content_type, req.data))
        if req.full_url.endswith("/files"):
            file_counter += 1
            payload = {"urls": {"get": f"https://api.replicate.com/v1/files/f-{file_counter}"}}
            return _FakeResponse(json.dumps(payload).encode())
        return _FakeResponse(json.dumps({"id": "pred-mm-1"}).encode())

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)
    adapter = MinimaxAdapter(MinimaxConfig(api_key="tok"))
    req = VideoRequest(
        prompt="Vertical 9:16 smooth product showcase",
        source_revision_id=uuid4(),
        reference_image_bytes=_PNG,
        reference_images=(_PNG, _JPG, _PNG),
    )

    task_id = adapter.submit_video(req)

    assert task_id == "pred-mm-1"
    assert len(calls) == 4
    for idx in range(3):
        method, url, ctype, data = calls[idx]
        assert method == "POST"
        assert url == "https://api.replicate.com/v1/files"
        assert ctype.startswith("multipart/form-data; boundary=")
        assert data is not None and b'name="content"' in data

    pred_method, pred_url, _, pred_data = calls[3]
    assert pred_method == "POST"
    assert pred_url == "https://api.replicate.com/v1/models/minimax/h3-lite/predictions"
    body = json.loads((pred_data or b"{}").decode())
    assert body["input"] == {
        "prompt": "Vertical 9:16 smooth product showcase",
        "duration": 5,
        "resolution": "768P",
        "ratio": "9:16",
        "reference_image_urls": [
            "https://api.replicate.com/v1/files/f-1",
            "https://api.replicate.com/v1/files/f-2",
            "https://api.replicate.com/v1/files/f-3",
        ],
    }


def test_minimax_submit_first_frame_omits_reference_image_urls(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[tuple[str, str, bytes | None]] = []

    def fake_urlopen(req: urllib.request.Request, timeout: float = 0) -> _FakeResponse:
        assert req.data is None or isinstance(req.data, bytes)
        calls.append((req.get_method(), req.full_url, req.data))
        if req.full_url.endswith("/files"):
            payload = {"urls": {"get": "https://api.replicate.com/v1/files/kf-0"}}
            return _FakeResponse(json.dumps(payload).encode())
        return _FakeResponse(json.dumps({"id": "pred-mm-kf"}).encode())

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)
    adapter = MinimaxAdapter(MinimaxConfig(api_key="tok", resolution="480P"))
    req = VideoRequest(
        prompt="Locked eye-level MC presenter shot",
        source_revision_id=uuid4(),
        reference_image_bytes=_PNG,
        reference_images=(_PNG, _JPG),
        first_frame_image_bytes=_JPG,
        resolution="480P",
    )

    task_id = adapter.submit_video(req)

    assert task_id == "pred-mm-kf"
    assert len(calls) == 2
    assert calls[0][1] == "https://api.replicate.com/v1/files"
    body = json.loads((calls[1][2] or b"{}").decode())
    assert body["input"] == {
        "prompt": "Locked eye-level MC presenter shot",
        "duration": 5,
        "resolution": "480P",
        "ratio": "9:16",
        "first_frame_image": "https://api.replicate.com/v1/files/kf-0",
    }
    assert "reference_image_urls" not in body["input"]


def test_minimax_await_and_cancel(monkeypatch: pytest.MonkeyPatch) -> None:
    responses = iter([
        json.dumps({"status": "processing"}).encode(),
        json.dumps({
            "status": "succeeded",
            "output": ["https://replicate.delivery/xezq/clip.mp4"],
        }).encode(),
        _MP4,
        json.dumps({"status": "canceled"}).encode(),
    ])

    def fake_urlopen(req: urllib.request.Request, timeout: float = 0) -> _FakeResponse:
        return _FakeResponse(next(responses))

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)
    adapter = MinimaxAdapter(MinimaxConfig(api_key="tok", poll_interval_seconds=0.0))

    artifact = adapter.await_video("pred-mm-1", _FakeAbort())
    assert artifact.provider_task_id == "pred-mm-1"
    assert artifact.body == _MP4

    cancelled = adapter.cancel_video("pred-mm-1")
    assert cancelled.confirmed is True
    assert cancelled.status == "canceled"


def test_minimax_await_raises_on_failed_prediction(monkeypatch: pytest.MonkeyPatch) -> None:
    def fake_urlopen(req: urllib.request.Request, timeout: float = 0) -> _FakeResponse:
        return _FakeResponse(json.dumps({"status": "failed"}).encode())

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)
    adapter = MinimaxAdapter(MinimaxConfig(api_key="tok"))
    with pytest.raises(VendorJobEnded):
        adapter.await_video("pred-mm-2", _FakeAbort())


def test_build_adapter_selects_minimax_with_parallel_clips(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("VIDEO_PROVIDER", "minimax")
    monkeypatch.setenv("MINIMAX_API_KEY", "rep-secret")
    monkeypatch.setenv("MINIMAX_RESOLUTION", "480p")
    monkeypatch.setenv("PRODUCT_VIDEO_PARALLEL_CLIPS_ENABLED", "true")

    adapter = _build_adapter(video_enabled=True, parallel_clips_enabled=True)
    ident = adapter.video_identity()
    assert ident.provider == PROVIDER_MINIMAX
    assert ident.model == "minimax/h3-lite"
    assert ident.resolution == "480p"

    monkeypatch.setenv("MINIMAX_DURATION", "10")
    with pytest.raises(RuntimeError, match="MINIMAX_DURATION=5"):
        _build_adapter(video_enabled=True, parallel_clips_enabled=True)


def test_replicate_download_fail_fast_handshake_and_retry_across_adapters(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    import urllib.error

    from flowcortex_worker.provider.keyframe_adapter import (
        SeedreamConfig,
        SeedreamKeyframeAdapter,
    )
    from flowcortex_worker.provider.lipsync_adapter import (
        KlingLipsyncAdapter,
        KlingLipsyncConfig,
    )
    from flowcortex_worker.provider.vendor_jobs import (
        REPLICATE_DOWNLOAD_ATTEMPTS,
        REPLICATE_HANDSHAKE_TIMEOUT_SECONDS,
    )

    recorded_timeouts: list[float] = []
    sleeps: list[float] = []

    class _AbortWithWait:
        def check(self) -> None:
            return None

        def wait(self, delay: float) -> None:
            sleeps.append(delay)

    attempts = {"count": 0}

    def fake_urlopen(req: urllib.request.Request, timeout: float = 0) -> _FakeResponse:
        if "replicate.delivery" in req.full_url:
            recorded_timeouts.append(timeout)
            attempts["count"] += 1
            if attempts["count"] <= 2:
                raise urllib.error.URLError("handshake timed out")
            return _FakeResponse(_MP4)
        return _FakeResponse(
            json.dumps({
                "status": "succeeded",
                "output": "https://replicate.delivery/xezq/out.mp4",
            }).encode()
        )

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)

    mm = MinimaxAdapter(MinimaxConfig(api_key="tok", request_timeout_seconds=60.0))
    artifact = mm.await_video("pred-mm-retry", _AbortWithWait())
    assert artifact.body == _MP4
    assert attempts["count"] == 3
    assert recorded_timeouts == [REPLICATE_HANDSHAKE_TIMEOUT_SECONDS] * 3
    assert sleeps == [1.0, 2.0]

    # Same fail-fast handshake & retry behavior applies to Kling LipSync and Seedream
    attempts["count"] = 0
    recorded_timeouts.clear()
    sleeps.clear()
    ls = KlingLipsyncAdapter(KlingLipsyncConfig(api_key="tok", request_timeout_seconds=60.0))
    ls_artifact = ls.await_lipsync("pred-ls-retry", _AbortWithWait())
    assert ls_artifact.body == _MP4
    assert attempts["count"] == 3
    assert recorded_timeouts == [REPLICATE_HANDSHAKE_TIMEOUT_SECONDS] * 3

    # Verify Seedream uses the full shared retry budget (8 attempts) before raising TransientError
    from flowcortex_worker.errors import TransientError

    kf_attempts = {"count": 0}
    kf_timeouts: list[float] = []

    def always_timeout(req: urllib.request.Request, timeout: float = 0) -> _FakeResponse:
        if "replicate.delivery" in req.full_url:
            kf_attempts["count"] += 1
            kf_timeouts.append(timeout)
            raise urllib.error.URLError("handshake timed out")
        return _FakeResponse(
            json.dumps({
                "status": "succeeded",
                "output": ["https://replicate.delivery/xezq/kf.jpg"],
            }).encode()
        )

    monkeypatch.setattr(urllib.request, "urlopen", always_timeout)
    kf = SeedreamKeyframeAdapter(SeedreamConfig(api_key="tok", request_timeout_seconds=60.0))
    with pytest.raises(TransientError, match="Seedream download failed: URLError"):
        kf.await_keyframe("pred-kf-retry", _AbortWithWait())
    assert kf_attempts["count"] == REPLICATE_DOWNLOAD_ATTEMPTS
    assert kf_timeouts == [REPLICATE_HANDSHAKE_TIMEOUT_SECONDS] * REPLICATE_DOWNLOAD_ATTEMPTS


def test_replicate_download_retries_on_truncated_content_length(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class _HeadersResp(_FakeResponse):
        def __init__(self, body: bytes, content_length: int) -> None:
            super().__init__(body)
            self.headers = {"Content-Length": str(content_length)}

    class _AbortNoSleep:
        def check(self) -> None:
            return None

        def wait(self, _delay: float) -> None:
            return None

    dl_calls = {"count": 0}

    def fake_urlopen(req: urllib.request.Request, timeout: float = 0) -> _FakeResponse:
        if "replicate.delivery" in req.full_url:
            dl_calls["count"] += 1
            if dl_calls["count"] == 1:
                # Truncated body: claims full _MP4 length + 100 bytes, returns only _MP4
                return _HeadersResp(_MP4, len(_MP4) + 100)
            return _HeadersResp(_MP4, len(_MP4))
        return _FakeResponse(
            json.dumps({
                "status": "succeeded",
                "output": "https://replicate.delivery/xezq/out.mp4",
            }).encode()
        )

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)
    mm = MinimaxAdapter(MinimaxConfig(api_key="tok", request_timeout_seconds=60.0))
    artifact = mm.await_video("pred-mm-trunc", _AbortNoSleep())
    assert artifact.body == _MP4
    assert dl_calls["count"] == 2


def test_replicate_download_switches_to_fallback_relay_after_direct_failures(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    import urllib.error

    class _AbortNoSleep:
        def check(self) -> None:
            return None

        def wait(self, _delay: float) -> None:
            return None

    seen_urls: list[str] = []

    def fake_urlopen(req: urllib.request.Request, timeout: float = 0) -> _FakeResponse:
        if req.full_url.startswith("https://api.replicate.com/"):
            return _FakeResponse(
                json.dumps({
                    "status": "succeeded",
                    "output": "https://replicate.delivery/xezq/out.mp4?sig=1",
                }).encode()
            )
        seen_urls.append(req.full_url)
        if req.full_url.startswith("https://replicate.delivery/"):
            raise urllib.error.URLError("ISP TLS drop")
        return _FakeResponse(_MP4)

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)
    monkeypatch.setenv("REPLICATE_DELIVERY_FALLBACK_BASE_URL", "https://relay.example.com/")
    mm = MinimaxAdapter(MinimaxConfig(api_key="tok", request_timeout_seconds=60.0))
    artifact = mm.await_video("pred-mm-relay", _AbortNoSleep())
    assert artifact.body == _MP4
    assert seen_urls == [
        "https://replicate.delivery/xezq/out.mp4?sig=1",
        "https://replicate.delivery/xezq/out.mp4?sig=1",
        "https://relay.example.com/replicate.delivery/xezq/out.mp4?sig=1",
    ]

    # Malformed / non-HTTPS / query-bearing fallback URLs are ignored safely
    from flowcortex_worker.provider.vendor_jobs import _build_replicate_fallback_url

    for bad_base in (
        "http://insecure.local",
        "https://[bad-ipv6",
        "https://relay.example.com?foo=bar",
        "https://relay.example.com#frag",
    ):
        assert (
            _build_replicate_fallback_url("https://replicate.delivery/xezq/out.mp4", bad_base)
            is None
        )
