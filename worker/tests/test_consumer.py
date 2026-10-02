"""The consumer's contract with pika: one owner thread, confirmed publishes, ack on success."""

from __future__ import annotations

import threading
import time
from collections.abc import Mapping
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any

import pika.exceptions
from conftest import (
    FakeConnection,
    FakeStorage,
    assert_valid,
    job_body,
    published,
    run_consumer,
    settled,
    wait_for,
)

from flowcortex_worker.consumer import Consumer
from flowcortex_worker.handlers.registry import HandlerOutcome, OutputFile
from flowcortex_worker.runtime import ProcessRunner, Supervisor


def recording_handler(calls: list[str], conn: FakeConnection) -> Any:
    def handler(job: Any, inputs: Mapping[str, Path], processes: ProcessRunner) -> HandlerOutcome:
        calls.append(f"handler after {len(published(conn, 'progress'))} progress")
        return HandlerOutcome(
            data={"videoCodec": "h264", "audioCodec": None},
            files=[OutputFile("video-details.json", "application/json", b'{"streams":[]}')],
        )

    return lambda capability: handler


def test_started_is_published_before_the_handler_runs() -> None:
    conn = FakeConnection()
    conn.deliver(job_body())
    calls: list[str] = []

    run_consumer(conn, resolve=recording_handler(calls, conn), until=settled)

    assert calls == ["handler after 1 progress"]
    started = assert_valid(published(conn, "progress")[0].body, "media-progress")
    assert started["kind"] == "started"
    assert started["deliveryCount"] == 0


def test_channels_prefetch_one_and_confirmed_publishes() -> None:
    conn = FakeConnection()
    conn.deliver(job_body())

    run_consumer(conn, resolve=recording_handler([], conn), until=settled)

    assert conn.consume.prefetch == 1
    assert conn.consume.consuming is not None
    assert conn.consume.consuming[0] == "media.jobs"
    assert conn.consume.cancel_callbacks
    assert conn.publish.confirming
    assert not conn.consume.confirming


def test_result_is_confirmed_persistent_and_acked_on_the_consume_channel() -> None:
    conn = FakeConnection()
    body = job_body()
    conn.deliver(body, headers={"x-delivery-count": 1})
    storage = FakeStorage()

    run_consumer(conn, storage=storage, resolve=recording_handler([], conn), until=settled)

    [result] = published(conn, "result")
    assert result.exchange == "media"
    assert result.mandatory is True
    assert result.properties.delivery_mode == 2
    assert result.properties.message_id == f"{body['jobId']}:1:result"
    assert result.properties.correlation_id == body["runId"]
    assert result.properties.content_type == "application/json"
    message = assert_valid(result.body, "media-result")
    assert message["status"] == "completed"
    assert message["deliveryCount"] == 1
    assert message["workerId"] == "worker-test"
    # A null summary field survives: only the envelope's absent optionals are dropped.
    assert message["data"] == {"videoCodec": "h264", "audioCodec": None}
    key = f"runs/{body['runId']}/steps/1/video-details.json"
    assert message["outputs"] == [
        {
            "name": "video-details.json",
            "bucket": "flowcortex-media",
            "objectKey": key,
            "mime": "application/json",
            "bytes": 14,
        }
    ]
    assert storage.puts[key] == (b'{"streams":[]}', "application/json")
    for progress in published(conn, "progress"):
        assert progress.properties.correlation_id == body["runId"]
        assert progress.mandatory is True
        assert progress.properties.delivery_mode == 2
    assert conn.consume.acks == [1]


def test_pika_is_touched_only_from_the_connection_thread() -> None:
    conn = FakeConnection()
    conn.deliver(job_body())

    def slow(job: Any, inputs: Mapping[str, Path], processes: ProcessRunner) -> HandlerOutcome:
        wait_for(lambda: len(published(conn, "progress")) >= 3)
        return HandlerOutcome(data={}, files=[])

    run_consumer(conn, resolve=lambda c: slow, until=settled, heartbeat_seconds=0.02)

    assert len(published(conn, "progress")) >= 3
    assert conn.thread_violations == []
    assert {p.thread for p in conn.publish.published} == {threading.current_thread()}


