"""Permanent failures answer at once; transient ones back off and go back to the broker."""

from __future__ import annotations

import json
import tempfile
import threading
import time
from collections.abc import Mapping
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any, get_args

import boto3
import pika.exceptions
import pytest
from botocore.config import Config
from conftest import (
    MEDIA_BUCKET,
    UPLOAD_BUCKET,
    FakeConnection,
    FakeStorage,
    assert_valid,
    captured_logs,
    job_body,
    published,
    run_consumer,
    settled,
    wait_for,
)
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from flowcortex_worker.consumer import Abandoned, Consumer
from flowcortex_worker.contracts.media_result import MediaError
from flowcortex_worker.errors import ErrorCode, PermanentError, TransientError, backoff_seconds
from flowcortex_worker.handlers.registry import HandlerOutcome
from flowcortex_worker.storage import Storage


def heartbeats(conn: FakeConnection) -> int:
    return sum(
        json.loads(p.body)["kind"] == "heartbeat" for p in list(conn.publish.published)
        if p.routing_key == "progress"
    )  # fmt: skip


def failing(error: Exception) -> Any:
    def handler(job: Any, inputs: Mapping[str, Path], processes: Any) -> HandlerOutcome:
        raise error

    return lambda capability: handler


def test_permanent_error_publishes_an_error_result_and_acks() -> None:
    conn = FakeConnection()
    conn.deliver(job_body())

    run_consumer(conn, resolve=failing(PermanentError("probe_failed", "not media")), until=settled)

    [result] = published(conn, "result")
    message = assert_valid(result.body, "media-result")
    assert message["status"] == "error"
    assert message["error"] == {"code": "probe_failed", "message": "not media", "retryable": False}
    assert conn.consume.acks == [1]
    assert conn.consume.rejects == []


@pytest.mark.parametrize(
    "code",
    [
        "provider_capability_unavailable",
        "provider_request_failed",
        "provider_response_invalid",
    ],
)
def test_every_provider_failure_code_is_answered_without_a_retry(code: Any) -> None:
    conn = FakeConnection()
    conn.deliver(job_body())
    waits: list[float] = []

    run_consumer(
        conn,
        resolve=failing(PermanentError(code, "provider said no")),
        until=settled,
        wait=waits.append,
    )

    [result] = published(conn, "result")
    assert assert_valid(result.body, "media-result")["error"]["code"] == code
    assert conn.consume.acks == [1]
    assert conn.consume.rejects == []
    assert waits == []


def test_error_codes_match_the_result_contract() -> None:
    assert set(get_args(ErrorCode)) == set(get_args(MediaError.model_fields["code"].annotation))


@pytest.mark.parametrize(
    ("delivery_count", "expected"),
    [(0, 5), (1, 10), (2, 20), (3, 40), (4, 60), (9, 60)],
)
def test_backoff_doubles_from_five_seconds_and_caps_at_sixty(
    delivery_count: int, expected: float
) -> None:
    assert backoff_seconds(delivery_count) == expected


def test_transient_error_retry_after_overrides_exponential_backoff() -> None:
    waits: list[float] = []
    conn = FakeConnection()
    conn.deliver(job_body())
    run_consumer(
        conn,
        storage=FakeStorage(error=TransientError("rate limited", retry_after_seconds=23)),
        wait=waits.append,
        until=settled,
    )

    assert waits == [23]


def test_retry_after_is_capped_at_the_backoff_ceiling() -> None:
    waits: list[float] = []
    conn = FakeConnection()
    conn.deliver(job_body())
    run_consumer(
        conn,
        storage=FakeStorage(error=TransientError("quota", retry_after_seconds=3600)),
        wait=waits.append,
        until=settled,
    )

    assert waits == [60]


def test_transient_retry_budget_publishes_terminal_error() -> None:
    conn = FakeConnection()
    conn.deliver(job_body(), headers={"x-delivery-count": 3})
    waits: list[float] = []

    def fail(job: Any, runner: Any) -> Any:
        raise TransientError("provider unavailable")

    run_consumer(
        conn,
        execute_job=fail,
        max_transient_redeliveries=3,
        wait=waits.append,
        until=settled,
    )

    [result] = published(conn, "result")
    message = assert_valid(result.body, "media-result")
    assert message["status"] == "error"
    assert message["error"]["code"] == "provider_request_failed"
    assert message["deliveryCount"] == 3
    assert conn.consume.acks == [1]
    assert conn.consume.rejects == []
    assert waits == []


