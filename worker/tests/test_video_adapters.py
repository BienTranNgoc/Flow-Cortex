"""Unit tests for the Seedance and Veo video adapters, and the generate_video capability.

The HTTP layer is hand-rolled with ``urllib.request``; these tests mock it so the adapters
are exercised through their real call site without a network round trip. FFmpeg
post-processing is skipped when ``ffmpeg`` is not on PATH.
"""

from __future__ import annotations

import dataclasses
import importlib
import io
import json
import shutil
import subprocess
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, cast
from unittest.mock import MagicMock
from uuid import UUID

import jsonschema
import pytest

from flowcortex_worker.errors import PermanentError, TransientError
from flowcortex_worker.provider import seedance_adapter, veo_adapter
from flowcortex_worker.provider.adapter import (
    VideoAbort,
    VideoArtifact,
    VideoIdentity,
    VideoRequest,
)
from flowcortex_worker.provider.capabilities import ExecutionContext, execute
from flowcortex_worker.provider.contracts.provider_job import ProviderJob
from flowcortex_worker.provider.contracts.provider_video_result import ProviderVideoData

REVISION_ID = UUID("a71ea3ba-d72a-4c2c-985c-3a7fd98853c4")
RUN_ID = "0b7e1d2c-3a4b-4c5d-8e6f-7a8b9c0d1e2f"
JOB_ID = "5f0c7c1e-8f7e-4a53-9a38-2f6d6f3f9c11"
BUCKET = "flowcortex-media"

needs_ffmpeg = pytest.mark.skipif(
    shutil.which("ffmpeg") is None,
    reason="ffmpeg is installed in the worker image",
)


def _http_response(body: dict[str, Any] | bytes) -> MagicMock:
    response = MagicMock()
    payload = body if isinstance(body, bytes) else json.dumps(body).encode("utf-8")
    response.read = MagicMock(return_value=payload)
    response.__enter__ = MagicMock(return_value=response)
    response.__exit__ = MagicMock(return_value=False)
    return response


def _capture_urlopen(monkeypatch: pytest.MonkeyPatch) -> dict[str, Any]:
    captured: dict[str, Any] = {"calls": [], "responses": []}

    def fake_urlopen(request: Any, timeout: float = 0) -> MagicMock:
        captured["calls"].append(
            {
                "url": request.full_url,
                "method": request.method,
                "headers": dict(request.headers),
                "body": json.loads(request.data.decode("utf-8")) if request.data else None,
                "timeout": timeout,
            }
        )
        if not captured["responses"]:
            raise AssertionError("no canned response left")
        body = captured["responses"].pop(0)
        return _http_response(body)

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)
    return captured


def _valid_mp4(size: int = 4096) -> bytes:
    # `ftyp` box starts at byte offset 4 — the adapter only checks body[4:8] == b"ftyp".
    header = b"\0\0\0\x20ftypisom" + b"\0" * 16
    return header + b"\0" * max(0, size - len(header))


def _decodable_mp4() -> bytes:
    completed = subprocess.run(
        [
            "ffmpeg",
            "-nostdin",
            "-hide_banner",
            "-loglevel",
            "error",
            "-f",
            "lavfi",
            "-i",
            "color=c=black:s=180x320:d=1",
            "-an",
            "-c:v",
            "libx264",
            "-pix_fmt",
            "yuv420p",
            "-movflags",
            "frag_keyframe+empty_moov",
            "-f",
            "mp4",
            "pipe:1",
        ],
        check=True,
        capture_output=True,
    )
    return completed.stdout


class _Abort:
    def check(self) -> None:
        return

    def run(self, argv: list[str]) -> subprocess.CompletedProcess[bytes]:
        return subprocess.run(argv, check=False, capture_output=True)


def _fast_config(config: Any, **overrides: Any) -> Any:
    return dataclasses.replace(config, poll_interval_seconds=0.0, **overrides)