def test_no_heartbeat_follows_the_result() -> None:
    conn = FakeConnection()
    conn.deliver(job_body())

    def slow(job: Any, inputs: Mapping[str, Path], processes: ProcessRunner) -> HandlerOutcome:
        wait_for(lambda: len(published(conn, "progress")) >= 3)
        return HandlerOutcome(data={}, files=[])

    run_consumer(conn, resolve=lambda c: slow, until=settled, heartbeat_seconds=0.001)
    count = len(conn.publish.published)
    time.sleep(0.05)

    assert conn.publish.published[-1].routing_key == "result"
    assert len(conn.publish.published) == count


def test_a_stalled_input_download_is_stopped_at_the_maximum_run_time() -> None:
    conn = FakeConnection()
    conn.deliver(job_body(maxRuntimeSeconds=1))

    began = time.monotonic()
    run_consumer(conn, storage=FakeStorage(stall=True), until=settled)

    assert time.monotonic() - began < 5
    message = assert_valid(published(conn, "result")[0].body, "media-result")
    assert message["error"]["code"] == "run_timeout"
    assert conn.consume.acks == [1]


def test_a_run_past_its_maximum_is_killed_and_answered_run_timeout() -> None:
    conn = FakeConnection()
    conn.deliver(job_body(maxRuntimeSeconds=1))
    outcomes: list[int] = []

    def hangs(job: Any, inputs: Mapping[str, Path], processes: ProcessRunner) -> HandlerOutcome:
        try:
            processes.run(["sleep", "30"])
        finally:
            outcomes.append(1)
        return HandlerOutcome(data={}, files=[])

    began = time.monotonic()
    run_consumer(conn, resolve=lambda c: hangs, until=settled)

    assert time.monotonic() - began < 5
    [result] = published(conn, "result")
    message = assert_valid(result.body, "media-result")
    assert message["error"]["code"] == "run_timeout"
    assert message["error"]["retryable"] is False
    assert conn.consume.acks == [1]


def test_output_prefix_not_matching_the_run_is_a_contract_violation() -> None:
    conn = FakeConnection()
    body = job_body()
    body["outputPrefix"] = "runs/00000000-0000-0000-0000-000000000000/steps/1/"
    conn.deliver(body)
    calls: list[str] = []

    run_consumer(conn, resolve=recording_handler(calls, conn), until=settled)

    assert calls == []
    message = assert_valid(published(conn, "result")[0].body, "media-result")
    assert message["error"]["code"] == "contract_violation"
    assert conn.consume.acks == [1]


def test_sigterm_mid_job_stops_consuming_and_returns_the_message() -> None:
    conn = FakeConnection()
    conn.deliver(job_body())
    stop = threading.Event()

    def hangs(job: Any, inputs: Mapping[str, Path], processes: ProcessRunner) -> HandlerOutcome:
        stop.set()
        processes.run(["sleep", "30"])
        return HandlerOutcome(data={}, files=[])

    executor = ThreadPoolExecutor(max_workers=1)
    consumer = Consumer(
        conn,
        storage=FakeStorage(),
        worker_id="worker-test",
        heartbeat_seconds=30.0,
        stop=stop,
        executor=executor,
        liveness_file=Path("/tmp/fake-alive-sigterm"),
        grace_seconds=0.5,
        resolve=lambda c: hangs,
    )
    began = time.monotonic()
    assert consumer.run() == "stopped"
    executor.shutdown(wait=True)

    assert time.monotonic() - began < 5
    assert conn.consume.cancelled
    assert conn.consume.rejects == [(1, True)]
    assert published(conn, "result") == []


class Connections:
    """A connect() for the supervisor: fails, hands out fake connections, records waits."""

    def __init__(self, plan: list[FakeConnection | Exception]) -> None:
        self.plan = plan
        self.handed_out: list[FakeConnection] = []

    def __call__(self) -> FakeConnection:
        step = self.plan.pop(0)
        if isinstance(step, Exception):
            raise step
        self.handed_out.append(step)
        return step


