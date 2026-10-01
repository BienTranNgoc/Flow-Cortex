"""Capabilities resolve through an explicit table; an unknown one never falls back."""

from __future__ import annotations

import json

import pytest
from conftest import FakeConnection, assert_valid, job_body, published, run_consumer, settled

from flowcortex_worker.errors import PermanentError
from flowcortex_worker.handlers import probe, registry


def test_probe_is_registered() -> None:
    assert registry.resolve("probe") is probe.handle


@pytest.mark.parametrize("capability", ["unknown-thing", "", "echo"])
def test_unknown_capability_is_a_contract_violation(capability: str) -> None:
    with pytest.raises(PermanentError) as rejected:
        registry.resolve(capability)

    assert rejected.value.code == "contract_violation"


def test_job_naming_an_unknown_capability_is_answered_and_acked_without_a_handler() -> None:
    conn = FakeConnection()
    body = job_body(capability="unknown-thing")
    conn.deliver(body)

    run_consumer(conn, resolve=lambda c: pytest.fail("no handler may run"), until=settled)

    [result] = published(conn, "result")
    message = assert_valid(result.body, "media-result")
    assert message["jobId"] == body["jobId"]
    assert message["error"]["code"] == "contract_violation"
    assert message["error"]["retryable"] is False
    assert result.properties.correlation_id == body["runId"]
    assert conn.consume.acks == [1]


def test_body_without_usable_ids_is_dead_lettered() -> None:
    conn = FakeConnection()
    conn.deliver(json.dumps({"status": "done"}).encode())

    run_consumer(conn, until=settled)

    assert published(conn, "result") == []
    assert conn.consume.rejects == [(1, False)]