# ---------------------------------------------------------------------------
# Seedance adapter
# ---------------------------------------------------------------------------


def _seedance() -> seedance_adapter.SeedanceAdapter:
    return seedance_adapter.SeedanceAdapter(
        seedance_adapter.SeedanceConfig(
            api_key="sk-seedance-test",
            base_url="https://dashscope.aliyuncs.com/api/v1",
        )
    )


def test_seedance_submit_sends_required_parameters(monkeypatch: pytest.MonkeyPatch) -> None:
    captured = _capture_urlopen(monkeypatch)
    captured["responses"].append({"output": {"task_id": "seedance-task-1"}})

    task_id = _seedance().submit_video(VideoRequest("Hook. Lợi ích. CTA.", REVISION_ID))

    assert task_id == "seedance-task-1"
    call = captured["calls"][0]
    assert call["method"] == "POST"
    assert call["url"].endswith("/api/v1/services/aigc/video-generation/video-synthesis")
    assert call["headers"]["Authorization"] == "Bearer sk-seedance-test"
    assert call["headers"]["X-dashscope-async"] == "enable"
    body = call["body"]
    assert body["model"] == seedance_adapter.SeedanceConfig.model
    params = body["parameters"]
    assert params["duration"] == 5
    assert params["resolution"] == "480p"
    assert params["ratio"] == "9:16"
    assert params["generate_audio"] is False


def test_seedance_poll_continues_while_pending(monkeypatch: pytest.MonkeyPatch) -> None:
    adapter = seedance_adapter.SeedanceAdapter(
        _fast_config(_seedance().config, poll_timeout_seconds=5.0)
    )
    captured = _capture_urlopen(monkeypatch)
    captured["responses"] = [
        {"output": {"task_status": "PENDING"}},
        {"output": {"task_status": "RUNNING"}},
        {
            "output": {
                "task_status": "SUCCEEDED",
                "video_url": "https://cdn.aliyuncs.com/seedance/clip.mp4",
            }
        },
    ]
    monkeypatch.setattr(
        seedance_adapter.SeedanceAdapter,
        "_download_video",
        lambda self, url: _valid_mp4(2048),
    )

    artifact = adapter.await_video("seedance-task-1", _Abort())
    assert artifact.provider_task_id == "seedance-task-1"
    assert artifact.body[4:8] == b"ftyp"


def test_seedance_terminal_status_is_permanent(monkeypatch: pytest.MonkeyPatch) -> None:
    adapter = seedance_adapter.SeedanceAdapter(_fast_config(_seedance().config))
    captured = _capture_urlopen(monkeypatch)
    captured["responses"].append(
        {"output": {"task_status": "FAILED", "code": "content_audit"}}
    )

    with pytest.raises(PermanentError, match="task ended with status FAILED"):
        adapter.await_video("seedance-task-2", _Abort())


@pytest.mark.parametrize("status", [408, 429, 500, 503])
def test_seedance_retryable_http_error_is_transient(
    monkeypatch: pytest.MonkeyPatch, status: int
) -> None:
    def fake_urlopen(request: Any, timeout: float = 0) -> MagicMock:
        raise urllib.error.HTTPError(
            request.full_url,
            status,
            "upstream",
            cast(Any, {}),
            io.BytesIO(b'{"code":"busy"}'),
        )

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)
    with pytest.raises(TransientError):
        _seedance().submit_video(VideoRequest("prompt", REVISION_ID))


def test_seedance_non_retryable_http_error_is_permanent(monkeypatch: pytest.MonkeyPatch) -> None:
    def fake_urlopen(request: Any, timeout: float = 0) -> MagicMock:
        raise urllib.error.HTTPError(
            request.full_url,
            403,
            "rejected",
            cast(Any, {}),
            io.BytesIO(b'{"code":"forbidden"}'),
        )

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)
    with pytest.raises(PermanentError) as raised:
        _seedance().submit_video(VideoRequest("prompt", REVISION_ID))
    assert raised.value.code == "provider_request_failed"