def test_transient_retry_budget_error_keeps_the_transient_reason() -> None:
    conn = FakeConnection()
    conn.deliver(job_body(), headers={"x-delivery-count": 3})

    def fail(job: Any, runner: Any) -> Any:
        raise TransientError("provider unavailable")

    run_consumer(
        conn, execute_job=fail, max_transient_redeliveries=3, wait=lambda s: None, until=settled
    )

    [result] = published(conn, "result")
    message = json.loads(result.body)["error"]["message"]
    assert message == "transient retry budget exhausted after 3 redeliveries: provider unavailable"


def test_unconfirmed_budget_exhausted_result_is_requeued() -> None:
    conn = FakeConnection()
    conn.deliver(job_body(), headers={"x-delivery-count": 3})
    waits: list[float] = []

    def fail(job: Any, runner: Any) -> Any:
        raise TransientError("provider unavailable")

    def start(c: FakeConnection) -> None:
        if len(c.channels) == 2:
            c.publish.fail_publish_routing_keys.add("result")

    conn.hooks.append(start)
    run_consumer(
        conn,
        execute_job=fail,
        max_transient_redeliveries=3,
        wait=waits.append,
        until=settled,
    )

    assert conn.consume.acks == []
    assert conn.consume.rejects == [(1, True)]
    assert waits == [backoff_seconds(3)]


def _started_fails_transiently(monkeypatch: pytest.MonkeyPatch) -> None:
    """A transient failure before the job runs: the `started` progress cannot be built."""
    original = Consumer._progress

    def progress(self: Consumer, job: Any, delivery: Any, kind: str) -> Any:
        if kind == "started":
            raise TransientError("progress unavailable")
        return original(self, job, delivery, kind)  # type: ignore[arg-type]

    monkeypatch.setattr(Consumer, "_progress", progress)


