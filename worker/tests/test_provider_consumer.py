from __future__ import annotations

import json
from datetime import UTC, datetime
from typing import Any

import pytest
from conftest import FakeConnection, FakeStorage, assert_valid, published, run_consumer, settled

from flowcortex_worker.contracts.media_progress import VendorTaskReport
from flowcortex_worker.errors import PermanentError, TransientError
from flowcortex_worker.provider.__main__ import make_execute_job, provider_queue
from flowcortex_worker.provider.adapter import FixtureProviderAdapter
from flowcortex_worker.provider.contracts.provider_job import ProviderJob
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
