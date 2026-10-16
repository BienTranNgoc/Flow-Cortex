from __future__ import annotations

from typing import Any

from conftest import FakeConnection, FakeStorage, assert_valid, published, run_consumer, settled

from flowcortex_worker.provider.adapter import FixtureProviderAdapter
from flowcortex_worker.provider.capabilities import execute
from flowcortex_worker.provider.contracts.provider_job import ProviderJob
from flowcortex_worker.runtime import ProcessRunner

JOB_ID = "5f0c7c1e-8f7e-4a53-9a38-2f6d6f3f9c11"
RUN_ID = "0b7e1d2c-3a4b-4c5d-8e6f-7a8b9c0d1e2f"


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


def run_provider(conn: FakeConnection, storage: FakeStorage) -> None:
    adapter = FixtureProviderAdapter()

    def parse_job(body: bytes) -> Any:
        return ProviderJob.model_validate_json(body).root

    def execute_job(job: Any, runner: ProcessRunner) -> Any:
        runner.check()
        return execute(ProviderJob(root=job), adapter)

    run_consumer(
        conn,
        storage=storage,
        queue="provider.jobs",
        exchange="provider",
        parse_job=parse_job,
        execute_job=execute_job,
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
            {"script": "Khám phá sản phẩm và đặt mua ngay hôm nay"},
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
    assert 15_000 <= result["data"]["durationMs"] <= 30_000