def test_seedance_rejects_video_url_outside_aliyun(monkeypatch: pytest.MonkeyPatch) -> None:
    adapter = seedance_adapter.SeedanceAdapter(_fast_config(_seedance().config))
    captured = _capture_urlopen(monkeypatch)
    captured["responses"].append(
        {
            "output": {
                "task_status": "SUCCEEDED",
                "video_url": "https://example.com/clip.mp4",
            }
        }
    )

    with pytest.raises(PermanentError, match="outside Alibaba Cloud"):
        adapter.await_video("seedance-task-3", _Abort())


def test_seedance_rejects_non_mp4_bytes(monkeypatch: pytest.MonkeyPatch) -> None:
    """The MP4 magic check runs inside `_download_video` itself; the test exercises it
    by mocking `urllib.request.urlopen` to return a body whose ftyp box is missing."""
    adapter = seedance_adapter.SeedanceAdapter(_fast_config(_seedance().config))
    response = MagicMock()
    response.read = MagicMock(return_value=b"x" * 12 + b"not-an-mp4")
    response.__enter__ = MagicMock(return_value=response)
    response.__exit__ = MagicMock(return_value=False)
    monkeypatch.setattr(urllib.request, "urlopen", MagicMock(return_value=response))

    with pytest.raises(PermanentError, match="not an MP4 file"):
        adapter._download_video("https://cdn.aliyuncs.com/seedance/clip.mp4")


def test_seedance_timeout_is_transient(monkeypatch: pytest.MonkeyPatch) -> None:
    adapter = seedance_adapter.SeedanceAdapter(
        _fast_config(_seedance().config, poll_timeout_seconds=0.0)
    )
    captured = _capture_urlopen(monkeypatch)
    captured["responses"].append({"output": {"task_status": "RUNNING"}})

    with pytest.raises(TransientError, match="timed out"):
        adapter.await_video("seedance-task-5", _Abort())


# ---------------------------------------------------------------------------
# Veo adapter
# ---------------------------------------------------------------------------


def _veo() -> veo_adapter.VeoAdapter:
    return veo_adapter.VeoAdapter(veo_adapter.VeoConfig(api_key="veo-test-key"))


def test_veo_submit_sends_six_seconds_then_normalises(monkeypatch: pytest.MonkeyPatch) -> None:
    captured = _capture_urlopen(monkeypatch)
    captured["responses"].append({"name": "operations/veo-1"})

    operation = _veo().submit_video(VideoRequest("Hook. Lợi ích. CTA.", REVISION_ID))

    assert operation == "operations/veo-1"
    call = captured["calls"][0]
    assert call["method"] == "POST"
    assert "/models/veo-3.1-fast-generate-preview:predictLongRunning" in call["url"]
    assert call["headers"]["X-goog-api-key"] == "veo-test-key"
    params = call["body"]["parameters"]
    assert params["aspectRatio"] == "9:16"
    assert params["durationSeconds"] == "6"
    assert params["resolution"] == "720p"


def test_veo_poll_completes_when_done(monkeypatch: pytest.MonkeyPatch) -> None:
    adapter = veo_adapter.VeoAdapter(
        _fast_config(_veo().config, poll_timeout_seconds=5.0)
    )
    captured = _capture_urlopen(monkeypatch)
    captured["responses"] = [
        {"done": False},
        {
            "done": True,
            "response": {
                "generateVideoResponse": {
                    "generatedSamples": [
                        {"video": {"uri": "https://storage.googleapis.com/veo/clip.mp4"}}
                    ]
                }
            },
        },
    ]
    monkeypatch.setattr(
        veo_adapter.VeoAdapter,
        "_download_video",
        lambda self, url: _valid_mp4(4096),
    )

    artifact = adapter.await_video("operations/veo-1", _Abort())
    assert artifact.provider_task_id == "operations/veo-1"


