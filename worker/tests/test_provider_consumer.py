from __future__ import annotations

import io
import json
import urllib.error
import urllib.request
from datetime import UTC, datetime
from typing import Any, cast
from unittest.mock import MagicMock

import pytest
from conftest import (
    FakeConnection,
    FakeStorage,
    assert_valid,
    captured_logs,
    published,
    run_consumer,
    settled,
)

from flowcortex_worker.contracts.media_progress import VendorTaskReport
from flowcortex_worker.errors import PermanentError, StaleDelivery, TransientError
from flowcortex_worker.provider.__main__ import (
    MAX_TRANSIENT_REDELIVERIES,
    _build_adapter,
    make_execute_job,
    provider_queue,
)
from flowcortex_worker.provider.adapter import FixtureProviderAdapter
from flowcortex_worker.provider.contracts.provider_job import ProviderJob
from flowcortex_worker.provider.openai_adapter import OpenAIConfig, OpenAIProviderAdapter
from flowcortex_worker.provider.vendor_jobs import VendorJobTimeout

JOB_ID = "5f0c7c1e-8f7e-4a53-9a38-2f6d6f3f9c11"
RUN_ID = "0b7e1d2c-3a4b-4c5d-8e6f-7a8b9c0d1e2f"
REVISION_ID = "a71ea3ba-d72a-4c2c-985c-3a7fd98853c4"


def provider_job(capability: str, profile: str, payload: dict[str, Any]) -> dict[str, Any]:
    return {
        "schemaVersion": "1",
        "jobId": JOB_ID,
        "runId": RUN_ID,
        "correlationId": RUN_ID,
        "stepOrder": 1,
        "capability": capability,
        "params": {"profile": profile, "payload": payload},
        "outputPrefix": f"runs/{RUN_ID}/steps/1/attempts/1/",
        "attempt": 1,
        "maxRuntimeSeconds": 30,
        "issuedAt": "2026-10-10T00:00:00Z",
    }


def run_provider(conn: FakeConnection, storage: FakeStorage, queue: str = "provider.jobs") -> None:
    def parse_job(body: bytes) -> Any:
        return ProviderJob.model_validate_json(body).root

    run_consumer(
        conn,
        storage=storage,
        queue=queue,
        exchange="provider",
        parse_job=parse_job,
        execute_job=make_execute_job(queue, storage, FixtureProviderAdapter()),
        until=settled,
    )


def test_script_job_uses_provider_topology_and_publishes_typed_data() -> None:
    conn = FakeConnection()
    conn.deliver(
        provider_job(
            "generate_script",
            "product_ad_v1",
            {
                "productName": "Bình giữ nhiệt",
                "description": "Giữ đồ uống tiện lợi",
                "cta": "Đặt mua ngay",
            },
        )
    )
    storage = FakeStorage()

    run_provider(conn, storage)

    assert conn.consume.consuming is not None
    assert conn.consume.consuming[0] == "provider.jobs"
    assert all(item.exchange == "provider" for item in conn.publish.published)
    result = assert_valid(published(conn, "result")[0].body, "media-result")
    assert result["status"] == "completed"
    assert result["outputs"] == []
    assert "Bình giữ nhiệt" in result["data"]["script"]
    assert storage.puts == {}


def test_speech_job_writes_attempt_scoped_audio_output() -> None:
    conn = FakeConnection()
    conn.deliver(
        provider_job(
            "generate_speech",
            "vi_voice_v1",
            {
                "script": "Khám phá sản phẩm và đặt mua ngay hôm nay",
                "sourceRevisionId": REVISION_ID,
            },
        )
    )
    storage = FakeStorage()

    run_provider(conn, storage)

    result = assert_valid(published(conn, "result")[0].body, "media-result")
    output = result["outputs"][0]
    expected_key = f"runs/{RUN_ID}/steps/1/attempts/1/speech.wav"
    assert output["objectKey"] == expected_key
    assert output["mime"] == "audio/wav"
    assert storage.puts[expected_key][1] == "audio/wav"
    assert result["outputs"][1]["objectKey"].endswith("/manifest.json")
    assert result["data"]["sourceRevisionId"] == REVISION_ID
    assert 4_000 <= result["data"]["durationMs"] <= 10_000


