"""Unit tests for Slice 3: template prompts, Seedream keyframe and Kling LipSync adapters."""

from __future__ import annotations

import io
import json
import urllib.request
from uuid import UUID

import pytest

from flowcortex_worker.provider.adapter import KeyframeRequest, LipsyncRequest
from flowcortex_worker.provider.keyframe_adapter import SeedreamConfig, SeedreamKeyframeAdapter
from flowcortex_worker.provider.lipsync_adapter import KlingLipsyncAdapter, KlingLipsyncConfig
from flowcortex_worker.provider.prompts.templates import (
    MC_SCRIPT_SYSTEM_PROMPT,
    MC_STORYBOARD_SYSTEM_PROMPT,
    scene_setting_instruction,
)

REV_ID = UUID("a71ea3ba-d72a-4c2c-985c-3a7fd98853c4")
TINY_JPEG = b"\xff\xd8\xff\xe0" + b"\x00" * 32
TINY_MP4 = b"\x00\x00\x00\x18ftypisom" + b"\x00" * 32


def _make_wav_bytes(duration_ms: int, *, comment: str | None = None) -> bytes:
    import wave

    buf = io.BytesIO()
    sample_rate = 24_000
    nframes = round(sample_rate * duration_ms / 1000)
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sample_rate)
        w.writeframes(b"\x00\x00" * nframes)
    raw = buf.getvalue()
    if not comment:
        return raw
    payload = comment.encode("ascii") + b"\x00"
    if len(payload) % 2:
        payload += b"\x00"
    icmt = b"ICMT" + len(payload).to_bytes(4, "little") + payload
    info = b"INFO" + icmt
    list_chunk = b"LIST" + len(info).to_bytes(4, "little") + info
    new_size = (int.from_bytes(raw[4:8], "little") + len(list_chunk)).to_bytes(4, "little")
    return raw[:4] + new_size + raw[8:] + list_chunk


class _FakeResp:
    def __init__(self, body: bytes) -> None:
        self._stream = io.BytesIO(body)

    def read(self, n: int = -1) -> bytes:
        return self._stream.read(n)

    def __enter__(self) -> _FakeResp:
        return self

    def __exit__(self, *args: object) -> None:
        return None


class _NoAbort:
    def check(self) -> None:
        return None


def test_template_prompts_and_scene_directives() -> None:
    assert "mcHookScript" in MC_SCRIPT_SYSTEM_PROMPT
    assert "productCloseScript" in MC_SCRIPT_SYSTEM_PROMPT
    assert "CRITICAL LIP-SYNC SAFETY" in MC_STORYBOARD_SYSTEM_PROMPT
    assert "keyframePrompt" not in MC_STORYBOARD_SYSTEM_PROMPT
    directive = scene_setting_instruction("bg-luxury-dark", "đèn vàng ấm hơn")
    assert "minimalist luxury gallery" in directive
    assert "đèn vàng ấm hơn" in directive


