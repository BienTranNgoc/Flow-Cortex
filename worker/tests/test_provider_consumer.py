from __future__ import annotations

from typing import Any

import pytest
from conftest import FakeConnection, FakeStorage, assert_valid, published, run_consumer, settled

from flowcortex_worker.provider.__main__ import make_execute_job, provider_queue
from flowcortex_worker.provider.adapter import FixtureProviderAdapter
from flowcortex_worker.provider.contracts.provider_job import ProviderJob

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