VIDEO_PAYLOAD = {"prompt": "Opening hero scene", "sourceRevisionId": REVISION_ID}
SCRIPT_PAYLOAD = {"productName": "Bình", "description": "Giữ nhiệt", "cta": "Mua ngay"}


@pytest.mark.parametrize(
    ("queue", "capability", "profile", "payload", "message"),
    [
        ("provider.jobs", "generate_video", "vertical_ad_clip_v1", VIDEO_PAYLOAD,
         "generate_video jobs are handled by provider-video-worker on provider.video.jobs"),
        ("provider.video.jobs", "generate_script", "product_ad_v1", SCRIPT_PAYLOAD,
         "generate_script jobs are handled by provider-worker on provider.jobs"),
    ],
)  # fmt: skip
def test_a_job_on_the_other_pool_s_queue_is_answered_with_a_contract_violation(
    queue: str, capability: str, profile: str, payload: dict[str, Any], message: str
) -> None:
    conn = FakeConnection()
    conn.deliver(provider_job(capability, profile, payload))
    storage = FakeStorage()

    run_provider(conn, storage, queue)

    assert conn.consume.consuming is not None
    assert conn.consume.consuming[0] == queue
    result = assert_valid(published(conn, "result")[0].body, "media-result")
    assert result["status"] == "error"
    assert result["error"] == {"code": "contract_violation", "message": message, "retryable": False}
    assert storage.puts == {}


def run_with(
    conn: FakeConnection, execute_job: Any, *, redeliveries: int | None = None
) -> None:
    def parse_job(body: bytes) -> Any:
        return ProviderJob.model_validate_json(body).root

    run_consumer(
        conn,
        storage=FakeStorage(),
        queue="provider.video.jobs",
        exchange="provider",
        parse_job=parse_job,
        execute_job=execute_job,
        max_transient_redeliveries=redeliveries,
        wait=lambda seconds: None,
        until=settled,
    )


def test_a_vendor_timeout_on_the_last_delivery_is_answered_retryable() -> None:
    conn = FakeConnection()
    conn.deliver(
        provider_job("generate_video", "vertical_ad_clip_v1", VIDEO_PAYLOAD)
        | {"vendorLedger": {"requestKey": "b" * 64, "candidates": []}},
        headers={"x-delivery-count": 3},
    )

    def poll_too_long(job: Any, runner: Any, report: Any) -> Any:
        raise VendorJobTimeout("seedance", "cgt-1", ledger_id="row-7")

    run_with(conn, poll_too_long, redeliveries=3)

    result = assert_valid(published(conn, "result")[0].body, "media-result")
    assert result["status"] == "error"
    assert result["error"]["code"] == "provider_timeout"
    assert result["error"]["retryable"] is True
    assert "seedance" in result["error"]["message"]
    assert "row-7" in result["error"]["message"]
    assert conn.consume.acks == [1]


def test_a_vendor_timeout_on_a_job_without_the_ledger_block_stays_provider_request_failed() -> None:
    conn = FakeConnection()
    conn.deliver(
        provider_job("generate_video", "vertical_ad_clip_v1", VIDEO_PAYLOAD),
        headers={"x-delivery-count": 3},
    )

    def poll_too_long(job: Any, runner: Any, report: Any) -> Any:
        raise VendorJobTimeout("seedance", "cgt-1")

    run_with(conn, poll_too_long, redeliveries=3)

    result = assert_valid(published(conn, "result")[0].body, "media-result")
    assert result["error"]["code"] == "provider_request_failed"
    assert result["error"]["retryable"] is False
    assert conn.consume.acks == [1]