def test_seedream_keyframe_submit_and_await(monkeypatch: pytest.MonkeyPatch) -> None:
    calls: list[str] = []
    file_idx = 0

    def fake_urlopen(req: urllib.request.Request, timeout: float = 0) -> _FakeResp:
        nonlocal file_idx
        calls.append(f"{req.get_method()} {req.full_url}")
        if req.full_url.endswith("/files"):
            file_idx += 1
            return _FakeResp(
                json.dumps(
                    {"urls": {"get": f"https://api.replicate.com/v1/files/f{file_idx}"}}
                ).encode()
            )
        if req.full_url.endswith("/predictions"):
            body = json.loads(req.data.decode())  # type: ignore[union-attr]
            assert body["input"]["aspect_ratio"] == "9:16"
            assert len(body["input"]["image_input"]) == 2
            return _FakeResp(json.dumps({"id": "pred-kf-1", "status": "starting"}).encode())
        if req.full_url.endswith("/predictions/pred-kf-1"):
            return _FakeResp(
                json.dumps(
                    {
                        "id": "pred-kf-1",
                        "status": "succeeded",
                        "output": ["https://replicate.delivery/xezq/kf.jpg"],
                    }
                ).encode()
            )
        return _FakeResp(TINY_JPEG)

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)
    monkeypatch.setattr(
        "flowcortex_worker.provider.keyframe_adapter._normalize_keyframe_jpeg",
        lambda raw: raw,
    )

    adapter = SeedreamKeyframeAdapter(SeedreamConfig(api_key="tok", poll_interval_seconds=0.01))
    task_id = adapter.submit_keyframe(
        KeyframeRequest("MC holding watch", REV_ID, reference_images=(TINY_JPEG, TINY_JPEG))
    )
    assert task_id == "pred-kf-1"
    artifact = adapter.await_keyframe(task_id, _NoAbort())
    assert artifact.width == 720
    assert artifact.height == 1280
    assert artifact.body == TINY_JPEG
    assert len([c for c in calls if "/files" in c]) == 2


def test_kling_lipsync_submit_and_await(monkeypatch: pytest.MonkeyPatch) -> None:
    file_idx = 0

    def fake_urlopen(req: urllib.request.Request, timeout: float = 0) -> _FakeResp:
        nonlocal file_idx
        if req.full_url.endswith("/files"):
            file_idx += 1
            return _FakeResp(
                json.dumps(
                    {"urls": {"get": f"https://api.replicate.com/v1/files/ls{file_idx}"}}
                ).encode()
            )
        if req.full_url.endswith("/predictions"):
            body = json.loads(req.data.decode())  # type: ignore[union-attr]
            assert body["input"]["video_url"].endswith("/ls1")
            assert body["input"]["audio_file"].endswith("/ls2")
            return _FakeResp(json.dumps({"id": "pred-ls-1", "status": "starting"}).encode())
        if req.full_url.endswith("/predictions/pred-ls-1"):
            return _FakeResp(
                json.dumps(
                    {
                        "id": "pred-ls-1",
                        "status": "succeeded",
                        "output": "https://replicate.delivery/xezq/out.mp4",
                    }
                ).encode()
            )
        return _FakeResp(TINY_MP4)

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)
    monkeypatch.setattr(
        "flowcortex_worker.provider.lipsync_adapter._upscale_to_720p", lambda raw, **_: raw
    )

    adapter = KlingLipsyncAdapter(KlingLipsyncConfig(api_key="tok", poll_interval_seconds=0.01))
    task_id = adapter.submit_lipsync(LipsyncRequest(TINY_MP4, b"RIFF0000WAVE", REV_ID))
    assert task_id == "pred-ls-1"
    artifact = adapter.await_lipsync(task_id, _NoAbort())
    assert artifact.body == TINY_MP4
    assert artifact.provider_task_id == "pred-ls-1"


def test_first_frame_changes_idempotency_key_and_vendor_fingerprint() -> None:
    from flowcortex_worker.provider.adapter import VideoIdentity, VideoRequest
    from flowcortex_worker.provider.capabilities import _video_idempotency_key
    from flowcortex_worker.provider.vendor_jobs import vendor_fingerprint

    identity = VideoIdentity("minimax", "minimax/h3-lite", 5, "9:16", "480P", False)
    scope = {"endpoint": "api.replicate.com", "account": "abc"}
    req_a = VideoRequest(
        "MC holding watch",
        REV_ID,
        profile="vertical_ad_clip_v2",
        reference_images=(TINY_JPEG,),
        first_frame_image_bytes=TINY_JPEG + b"A",
    )
    req_b = VideoRequest(
        "MC holding watch",
        REV_ID,
        profile="vertical_ad_clip_v2",
        reference_images=(TINY_JPEG,),
        first_frame_image_bytes=TINY_JPEG + b"B",
    )
    assert _video_idempotency_key(req_a, identity) != _video_idempotency_key(req_b, identity)
    assert vendor_fingerprint(identity, scope, req_a) != vendor_fingerprint(identity, scope, req_b)


