from __future__ import annotations

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

    def read(self, limit: int) -> bytes:
        return self.body[:limit]


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