def test_a_vendor_timeout_before_the_last_delivery_is_retried() -> None:
    conn = FakeConnection()
    conn.deliver(
        provider_job("generate_video", "vertical_ad_clip_v1", VIDEO_PAYLOAD),
        headers={"x-delivery-count": 1},
    )

    def poll_too_long(job: Any, runner: Any, report: Any) -> Any:
        raise VendorJobTimeout("seedance", "cgt-1")

    run_with(conn, poll_too_long, redeliveries=3)

    assert published(conn, "result") == []
    assert conn.consume.rejects


def test_a_plain_transient_failure_on_the_last_delivery_stays_provider_request_failed() -> None:
    conn = FakeConnection()
    conn.deliver(
        provider_job("generate_video", "vertical_ad_clip_v1", VIDEO_PAYLOAD),
        headers={"x-delivery-count": 3},
    )

    def unavailable(job: Any, runner: Any, report: Any) -> Any:
        raise TransientError("provider unavailable")

    run_with(conn, unavailable, redeliveries=3)

    result = assert_valid(published(conn, "result")[0].body, "media-result")
    assert result["error"]["code"] == "provider_request_failed"
    assert result["error"]["retryable"] is False


def test_a_vendor_task_report_is_published_as_progress_with_the_jobs_ids() -> None:
    conn = FakeConnection()
    conn.deliver(
        provider_job("generate_video", "vertical_ad_clip_v1", VIDEO_PAYLOAD),
        headers={"x-delivery-count": 2},
    )

    def submit_then_fail(job: Any, runner: Any, report: Any) -> Any:
        report(
            VendorTaskReport(
                vendor="seedance",
                model="seedance-1.5-pro",
                fingerprint="a" * 64,
                requestKey="b" * 64,
                vendorJobId="cgt-1",
                handle={},
                status="running",
                vendorCreatedAt=datetime(2026, 11, 9, tzinfo=UTC),
            )
        )
        raise PermanentError("provider_request_failed", "ended")

    run_with(conn, submit_then_fail)

    reports = [
        assert_valid(item.body, "media-progress")
        for item in published(conn, "progress")
        if json.loads(item.body)["kind"] == "vendor_task"
    ]
    [report] = reports
    assert report["jobId"] == JOB_ID and report["runId"] == RUN_ID
    assert report["deliveryCount"] == 2
    assert report["vendorTask"]["vendorJobId"] == "cgt-1"
    assert "vendorTaskId" not in report["vendorTask"]


@pytest.mark.parametrize(
    ("configured", "expected"),
    [(None, "provider.jobs"), ("provider.jobs", "provider.jobs"),
     (" provider.video.jobs ", "provider.video.jobs")],
)  # fmt: skip
def test_provider_queue_is_one_of_the_two_provider_job_queues(
    monkeypatch: pytest.MonkeyPatch, configured: str | None, expected: str
) -> None:
    if configured is None:
        monkeypatch.delenv("PROVIDER_QUEUE", raising=False)
    else:
        monkeypatch.setenv("PROVIDER_QUEUE", configured)

    assert provider_queue() == expected


@pytest.mark.parametrize("configured", ["media.jobs", "provider.results", "provider.video"])
def test_any_other_provider_queue_refuses_to_start(
    monkeypatch: pytest.MonkeyPatch, configured: str
) -> None:
    monkeypatch.setenv("PROVIDER_QUEUE", configured)

    with pytest.raises(RuntimeError, match=r"provider\.jobs, provider\.video\.jobs"):
        provider_queue()