def test_capabilities_dispatch_keyframe_and_lipsync(monkeypatch: pytest.MonkeyPatch) -> None:
    from flowcortex_worker.provider.adapter import FixtureProviderAdapter
    from flowcortex_worker.provider.capabilities import ExecutionContext, execute
    from flowcortex_worker.provider.contracts.provider_job import ProviderJob

    class _FakeStorage:
        def __init__(self, blobs: dict[str, bytes]) -> None:
            self._blobs = blobs

        def head_object(self, bucket: str, key: str) -> None:
            return None

        def fetch_bytes(self, bucket: str, key: str, abort: object) -> bytes:
            return self._blobs[key]

        def put(self, key: str, body: bytes, mime: str, abort: object) -> int:
            return len(body)

        def presigned_get_url(self, bucket: str, key: str, *, expires_seconds: int = 300) -> str:
            return f"https://example.test/{key}"

    monkeypatch.setattr(
        "flowcortex_worker.provider.capabilities._normalize_video",
        lambda body, ctx, **kw: body,
    )
    adapter = FixtureProviderAdapter()
    kf_job = ProviderJob.model_validate(
        {
            "schemaVersion": "1",
            "jobId": str(REV_ID),
            "runId": str(REV_ID),
            "correlationId": str(REV_ID),
            "stepOrder": 4,
            "capability": "generate_keyframe",
            "params": {
                "profile": "vertical_ad_keyframe_v1",
                "payload": {
                    "prompt": "MC holding watch in studio",
                    "sourceRevisionId": str(REV_ID),
                    "scenePreset": "bg-studio-minimal",
                },
            },
            "inputs": [{"key": "product_1", "bucket": "b", "objectKey": "k/p1.jpg"}],
            "outputPrefix": f"runs/{REV_ID}/steps/4/attempts/1/",
            "attempt": 1,
            "maxRuntimeSeconds": 180,
            "issuedAt": "2026-11-23T00:00:00Z",
        }
    )
    ctx = ExecutionContext(
        storage=_FakeStorage({"k/p1.jpg": TINY_JPEG}),
        bucket="b",
        output_prefix=f"runs/{REV_ID}/steps/4/attempts/1/",
        abort=_NoAbort(),
    )
    kf_out = execute(kf_job, adapter, ctx)
    from flowcortex_worker.provider.contracts.provider_keyframe_result import ProviderKeyframeData
    from flowcortex_worker.provider.contracts.provider_video_result import ProviderVideoData

    validated_kf = ProviderKeyframeData.model_validate(kf_out.data)
    assert validated_kf.format == "jpg"
    assert validated_kf.provider == "fixture"
    assert kf_out.files[0].name == "keyframe.jpg"
    assert kf_out.files[0].body[:3] == b"\xff\xd8\xff"

    ls_job = ProviderJob.model_validate(
        {
            "schemaVersion": "1",
            "jobId": str(REV_ID),
            "runId": str(REV_ID),
            "correlationId": str(REV_ID),
            "stepOrder": 6,
            "capability": "lipsync_video",
            "params": {
                "profile": "vertical_ad_lipsync_v1",
                "payload": {
                    "sourceRevisionId": str(REV_ID),
                    "durationSeconds": 5,
                },
            },
            "inputs": [
                {"key": "video", "bucket": "b", "objectKey": "k/v.mp4"},
                {"key": "audio", "bucket": "b", "objectKey": "k/a.wav"},
            ],
            "outputPrefix": f"runs/{REV_ID}/steps/6/attempts/1/",
            "attempt": 1,
            "maxRuntimeSeconds": 480,
            "issuedAt": "2026-11-23T00:00:00Z",
        }
    )
    ls_ctx = ExecutionContext(
        storage=_FakeStorage({"k/v.mp4": TINY_MP4, "k/a.wav": _make_wav_bytes(4000)}),
        bucket="b",
        output_prefix=f"runs/{REV_ID}/steps/6/attempts/1/",
        abort=_NoAbort(),
    )
    monkeypatch.setattr(adapter, "await_lipsync", lambda tid, ab: adapter.await_video(tid, ab))
    monkeypatch.setattr(
        adapter,
        "await_video",
        lambda tid, ab: __import__(
            "flowcortex_worker.provider.adapter", fromlist=["VideoArtifact"]
        ).VideoArtifact(TINY_MP4, tid),
    )
    ls_out = execute(ls_job, adapter, ls_ctx)
    validated_ls = ProviderVideoData.model_validate(ls_out.data)
    assert validated_ls.profile == "vertical_ad_lipsync_v1"
    assert validated_ls.provider == "fixture"
    assert ls_out.files[0].name == "clip.mp4"


