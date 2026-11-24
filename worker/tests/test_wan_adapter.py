from __future__ import annotations

import json
import urllib.error
from typing import Any

from flowcortex_worker.provider.wan_adapter import WanAdapter, WanConfig


class _Response:
    def __init__(self, body: bytes) -> None:
        self.body = body

    def __enter__(self) -> _Response:
        return self

    def __exit__(self, *args: Any) -> None:
        return None

    def read(self, limit: int = -1) -> bytes:
        return self.body if limit < 0 else self.body[:limit]


def test_download_retries_transient_cdn_failures(monkeypatch: Any) -> None:
    calls = 0
    video = b"\x00\x00\x00\x18ftypisom" + b"\x00" * 32

    def urlopen(request: Any, timeout: float) -> _Response:
        nonlocal calls
        calls += 1
        if calls < 3:
            raise urllib.error.URLError("temporary CDN failure")
        return _Response(video)

    monkeypatch.setattr("urllib.request.urlopen", urlopen)
    monkeypatch.setattr("time.sleep", lambda seconds: None)

    adapter = WanAdapter(WanConfig(api_key="test-token"))

    assert adapter._download_video("https://replicate.delivery/output.mp4") == video
    assert calls == 3


def test_cancel_video_succeeds_and_checks_endpoint_and_timeout(monkeypatch: Any) -> None:
    seen: list[tuple[str, str, float]] = []

    def urlopen(request: Any, timeout: float) -> _Response:
        seen.append((request.get_method(), request.full_url, timeout))
        return _Response(json.dumps({"id": "pred-123", "status": "canceled"}).encode())

    monkeypatch.setattr("urllib.request.urlopen", urlopen)
    monkeypatch.setattr("time.sleep", lambda s: (_ for _ in ()).throw(AssertionError("no sleep")))

    adapter = WanAdapter(WanConfig(api_key="test-token", base_url="https://api.replicate.com/v1"))
    result = adapter.cancel_video("pred-123")

    assert result.confirmed is True
    assert result.status == "canceled"
    assert seen == [("POST", "https://api.replicate.com/v1/predictions/pred-123/cancel", 10.0)]


def test_cancel_video_retries_three_times_with_one_and_two_second_waits(
    monkeypatch: Any, caplog: Any
) -> None:
    calls = 0
    sleeps: list[float] = []

    def urlopen(request: Any, timeout: float) -> _Response:
        nonlocal calls
        calls += 1
        assert timeout == 10.0
        raise urllib.error.URLError("timeout")

    monkeypatch.setattr("urllib.request.urlopen", urlopen)
    monkeypatch.setattr("time.sleep", sleeps.append)

    adapter = WanAdapter(WanConfig(api_key="test-token"))
    result = adapter.cancel_video("pred-unconfirmed-99")

    assert result.confirmed is False
    assert result.reason is not None
    assert calls == 3
    assert sleeps == [1.0, 2.0]
    assert "pred-unconfirmed-99" in caplog.text


def test_cancel_video_treats_already_succeeded_prediction_as_terminal(monkeypatch: Any) -> None:
    def urlopen(request: Any, timeout: float) -> _Response:
        return _Response(json.dumps({"id": "pred-done", "status": "succeeded"}).encode())

    monkeypatch.setattr("urllib.request.urlopen", urlopen)
    adapter = WanAdapter(WanConfig(api_key="test-token"))
    result = adapter.cancel_video("pred-done")

    assert result.confirmed is True
    assert result.status == "succeeded"


def test_cancel_video_unconfirmed_when_status_still_processing_after_retries(
    monkeypatch: Any,
) -> None:
    calls = 0
    sleeps: list[float] = []

    def urlopen(request: Any, timeout: float) -> _Response:
        nonlocal calls
        calls += 1
        return _Response(json.dumps({"id": "pred-busy", "status": "processing"}).encode())

    monkeypatch.setattr("urllib.request.urlopen", urlopen)
    monkeypatch.setattr("time.sleep", sleeps.append)

    adapter = WanAdapter(WanConfig(api_key="test-token"))
    result = adapter.cancel_video("pred-busy")

    assert result.confirmed is False
    assert result.reason is not None
    assert "processing" in result.reason
    assert calls == 3
    assert sleeps == [1.0, 2.0]