def test_masked_200_upstream_error_on_script_job_requeues_until_budget_exhausted(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    import io
    import urllib.request
    from unittest.mock import MagicMock

    payload_body = json.dumps(
        {
            "id": "chatcmpl-test",
            "object": "chat.completion",
            "choices": [
                {
                    "index": 0,
                    "finish_reason": "stop",
                    "message": {
                        "role": "assistant",
                        "content": "Upstream temporarily unavailable, please retry.",
                    },
                }
            ],
            "usage": {"prompt_tokens": 0, "completion_tokens": 12, "total_tokens": 12},
        }
    ).encode()

    def fake_urlopen(request: Any, timeout: float = 0) -> MagicMock:
        response = MagicMock()
        response.read = MagicMock(return_value=io.BytesIO(payload_body).read())
        response.__enter__ = MagicMock(return_value=response)
        response.__exit__ = MagicMock(return_value=False)
        return response

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)

    adapter = OpenAIProviderAdapter(
        OpenAIConfig(api_key="sk-test", base_url="https://llm-proxy.example/v1", model="m")
    )

    def parse_job(body: bytes) -> Any:
        return ProviderJob.model_validate_json(body).root

    # First delivery: requeued with default 5 s exponential backoff, no terminal result published.
    conn = FakeConnection()
    conn.deliver(provider_job("generate_script", "product_ad_v1", SCRIPT_PAYLOAD))
    waits: list[float] = []
    run_consumer(
        conn,
        storage=FakeStorage(),
        queue="provider.jobs",
        exchange="provider",
        parse_job=parse_job,
        execute_job=make_execute_job("provider.jobs", FakeStorage(), adapter),
        max_transient_redeliveries=MAX_TRANSIENT_REDELIVERIES,
        wait=waits.append,
        until=settled,
    )
    assert published(conn, "result") == []
    assert conn.consume.rejects == [(1, True)]
    assert waits == [5.0]

    # Final delivery at MAX_TRANSIENT_REDELIVERIES: answered with terminal failure and acked.
    exhausted_conn = FakeConnection()
    exhausted_conn.deliver(
        provider_job("generate_script", "product_ad_v1", SCRIPT_PAYLOAD),
        headers={"x-delivery-count": MAX_TRANSIENT_REDELIVERIES},
    )
    run_consumer(
        exhausted_conn,
        storage=FakeStorage(),
        queue="provider.jobs",
        exchange="provider",
        parse_job=parse_job,
        execute_job=make_execute_job("provider.jobs", FakeStorage(), adapter),
        max_transient_redeliveries=MAX_TRANSIENT_REDELIVERIES,
        wait=lambda s: None,
        until=settled,
    )
    result = assert_valid(published(exhausted_conn, "result")[0].body, "media-result")
    assert result["status"] == "error"
    assert result["error"]["code"] == "provider_request_failed"
    assert result["error"]["retryable"] is False
    assert exhausted_conn.consume.acks == [1]


def test_stale_delivery_acks_without_publishing_result() -> None:
    conn = FakeConnection()
    conn.deliver(
        provider_job(
            "generate_video",
            "vertical_ad_clip_v1",
            {"prompt": "p", "sourceRevisionId": REVISION_ID},
        )
    )

    def parse_job(body: bytes) -> Any:
        return ProviderJob.model_validate_json(body).root

    def stale_execute(job: Any, runner: Any, report: Any) -> Any:
        raise StaleDelivery("stale attempt")

    run_consumer(
        conn,
        storage=FakeStorage(),
        queue="provider.video.jobs",
        exchange="provider",
        parse_job=parse_job,
        execute_job=stale_execute,
        until=settled,
    )

    assert published(conn, "result") == []
    assert conn.consume.acks == [1]