def test_transient_failure_outside_the_job_on_the_last_delivery_answers_once(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _started_fails_transiently(monkeypatch)
    conn = FakeConnection()
    conn.deliver(job_body(), headers={"x-delivery-count": 3})
    waits: list[float] = []

    run_consumer(conn, max_transient_redeliveries=3, wait=waits.append, until=settled)

    [result] = published(conn, "result")
    message = assert_valid(result.body, "media-result")
    assert message["error"]["code"] == "provider_request_failed"
    assert message["error"]["message"] == (
        "transient retry budget exhausted after 3 redeliveries: progress unavailable"
    )
    assert conn.consume.acks == [1]
    assert conn.consume.rejects == []
    assert waits == []


def test_budget_exhausted_answer_that_cannot_be_acked_is_abandoned_not_crashed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _started_fails_transiently(monkeypatch)
    original = Consumer._settle_now

    def settle(self: Consumer, delivery: Any, action: Any) -> None:
        if action == "ack":
            raise Abandoned("channel closed")
        original(self, delivery, action)

    monkeypatch.setattr(Consumer, "_settle_now", settle)
    conn = FakeConnection()
    conn.deliver(job_body(), headers={"x-delivery-count": 3})

    with captured_logs() as lines:
        run_consumer(
            conn,
            max_transient_redeliveries=3,
            wait=lambda s: None,
            grace_seconds=0.1,
            until=lambda c: bool(c.channels and published(c, "result")),
        )

    messages = [line["message"] for line in lines]
    assert "job thread crashed" not in messages
    assert "job abandoned; the broker redelivers it" in messages
    assert conn.consume.acks == []


@pytest.mark.parametrize(
    ("props", "expected_wait", "expected_count"),
    [
        ({}, 5, 0),
        ({"headers": {"x-delivery-count": 2}}, 20, 2),
        ({"redelivered": True}, 10, 1),
    ],
    ids=["first-delivery", "delivery-count-header", "redelivered-without-header"],
)
def test_transient_error_waits_then_rejects_for_redelivery(
    props: dict[str, Any], expected_wait: float, expected_count: int
) -> None:
    conn = FakeConnection()
    conn.deliver(job_body(), **props)
    waits: list[float] = []

    def wait(seconds: float) -> None:
        waits.append(seconds)
        # Held until two heartbeats went out during the wait, however slow the machine.
        before = heartbeats(conn)
        wait_for(lambda: heartbeats(conn) >= before + 2)

    run_consumer(
        conn,
        storage=FakeStorage(error=TransientError("store unreachable")),
        until=settled,
        wait=wait,
        heartbeat_seconds=0.05,
    )

    assert waits == [expected_wait]
    assert conn.consume.rejects == [(1, True)]
    assert conn.consume.acks == []
    assert published(conn, "result") == []
    progress = [assert_valid(p.body, "media-progress") for p in published(conn, "progress")]
    assert progress[0]["kind"] == "started"
    assert {p["deliveryCount"] for p in progress} == {expected_count}
    # The wait heartbeats, so the lease stays alive while the job is held back.
    assert [p["kind"] for p in progress[1:]].count("heartbeat") >= 2


def test_handler_crash_is_transient() -> None:
    conn = FakeConnection()
    conn.deliver(job_body())

    run_consumer(conn, resolve=failing(RuntimeError("boom")), until=settled, wait=lambda s: None)

    assert conn.consume.rejects == [(1, True)]
    assert published(conn, "result") == []


def test_unconfirmed_result_publish_is_never_acked() -> None:
    conn = FakeConnection()
    conn.deliver(job_body())
    waits: list[float] = []

    def ok(job: Any, inputs: Mapping[str, Path], processes: Any) -> HandlerOutcome:
        return HandlerOutcome(data={}, files=[])

    def start(c: FakeConnection) -> None:
        if len(c.channels) == 2:
            c.publish.fail_publish_routing_keys.add("result")

    conn.hooks.append(start)
    run_consumer(conn, resolve=lambda capability: ok, until=settled, wait=waits.append)

    assert conn.consume.acks == []
    assert conn.consume.rejects == [(1, True)]
    assert waits == [5]


def unreachable_store() -> Storage:
    client = boto3.client(
        "s3",
        endpoint_url="http://127.0.0.1:1",
        region_name="us-east-1",
        aws_access_key_id="x",
        aws_secret_access_key="y",
        config=Config(retries={"max_attempts": 1}, connect_timeout=1),
    )
    return Storage(client, MEDIA_BUCKET, UPLOAD_BUCKET)


def test_transient_store_failure_names_no_url_in_logs_or_spans(
    spans: InMemorySpanExporter,
) -> None:
    conn = FakeConnection()
    conn.deliver(job_body())

    with captured_logs() as lines:
        run_consumer(conn, storage=unreachable_store(), until=settled, wait=lambda s: None)

    assert conn.consume.rejects == [(1, True)]
    retried = [line for line in lines if line["message"].startswith("transient failure")]
    assert retried
    assert retried[0]["error"] == "TransientError"
    assert UPLOAD_BUCKET in retried[0]["reason"]
    for line in lines:
        assert "http://" not in json.dumps(line), line
    events = [event for span in spans.get_finished_spans() for event in span.events]
    assert any(event.name == "exception" for event in events)
    for event in events:
        assert "http://" not in json.dumps(dict(event.attributes or {})), event.name


def test_backoff_wait_ends_when_the_connection_is_lost() -> None:
    conn = FakeConnection()
    conn.deliver(job_body())
    stop = threading.Event()

    def lose_after_started(c: FakeConnection) -> None:
        if len(c.channels) == 2 and published(c, "progress"):
            c.closed = True
            raise pika.exceptions.StreamLostError("connection reset")

    conn.hooks.append(lose_after_started)
    executor = ThreadPoolExecutor(max_workers=1)
    consumer = Consumer(
        conn,
        storage=FakeStorage(error=TransientError("store unreachable")),
        worker_id="worker-test",
        heartbeat_seconds=30.0,
        stop=stop,
        executor=executor,
        liveness_file=Path(tempfile.gettempdir()) / "fake-alive-backoff",
    )
    with pytest.raises(pika.exceptions.StreamLostError):
        consumer.run()
    began = time.monotonic()
    executor.shutdown(wait=True)

    # The default wait is the 5 s backoff; losing the connection must cut it short.
    assert time.monotonic() - began < 2
    assert conn.consume.rejects == []