def test_veo_rejects_invalid_operation_name(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(urllib.request, "urlopen", MagicMock())
    with pytest.raises(PermanentError, match="operation name is invalid"):
        _veo().await_video("not-an-operation", _Abort())


def test_veo_operation_error_is_permanent(monkeypatch: pytest.MonkeyPatch) -> None:
    adapter = veo_adapter.VeoAdapter(_fast_config(_veo().config))
    captured = _capture_urlopen(monkeypatch)
    captured["responses"].append({"done": True, "error": {"code": "policy_violation"}})

    with pytest.raises(PermanentError, match="completed with an error"):
        adapter.await_video("operations/veo-2", _Abort())


@pytest.mark.parametrize("status", [408, 429, 500, 503])
def test_veo_retryable_http_error_is_transient(
    monkeypatch: pytest.MonkeyPatch, status: int
) -> None:
    def fake_urlopen(request: Any, timeout: float = 0) -> MagicMock:
        raise urllib.error.HTTPError(
            request.full_url,
            status,
            "upstream",
            cast(Any, {}),
            io.BytesIO(b""),
        )

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)
    with pytest.raises(TransientError):
        _veo().submit_video(VideoRequest("prompt", REVISION_ID))


def test_veo_rejects_url_outside_googleapis(monkeypatch: pytest.MonkeyPatch) -> None:
    adapter = veo_adapter.VeoAdapter(_fast_config(_veo().config))
    captured = _capture_urlopen(monkeypatch)
    captured["responses"].append(
        {
            "done": True,
            "response": {
                "generateVideoResponse": {
                    "generatedSamples": [{"video": {"uri": "https://example.com/clip.mp4"}}]
                }
            },
        }
    )

    with pytest.raises(PermanentError, match="outside Google APIs"):
        adapter.await_video("operations/veo-3", _Abort())


# ---------------------------------------------------------------------------
# generate_video capability (executor level)
# ---------------------------------------------------------------------------


def _video_job() -> ProviderJob:
    return ProviderJob.model_validate(_video_job_dict())


def _video_job_dict() -> dict[str, Any]:
    return {
        "schemaVersion": "1",
        "jobId": JOB_ID,
        "runId": RUN_ID,
        "correlationId": RUN_ID,
        "stepOrder": 4,
        "capability": "generate_video",
        "params": {
            "profile": "vertical_ad_clip_v1",
            "payload": {
                "prompt": (
                    "A premium brown baseball cap rotating slowly "
                    "on a clean studio pedestal"
                ),
                "sourceRevisionId": str(REVISION_ID),
            },
        },
        "outputPrefix": f"runs/{RUN_ID}/steps/4/attempts/1/",
        "attempt": 1,
        "maxRuntimeSeconds": 600,
        "issuedAt": "2026-10-15T00:00:00Z",
    }


def _empty_context() -> ExecutionContext:
    storage = MagicMock()
    storage.head_object.return_value = None
    storage.fetch_bytes.return_value = b""
    return ExecutionContext(
        storage=storage,
        bucket=BUCKET,
        output_prefix=f"runs/{RUN_ID}/steps/4/attempts/1/",
        abort=MagicMock(),
    )


@needs_ffmpeg
def test_video_capability_normalises_clip_to_five_seconds() -> None:
    """End-to-end: submit a clip and normalize it to the configured vertical output."""

    class _Adapter:
        def video_identity(self) -> VideoIdentity:
            return VideoIdentity(
                "seedance", "ByteDance-Seedance-1.5-pro", 5, "9:16", "480p", False
            )

        def submit_video(self, request: VideoRequest) -> str:
            return "seedance-task-1"

        def await_video(self, task_id: str, abort: VideoAbort) -> VideoArtifact:
            return VideoArtifact(_decodable_mp4(), task_id)

    context = dataclasses.replace(_empty_context(), abort=_Abort())
    outcome = execute(_video_job(), _Adapter(), context)  # type: ignore[arg-type]
    data = ProviderVideoData.model_validate(outcome.data)
    assert data.provider == "seedance"
    assert data.durationSeconds == 5
    assert data.aspectRatio == "9:16"
    assert data.resolution == "480p"
    assert data.providerTaskId == "seedance-task-1"
    clip, manifest = outcome.files
    assert clip.name == "clip.mp4"
    assert manifest.name == "manifest.json"
    assert json.loads(manifest.body) == outcome.data