def test_parallel_clips_flag_restricts_video_worker_to_wan_or_fixture(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("PRODUCT_VIDEO_PARALLEL_CLIPS_ENABLED", "true")
    monkeypatch.setenv("VIDEO_PROVIDER", "seedance")
    monkeypatch.setenv("SEEDANCE_API_KEY", "sk-test")

    with pytest.raises(RuntimeError, match=r"wan.*fixture"):
        _build_adapter(video_enabled=True)

    monkeypatch.setenv("VIDEO_PROVIDER", "veo")
    monkeypatch.setenv("VEO_API_KEY", "vk-test")
    with pytest.raises(RuntimeError, match=r"wan.*fixture"):
        _build_adapter(video_enabled=True)

    # Non-video worker on provider.jobs is unaffected by VIDEO_PROVIDER=seedance.
    _build_adapter(video_enabled=False)

    monkeypatch.setenv("VIDEO_PROVIDER", "fixture")
    _build_adapter(video_enabled=True)

    monkeypatch.setenv("VIDEO_PROVIDER", "wan")
    monkeypatch.setenv("WAN_API_KEY", "wan-test")
    _build_adapter(video_enabled=True)


def _ok_completion(content: str) -> MagicMock:
    payload = json.dumps({
        "id": "chatcmpl-test",
        "object": "chat.completion",
        "choices": [
            {
                "index": 0,
                "finish_reason": "stop",
                "message": {"role": "assistant", "content": content},
            }
        ],
    }).encode("utf-8")
    response = MagicMock()
    response.read = MagicMock(return_value=payload)
    response.__enter__ = MagicMock(return_value=response)
    response.__exit__ = MagicMock(return_value=False)
    return response


def _http_err(status: int, code: str) -> urllib.error.HTTPError:
    body = json.dumps({"error": {"code": code, "message": "upstream detail"}}).encode("utf-8")
    return urllib.error.HTTPError(
        "https://vilao.invalid/v1/chat/completions",
        status,
        "HTTP Error",
        cast(Any, {}),
        io.BytesIO(body),
    )


def test_fallback_succeeds_through_consumer_with_safe_job_scope_logs(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    outcomes: list[Any] = [
        _http_err(503, "upstream_busy"),
        _ok_completion(json.dumps({"script": "Kịch bản dự phòng. Mua ngay"})),
    ]
    models_seen: list[str] = []

    def fake_urlopen(request: Any, timeout: float = 0) -> MagicMock:
        models_seen.append(json.loads(request.data.decode("utf-8"))["model"])
        outcome = outcomes.pop(0)
        if isinstance(outcome, BaseException):
            raise outcome
        assert isinstance(outcome, MagicMock)
        return outcome

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)
    adapter = OpenAIProviderAdapter(
        OpenAIConfig(
            api_key="sk-test",
            base_url="https://vilao.invalid/v1",
            model="vgpt/gpt-6.2-sol",
            fallback_model="chib/deepseek-v4.1-flash",
        )
    )

    def parse_job(body: bytes) -> Any:
        return ProviderJob.model_validate_json(body).root

    conn = FakeConnection()
    conn.deliver(provider_job("generate_script", "product_ad_v1", SCRIPT_PAYLOAD))
    with captured_logs() as lines:
        run_consumer(
            conn,
            storage=FakeStorage(),
            queue="provider.jobs",
            exchange="provider",
            parse_job=parse_job,
            execute_job=make_execute_job("provider.jobs", FakeStorage(), adapter),
            max_transient_redeliveries=MAX_TRANSIENT_REDELIVERIES,
            wait=lambda s: None,
            until=settled,
        )

    assert models_seen == ["vgpt/gpt-6.2-sol", "chib/deepseek-v4.1-flash"]
    result = assert_valid(published(conn, "result")[0].body, "media-result")
    assert result["status"] == "completed"
    assert result["data"]["script"] == "Kịch bản dự phòng. Mua ngay"
    assert conn.consume.acks == [1]

    fallback_warnings = [
        line for line in lines if line.get("message") == "provider falling back to secondary model"
    ]
    assert len(fallback_warnings) == 1
    entry = fallback_warnings[0]
    assert entry["primaryModel"] == "vgpt/gpt-6.2-sol"
    assert entry["fallbackModel"] == "chib/deepseek-v4.1-flash"
    assert entry["fallbackReason"] == "upstream_transient"
    assert entry["providerOperation"] == "generate_script"
    assert entry["jobId"] == JOB_ID
    assert entry["runId"] == RUN_ID
    assert entry["capability"] == "generate_script"
    assert all("upstream detail" not in json.dumps(line) for line in lines)


def test_fallback_terminal_failure_acks_and_transient_failure_requeues(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def parse_job(body: bytes) -> Any:
        return ProviderJob.model_validate_json(body).root

    adapter = OpenAIProviderAdapter(
        OpenAIConfig(
            api_key="sk-test",
            base_url="https://vilao.invalid/v1",
            model="vgpt/gpt-6.2-sol",
            fallback_model="chib/deepseek-v4.1-flash",
        )
    )

    # Primary 503 + fallback 400 generic bad_request -> terminal PermanentError, acked.
    perm_outcomes: list[Any] = [
        _http_err(503, "upstream_busy"),
        _http_err(400, "bad_request"),
    ]

    def fake_urlopen_perm(request: Any, timeout: float = 0) -> MagicMock:
        raise perm_outcomes.pop(0)

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen_perm)
    perm_conn = FakeConnection()
    perm_conn.deliver(provider_job("generate_script", "product_ad_v1", SCRIPT_PAYLOAD))
    run_consumer(
        perm_conn,
        storage=FakeStorage(),
        queue="provider.jobs",
        exchange="provider",
        parse_job=parse_job,
        execute_job=make_execute_job("provider.jobs", FakeStorage(), adapter),
        max_transient_redeliveries=MAX_TRANSIENT_REDELIVERIES,
        wait=lambda s: None,
        until=settled,
    )
    perm_result = assert_valid(published(perm_conn, "result")[0].body, "media-result")
    assert perm_result["status"] == "error"
    assert perm_result["error"]["code"] == "provider_request_failed"
    assert perm_result["error"]["retryable"] is False
    assert perm_conn.consume.acks == [1]

    # Primary bad output + fallback 503 -> TransientError, requeued on delivery 1.
    trans_outcomes: list[Any] = [
        _ok_completion("not json"),
        _http_err(503, "upstream_busy"),
    ]

    def fake_urlopen_trans(request: Any, timeout: float = 0) -> MagicMock:
        outcome = trans_outcomes.pop(0)
        if isinstance(outcome, BaseException):
            raise outcome
        assert isinstance(outcome, MagicMock)
        return outcome

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen_trans)
    trans_conn = FakeConnection()
    trans_conn.deliver(provider_job("generate_script", "product_ad_v1", SCRIPT_PAYLOAD))
    run_consumer(
        trans_conn,
        storage=FakeStorage(),
        queue="provider.jobs",
        exchange="provider",
        parse_job=parse_job,
        execute_job=make_execute_job("provider.jobs", FakeStorage(), adapter),
        max_transient_redeliveries=MAX_TRANSIENT_REDELIVERIES,
        wait=lambda s: None,
        until=settled,
    )
    assert published(trans_conn, "result") == []
    assert trans_conn.consume.rejects == [(1, True)]