def supervise(
    connections: Connections, resolve: Any, stop: threading.Event
) -> tuple[list[float], Supervisor]:
    waits: list[float] = []
    executor = ThreadPoolExecutor(max_workers=1)

    def make_consumer(conn: Any) -> Consumer:
        return Consumer(
            conn,
            storage=FakeStorage(),
            worker_id="worker-test",
            heartbeat_seconds=30.0,
            stop=stop,
            executor=executor,
            liveness_file=Path("/tmp/fake-alive-supervisor"),
            grace_seconds=1.0,
            resolve=resolve,
        )

    supervisor = Supervisor(
        connect=connections, make_consumer=make_consumer, stop=stop, wait=waits.append
    )
    supervisor.run()
    executor.shutdown(wait=True)
    return waits, supervisor


def hanging_until_killed(killed: list[int], running: threading.Event) -> Any:
    def handler(job: Any, inputs: Mapping[str, Path], processes: ProcessRunner) -> HandlerOutcome:
        running.set()
        began = time.monotonic()
        try:
            processes.run(["sleep", "30"])
        finally:
            killed.append(round(time.monotonic() - began))
        return HandlerOutcome(data={}, files=[])

    return lambda capability: handler


def test_broker_cancel_kills_the_subprocess_and_reconnects() -> None:
    stop = threading.Event()
    killed: list[int] = []
    running = threading.Event()
    first, second = FakeConnection(), FakeConnection()
    first.deliver(job_body())
    first.hooks.append(lambda c: c.cancel_consumer() if running.is_set() else None)
    second.hooks.append(lambda c: stop.set())

    waits, _ = supervise(Connections([first, second]), hanging_until_killed(killed, running), stop)

    assert killed == [0]
    assert first.closed
    assert first.consume.acks == []
    assert first.consume.rejects == []
    assert second.consume.consuming is not None
    assert waits == [1]


def test_connection_loss_kills_the_subprocess_and_reconnects_with_backoff() -> None:
    stop = threading.Event()
    killed: list[int] = []
    running = threading.Event()
    first, second = FakeConnection(), FakeConnection()
    first.deliver(job_body())

    def lose(c: FakeConnection) -> None:
        if running.is_set():
            c.closed = True
            raise pika.exceptions.StreamLostError("connection reset")

    first.hooks.append(lose)
    second.hooks.append(lambda c: stop.set())
    plan: list[FakeConnection | Exception] = [
        first,
        pika.exceptions.AMQPConnectionError("refused"),
        pika.exceptions.AMQPConnectionError("refused"),
        second,
    ]

    waits, _ = supervise(Connections(plan), hanging_until_killed(killed, running), stop)

    assert killed == [0]
    assert first.consume.acks == []
    assert waits == [1, 2, 4]
    assert second.consume.consuming is not None


def test_a_queue_that_cannot_be_consumed_keeps_backing_off() -> None:
    stop = threading.Event()
    refused = [FakeConnection() for _ in range(4)]
    for conn in refused:
        conn.consume_error = pika.exceptions.ChannelClosedByBroker(404, "NOT_FOUND - no queue")
    last = FakeConnection()
    last.hooks.append(lambda c: stop.set())

    waits, _ = supervise(Connections([*refused, last]), None, stop)

    assert waits == [1, 2, 4, 8]
    assert all(conn.closed for conn in refused)


def test_reconnect_backoff_is_capped_at_sixty_seconds() -> None:
    stop = threading.Event()
    last = FakeConnection()
    last.hooks.append(lambda c: stop.set())
    plan: list[FakeConnection | Exception] = [
        pika.exceptions.AMQPConnectionError("refused") for _ in range(8)
    ]
    plan.append(last)

    waits, _ = supervise(Connections(plan), None, stop)

    assert waits == [1, 2, 4, 8, 16, 32, 60, 60]