def test_veo_api_key_never_leaks_to_replicate_adapters(monkeypatch: pytest.MonkeyPatch) -> None:
    from flowcortex_worker.errors import PermanentError
    from flowcortex_worker.provider.__main__ import _build_adapter

    monkeypatch.delenv("REPLICATE_API_TOKEN", raising=False)
    monkeypatch.setenv("VIDEO_PROVIDER", "veo")
    monkeypatch.setenv("VEO_API_KEY", "secret-veo-key")
    monkeypatch.setenv("SCRIPT_PROVIDER", "fixture")
    monkeypatch.setenv("SPEECH_PROVIDER", "fixture")
    built = _build_adapter(video_enabled=True)
    assert built.keyframe_adapter is None
    assert built.lipsync_adapter is None
    with pytest.raises(PermanentError, match="Keyframe adapter is not configured"):
        built.submit_keyframe(KeyframeRequest("prompt", REV_ID))


def test_provider_jobs_pool_uses_real_replicate_adapters_for_wan(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from flowcortex_worker.provider.__main__ import _build_adapter

    monkeypatch.delenv("REPLICATE_API_TOKEN", raising=False)
    monkeypatch.setenv("VIDEO_PROVIDER", "wan")
    monkeypatch.setenv("WAN_API_KEY", "wan-replicate-key")
    monkeypatch.setenv("SCRIPT_PROVIDER", "fixture")
    monkeypatch.setenv("SPEECH_PROVIDER", "fixture")
    built = _build_adapter(video_enabled=False)
    assert isinstance(built.keyframe_adapter, SeedreamKeyframeAdapter)
    assert isinstance(built.lipsync_adapter, KlingLipsyncAdapter)


def test_seedance_rejects_unsupported_clip_v2_overrides() -> None:
    from flowcortex_worker.errors import PermanentError
    from flowcortex_worker.provider.capabilities import ExecutionContext, execute
    from flowcortex_worker.provider.contracts.provider_job import ProviderJob
    from flowcortex_worker.provider.seedance_adapter import SeedanceAdapter, SeedanceConfig

    class _EmptyStorage:
        def head_object(self, bucket: str, key: str) -> None:
            return None

        def fetch_bytes(self, bucket: str, key: str, abort: object) -> bytes:
            return TINY_JPEG

        def put(self, key: str, body: bytes, mime: str, abort: object) -> int:
            return len(body)

        def presigned_get_url(self, bucket: str, key: str, *, expires_seconds: int = 300) -> str:
            return f"https://example.test/{key}"

    adapter = SeedanceAdapter(SeedanceConfig(api_key="tok", base_url="https://seedance.test"))
    job = ProviderJob.model_validate(
        {
            "schemaVersion": "1",
            "jobId": str(REV_ID),
            "runId": str(REV_ID),
            "correlationId": str(REV_ID),
            "stepOrder": 5,
            "capability": "generate_video",
            "params": {
                "profile": "vertical_ad_clip_v2",
                "payload": {
                    "prompt": "Watch shot",
                    "sourceRevisionId": str(REV_ID),
                    "durationSeconds": 8,
                    "resolution": "720p",
                    "firstFrameKey": "keyframe",
                },
            },
            "inputs": [{"key": "keyframe", "bucket": "b", "objectKey": "k/kf.jpg"}],
            "outputPrefix": f"runs/{REV_ID}/steps/5/attempts/1/",
            "attempt": 1,
            "maxRuntimeSeconds": 480,
            "issuedAt": "2026-11-23T00:00:00Z",
        }
    )
    ctx = ExecutionContext(
        storage=_EmptyStorage(),
        bucket="b",
        output_prefix=f"runs/{REV_ID}/steps/5/attempts/1/",
        abort=_NoAbort(),
    )
    with pytest.raises(PermanentError, match="does not support vertical_ad_clip_v2 overrides"):
        execute(job, adapter, ctx)


def test_wan_adapter_honors_v2_resolution_first_frame_and_rejects_bad_duration(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from flowcortex_worker.errors import PermanentError
    from flowcortex_worker.provider.adapter import VideoRequest
    from flowcortex_worker.provider.wan_adapter import WanAdapter, WanConfig

    captured: dict[str, object] = {}

    def fake_urlopen(req: urllib.request.Request, timeout: float = 0) -> _FakeResp:
        captured["body"] = json.loads(req.data.decode())  # type: ignore[union-attr]
        return _FakeResp(json.dumps({"id": "pred-wan-1", "status": "starting"}).encode())

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)
    adapter = WanAdapter(WanConfig(api_key="tok"))
    kf_bytes = TINY_JPEG + b"KEYFRAME"
    adapter.submit_video(
        VideoRequest(
            "Product shot",
            REV_ID,
            profile="vertical_ad_clip_v2",
            duration_seconds=4,
            resolution="720p",
            reference_image_bytes=TINY_JPEG,
            first_frame_image_bytes=kf_bytes,
        )
    )
    sent_input = captured["body"]["input"]  # type: ignore[index]
    assert sent_input["resolution"] == "720p"
    assert sent_input["num_frames"] == 97
    assert "S0VZRlJBTUU=" in sent_input["image"]

    with pytest.raises(PermanentError, match="does not support durationSeconds=10"):
        adapter.submit_video(
            VideoRequest("Product shot", REV_ID, profile="vertical_ad_clip_v2", duration_seconds=10)
        )

    # v1 clip with 30fps / 121 frames must keep config num_frames when duration_seconds is None
    wan_30fps = WanAdapter(WanConfig(api_key="tok", frames_per_second=30, num_frames=121))
    wan_30fps.submit_video(
        VideoRequest("Product shot", REV_ID, profile="vertical_ad_clip_v1", duration_seconds=None)
    )
    assert captured["body"]["input"]["num_frames"] == 121  # type: ignore[index]


def test_openai_storyboard_includes_scene_preset_and_note(monkeypatch: pytest.MonkeyPatch) -> None:
    from flowcortex_worker.provider.adapter import StoryboardRequest
    from flowcortex_worker.provider.openai_adapter import OpenAIConfig, OpenAIProviderAdapter

    captured: dict[str, object] = {}

    def fake_request(
        self: OpenAIProviderAdapter,
        *,
        model: str,
        system_prompt: str,
        user_message: object,
        response_format: dict[str, object],
        temperature: float,
        timeout: float,
    ) -> dict[str, str]:
        captured["system_prompt"] = system_prompt
        captured["user_message"] = user_message
        return {"scene1Prompt": "Wide shot of watch.", "scene2Prompt": "Close-up of strap."}

    monkeypatch.setattr(OpenAIProviderAdapter, "_request_json", fake_request)
    adapter = OpenAIProviderAdapter(OpenAIConfig(api_key="tok"))
    adapter.generate_storyboard(
        StoryboardRequest(
            script="Đồng hồ đẹp.",
            source_revision_id=REV_ID,
            template_id="mc-v1",
            scene_preset="bg-luxury-dark",
            scene_note="Ánh đèn vàng ấm hơn",
        )
    )
    assert "CRITICAL LIP-SYNC SAFETY" in str(captured["system_prompt"])
    assert "minimalist luxury gallery" in str(captured["user_message"])
    assert "Ánh đèn vàng ấm hơn" in str(captured["user_message"])


def test_keyframe_retry_reuses_checkpoint_without_resubmitting() -> None:
    from flowcortex_worker.provider.adapter import FixtureProviderAdapter
    from flowcortex_worker.provider.capabilities import ExecutionContext, execute
    from flowcortex_worker.provider.contracts.provider_job import ProviderJob

    class _StatefulStorage:
        def __init__(self, blobs: dict[str, bytes]) -> None:
            self._blobs = dict(blobs)

        def head_object(self, bucket: str, key: str) -> dict[str, str] | None:
            return {"ok": "1"} if key in self._blobs else None

        def fetch_bytes(self, bucket: str, key: str, abort: object) -> bytes:
            return self._blobs[key]

        def put(self, key: str, body: bytes, mime: str, abort: object) -> int:
            self._blobs[key] = body
            return len(body)

        def presigned_get_url(self, bucket: str, key: str, *, expires_seconds: int = 300) -> str:
            return f"https://example.test/{key}"

    submit_calls = 0

    class _CountingAdapter(FixtureProviderAdapter):
        def submit_keyframe(self, request: KeyframeRequest) -> str:
            nonlocal submit_calls
            submit_calls += 1
            return "pred-kf-once"

    kf_job = ProviderJob.model_validate(
        {
            "schemaVersion": "1",
            "jobId": str(REV_ID),
            "runId": str(REV_ID),
            "correlationId": str(REV_ID),
            "stepOrder": 4,
            "capability": "generate_keyframe",
            "params": {
                "profile": "vertical_ad_keyframe_v1",
                "payload": {
                    "prompt": "MC holding watch in studio",
                    "sourceRevisionId": str(REV_ID),
                },
            },
            "inputs": [{"key": "product_1", "bucket": "b", "objectKey": "k/p1.jpg"}],
            "outputPrefix": f"runs/{REV_ID}/steps/4/attempts/1/",
            "attempt": 1,
            "maxRuntimeSeconds": 180,
            "issuedAt": "2026-11-23T00:00:00Z",
        }
    )
    storage = _StatefulStorage({"k/p1.jpg": TINY_JPEG})
    ctx = ExecutionContext(
        storage=storage,
        bucket="b",
        output_prefix=f"runs/{REV_ID}/steps/4/attempts/1/",
        abort=_NoAbort(),
    )
    adapter = _CountingAdapter()
    execute(kf_job, adapter, ctx)
    execute(kf_job, adapter, ctx)
    assert submit_calls == 1


def test_edge_adapter_rejects_oversized_scene2_instead_of_truncating(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from flowcortex_worker.errors import PermanentError
    from flowcortex_worker.provider.adapter import SpeechRequest
    from flowcortex_worker.provider.edge_adapter import EdgeConfig, EdgeSpeechAdapter

    adapter = EdgeSpeechAdapter(EdgeConfig())
    monkeypatch.setattr(
        EdgeSpeechAdapter, "_download_segment", lambda self, script, cfg, ab: b"mp3"
    )
    monkeypatch.setattr(
        "flowcortex_worker.provider.edge_adapter._to_wav", lambda mp3, ab, atempo=None: b"wav"
    )
    durations = iter([4_200, 6_000])
    monkeypatch.setattr(
        "flowcortex_worker.provider.edge_adapter._validate_wav", lambda wav: next(durations)
    )

    with pytest.raises(PermanentError, match="Product close speech is 6000 ms"):
        adapter.generate_speech(
            SpeechRequest(
                script="Hook. Close.",
                source_revision_id=REV_ID,
                mc_hook_script="Hook.",
                product_close_script="Close that is six seconds long.",
            ),
            _NoAbort(),  # type: ignore[arg-type]
        )


def test_read_bounded_response_handles_ssl_socket_success_timeout_and_abort() -> None:
    import socket
    import threading
    import time
    from types import SimpleNamespace

    from flowcortex_worker.provider.vendor_jobs import VendorJobTimeout, read_bounded_response

    class _FakeSSLSocket(socket.socket):
        def __init__(self) -> None:
            self.shutdown_calls = 0

        def dup(self) -> socket.socket:
            raise NotImplementedError("Cannot dup() SSLSocket")

        def shutdown(self, _how: int) -> None:
            self.shutdown_calls += 1

        def close(self) -> None:
            return None

    class _StreamingResp:
        def __init__(
            self,
            sock: socket.socket,
            chunks: list[bytes],
            *,
            block_event: threading.Event | None = None,
        ) -> None:
            self._chunks = iter(chunks)
            self._block = block_event
            self.fp = SimpleNamespace(raw=SimpleNamespace(_sock=sock))

        def read1(self, _n: int = -1) -> bytes:
            if self._block is not None:
                self._block.wait(timeout=1.0)
                return b""
            return next(self._chunks, b"")

    # 1. Success path with SSLSocket (dup raises NotImplementedError)
    sock_ok = _FakeSSLSocket()
    resp_ok = _StreamingResp(sock_ok, [b"hello ", b"world"])
    body = read_bounded_response(
        resp_ok,
        max_bytes=1024,
        provider="seedream",
        task_id="t-ok",
        abort=_NoAbort(),
        deadline=time.monotonic() + 5.0,
    )
    assert body == b"hello world"
    assert sock_ok.shutdown_calls == 0

    # 2. Deadline path shuts down SSLSocket and raises VendorJobTimeout
    unblock = threading.Event()
    sock_to = _FakeSSLSocket()
    orig_shutdown = sock_to.shutdown

    def _shutdown_and_unblock(how: int) -> None:
        orig_shutdown(how)
        unblock.set()

    sock_to.shutdown = _shutdown_and_unblock  # type: ignore[method-assign]
    resp_to = _StreamingResp(sock_to, [], block_event=unblock)
    with pytest.raises(VendorJobTimeout):
        read_bounded_response(
            resp_to,
            max_bytes=1024,
            provider="kling_lipsync",
            task_id="t-to",
            abort=_NoAbort(),
            deadline=time.monotonic() + 0.05,
        )
    assert sock_to.shutdown_calls >= 1

    # 3. Abort/kill path triggers on_kill shutdown and raises abort error
    class _KillError(RuntimeError):
        pass

    class _AbortWithKill:
        def __init__(self) -> None:
            self.killed = False
            self._cb: object = None

        def on_kill(self, cb: object) -> None:
            self._cb = cb

        def trigger(self) -> None:
            self.killed = True
            if callable(self._cb):
                self._cb()

        def check(self) -> None:
            if self.killed:
                raise _KillError("killed")

    unblock_kill = threading.Event()
    sock_kill = _FakeSSLSocket()
    orig_kill_shutdown = sock_kill.shutdown

    def _kill_shutdown(how: int) -> None:
        orig_kill_shutdown(how)
        unblock_kill.set()

    sock_kill.shutdown = _kill_shutdown  # type: ignore[method-assign]
    abort = _AbortWithKill()
    resp_kill = _StreamingResp(sock_kill, [], block_event=unblock_kill)
    killer = threading.Timer(0.05, abort.trigger)
    killer.start()
    try:
        with pytest.raises(_KillError):
            read_bounded_response(
                resp_kill,
                max_bytes=1024,
                provider="kling_lipsync",
                task_id="t-kill",
                abort=abort,
                deadline=time.monotonic() + 5.0,
            )
    finally:
        killer.join()
    assert sock_kill.shutdown_calls >= 1


def test_mc_script_split_wav_marker_and_lipsync_trim(monkeypatch: pytest.MonkeyPatch) -> None:
    import wave

    from flowcortex_worker.provider.adapter import FixtureProviderAdapter, VideoArtifact
    from flowcortex_worker.provider.capabilities import ExecutionContext, execute
    from flowcortex_worker.provider.contracts.provider_job import ProviderJob
    from flowcortex_worker.provider.edge_adapter import (
        extract_mc_end_ms,
        split_mc_script_segments,
        trim_wav,
    )

    assert split_mc_script_segments("Câu một MC.\n\nCâu hai sản phẩm.") == (
        "Câu một MC.",
        "Câu hai sản phẩm.",
    )
    assert split_mc_script_segments("Một đoạn duy nhất.") is None
    assert "NO speaking" in MC_STORYBOARD_SYSTEM_PROMPT
    assert "never floating" in MC_STORYBOARD_SYSTEM_PROMPT

    wav_7s = _make_wav_bytes(7000, comment="mc_end_ms=3480")
    assert extract_mc_end_ms(wav_7s) == 3480
    trimmed = trim_wav(wav_7s, 3480)
    with wave.open(io.BytesIO(trimmed), "rb") as w:
        assert round(w.getnframes() * 1000 / w.getframerate()) == 3480

    captured: dict[str, object] = {}

    class _RecordingLipsync(FixtureProviderAdapter):
        def submit_lipsync(self, request: LipsyncRequest) -> str:
            captured["duration_seconds"] = request.duration_seconds
            captured["audio_bytes"] = request.audio_bytes
            return "ls-trim-1"

        def await_lipsync(self, task_id: str, abort: object) -> VideoArtifact:
            return VideoArtifact(TINY_MP4, task_id)

    def _fake_norm(body: bytes, _ctx: object, **kw: object) -> bytes:
        captured["norm_dur"] = kw.get("duration_seconds")
        return body

    monkeypatch.setattr("flowcortex_worker.provider.capabilities._normalize_video", _fake_norm)
    ls_job = ProviderJob.model_validate(
        {
            "schemaVersion": "1",
            "jobId": str(REV_ID),
            "runId": str(REV_ID),
            "correlationId": str(REV_ID),
            "stepOrder": 9,
            "capability": "lipsync_video",
            "params": {
                "profile": "vertical_ad_lipsync_v1",
                "payload": {"sourceRevisionId": str(REV_ID), "durationSeconds": 5},
            },
            "inputs": [
                {"key": "video", "bucket": "b", "objectKey": "k/v.mp4"},
                {"key": "audio", "bucket": "b", "objectKey": "k/speech.wav"},
            ],
            "outputPrefix": f"runs/{REV_ID}/steps/9/attempts/1/",
            "attempt": 1,
            "maxRuntimeSeconds": 480,
            "issuedAt": "2026-11-24T00:00:00Z",
        }
    )

    class _MemStorage:
        def __init__(self, objs: dict[str, bytes]) -> None:
            self._objs = objs

        def head_object(self, _b: str, key: str) -> int | None:
            return len(self._objs[key]) if key in self._objs else None

        def fetch_bytes(self, _b: str, key: str, _a: object) -> bytes:
            return self._objs[key]

        def put(self, key: str, body: bytes, _m: str, _a: object) -> None:
            self._objs[key] = body

    ctx = ExecutionContext(
        storage=_MemStorage({"k/v.mp4": TINY_MP4, "k/speech.wav": wav_7s}),
        bucket="b",
        output_prefix=f"runs/{REV_ID}/steps/9/attempts/1/",
        abort=_NoAbort(),
    )
    execute(ls_job, _RecordingLipsync(), ctx)
    assert captured["duration_seconds"] == 3.48
    assert captured["norm_dur"] == 3.48
    with wave.open(io.BytesIO(captured["audio_bytes"]), "rb") as w:  # type: ignore[arg-type]
        assert round(w.getnframes() * 1000 / w.getframerate()) == 3480