def test_fallback_expired_job_runtime_publishes_run_timeout_without_network_call(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    called = False

    def fake_urlopen(request: Any, timeout: float = 0) -> MagicMock:
        nonlocal called
        called = True
        return _ok_completion(json.dumps({"script": "unused"}))

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)
    adapter = OpenAIProviderAdapter(
        OpenAIConfig(
            api_key="sk-test",
            base_url="https://vilao.invalid/v1",
            model="vgpt/gpt-6.2-sol",
            fallback_model="chib/deepseek-v4.1-flash",
        )
    )

    def parse_job(body: bytes) -> Any:
        return ProviderJob.model_validate_json(body).root

    short_job = provider_job("generate_script", "product_ad_v1", SCRIPT_PAYLOAD)
    short_job["maxRuntimeSeconds"] = 5
    conn = FakeConnection()
    conn.deliver(short_job)
    run_consumer(
        conn,
        storage=FakeStorage(),
        queue="provider.jobs",
        exchange="provider",
        parse_job=parse_job,
        execute_job=make_execute_job("provider.jobs", FakeStorage(), adapter),
        max_transient_redeliveries=MAX_TRANSIENT_REDELIVERIES,
        wait=lambda s: None,
        until=settled,
    )

    assert called is False
    result = assert_valid(published(conn, "result")[0].body, "media-result")
    assert result["status"] == "error"
    assert result["error"]["code"] == "run_timeout"
    assert conn.consume.acks == [1]