def test_video_capability_uses_existing_checkpoint_without_resubmit(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """If the checkpoint is present, the adapter must NOT be asked to submit a new task."""
    from flowcortex_worker.provider import capabilities as capabilities_module

    monkeypatch.setattr(
        capabilities_module, "_normalize_video", _raise_no_ffmpeg, raising=True
    )

    storage = MagicMock()
    storage.head_object.return_value = {}
    storage.fetch_bytes.return_value = json.dumps(
        {
            "idempotencyKey": "a" * 64,
            "model": "ByteDance-Seedance-1.5-pro",
            "provider": "seedance",
            "providerTaskId": "seedance-task-existing",
        }
    ).encode()

    submitted: list[VideoRequest] = []

    class _Adapter:
        def video_identity(self) -> VideoIdentity:
            return VideoIdentity(
                "seedance", "ByteDance-Seedance-1.5-pro", 5, "9:16", "720p", False
            )

        def submit_video(self, request: VideoRequest) -> str:
            submitted.append(request)
            return "seedance-task-NEW"

        def await_video(self, task_id: str, abort: VideoAbort) -> VideoArtifact:
            return VideoArtifact(_valid_mp4(1024), task_id)

    context = ExecutionContext(
        storage=storage,
        bucket=BUCKET,
        output_prefix=f"runs/{RUN_ID}/steps/4/attempts/1/",
        abort=MagicMock(),
    )
    with pytest.raises(PermanentError):
        execute(_video_job(), _Adapter(), context)  # type: ignore[arg-type]
    assert submitted == []  # checkpoint was honoured; no new task was created.


def _raise_no_ffmpeg(body: bytes, context: ExecutionContext) -> bytes:
    raise PermanentError("provider_response_invalid", "ffmpeg not available in this test")


def test_video_capability_rejects_unsupported_profile() -> None:
    """The provider-job schema only allows `vertical_ad_clip_v1` — any other profile
    is rejected at the contract boundary before reaching the executor."""
    schema = json.loads(
        (Path(__file__).resolve().parents[2] / "contracts" / "provider-job.schema.json").read_text()
    )
    bad = _video_job_dict()
    bad["params"]["profile"] = "square_clip_v1"
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.Draft202012Validator(schema).validate(bad)


def test_video_capability_idempotency_key_changes_with_provider_model() -> None:
    """Two video adapters with different provider or model must yield different keys."""
    from flowcortex_worker.provider.capabilities import _video_idempotency_key

    request = VideoRequest("prompt", REVISION_ID)
    identity_a = VideoIdentity("seedance", "model-A", 5, "9:16", "720p", False)
    identity_b = VideoIdentity("veo", "model-A", 5, "9:16", "720p", False)
    identity_c = VideoIdentity("seedance", "model-B", 5, "9:16", "720p", False)
    keys = {
        _video_idempotency_key(request, identity_a),
        _video_idempotency_key(request, identity_b),
        _video_idempotency_key(request, identity_c),
    }
    assert len(keys) == 3


# ---------------------------------------------------------------------------
# Adapter wiring (VIDEO_PROVIDER env)
# ---------------------------------------------------------------------------


def _clear_env(monkeypatch: pytest.MonkeyPatch) -> None:
    for name in (
        "VIDEO_PROVIDER",
        "VIDEO_PROVIDER_API_KEY",
        "VIDEO_PROVIDER_BASE_URL",
        "VIDEO_PROVIDER_MODEL",
        "SEEDANCE_API_KEY",
        "SEEDANCE_BASE_URL",
        "SEEDANCE_MODEL",
        "VEO_API_KEY",
        "VEO_BASE_URL",
        "VEO_MODEL",
        "PROVIDER_ADAPTER",
        "SCRIPT_PROVIDER",
        "SPEECH_PROVIDER",
        "SCRIPT_PROVIDER_API_KEY",
        "SPEECH_PROVIDER_API_KEY",
        "OPENAI_API_KEY",
        "FLOWCORTEX_PROVIDER_BASE_URL",
    ):
        monkeypatch.delenv(name, raising=False)


def test_video_provider_fixture_does_not_call_internet(monkeypatch: pytest.MonkeyPatch) -> None:
    _clear_env(monkeypatch)
    monkeypatch.setenv("VIDEO_PROVIDER", "fixture")

    from flowcortex_worker.provider import __main__ as provider_main

    importlib.reload(provider_main)
    adapter = provider_main._build_adapter()

    from flowcortex_worker.provider.adapter import FixtureProviderAdapter

    assert isinstance(adapter.video_adapter, FixtureProviderAdapter)
    with pytest.raises(PermanentError, match="not implemented"):
        adapter.video_identity()


def test_video_provider_seedance_requires_key(monkeypatch: pytest.MonkeyPatch) -> None:
    _clear_env(monkeypatch)
    monkeypatch.setenv("VIDEO_PROVIDER", "seedance")

    from flowcortex_worker.provider import __main__ as provider_main

    importlib.reload(provider_main)
    with pytest.raises(RuntimeError, match="SEEDANCE_API_KEY"):
        provider_main._build_adapter()


def test_video_provider_seedance_uses_capability_scoped_credentials(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _clear_env(monkeypatch)
    monkeypatch.setenv("VIDEO_PROVIDER", "seedance")
    monkeypatch.setenv("SEEDANCE_API_KEY", "sk-capability-key")
    monkeypatch.setenv("SEEDANCE_BASE_URL", "https://dashscope.example.com/api/v1")
    monkeypatch.setenv("SEEDANCE_MODEL", "vendor/seedance-pro")

    from flowcortex_worker.provider import __main__ as provider_main

    importlib.reload(provider_main)
    adapter = provider_main._build_adapter()

    assert isinstance(adapter.video_adapter, seedance_adapter.SeedanceAdapter)
    assert adapter.video_adapter.config.api_key == "sk-capability-key"
    assert adapter.video_adapter.config.base_url == "https://dashscope.example.com/api/v1"
    assert adapter.video_adapter.config.model == "vendor/seedance-pro"


def test_video_provider_veo_uses_default_base_url(monkeypatch: pytest.MonkeyPatch) -> None:
    _clear_env(monkeypatch)
    monkeypatch.setenv("VIDEO_PROVIDER", "veo")
    monkeypatch.setenv("VEO_API_KEY", "veo-key")

    from flowcortex_worker.provider import __main__ as provider_main

    importlib.reload(provider_main)
    adapter = provider_main._build_adapter()

    assert isinstance(adapter.video_adapter, veo_adapter.VeoAdapter)
    assert adapter.video_adapter.config.base_url == (
        "https://generativelanguage.googleapis.com/v1beta"
    )
    assert adapter.video_adapter.config.model == "veo-3.1-fast-generate-preview"


def test_video_provider_unknown_is_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    _clear_env(monkeypatch)
    monkeypatch.setenv("VIDEO_PROVIDER", "sora")

    from flowcortex_worker.provider import __main__ as provider_main

    importlib.reload(provider_main)
    with pytest.raises(RuntimeError, match="Unknown VIDEO_PROVIDER"):
        provider_main._build_adapter()
