"""Tests for dedicated RabbitMQ control protocol and parallel clip cancellation."""

from __future__ import annotations

import json
import re
import subprocess
import threading
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any
from uuid import UUID

import pika
import pika.exceptions
import pytest
from conftest import FakeConnection, FakeStorage, published, run_consumer, settled

from flowcortex_worker.contracts.media_control_request import MediaControlRequest
from flowcortex_worker.contracts.media_control_response import MediaControlResponse
from flowcortex_worker.errors import PermanentError, StaleDelivery
from flowcortex_worker.provider.adapter import (
    CancelResult,
    FixtureProviderAdapter,
    VendorProbe,
    VideoArtifact,
    VideoIdentity,
    VideoRequest,
)
from flowcortex_worker.provider.capabilities import (
    ExecutionContext,
    _store_video_task,
    _video_idempotency_key,
    execute,
)
from flowcortex_worker.provider.contracts.provider_job import ProviderJob
from flowcortex_worker.provider.control import (
    CONTROL_EXCHANGE,
    CONTROL_ROUTING_KEY,
    ControlClient,
    ControlClientClosed,
    ControlConfig,
)

JOB_ID = UUID("5f0c7c1e-8f7e-4a53-9a38-2f6d6f3f9c11")
RUN_ID = UUID("0b7e1d2c-3a4b-4c5d-8e6f-7a8b9c0d1e2f")
REVISION_ID = "a71ea3ba-d72a-4c2c-985c-3a7fd98853c4"
PNG_BYTES = b"\x89PNG\r\n\x1a\n" + b"\x00" * 32
MP4_BYTES = b"\x00\x00\x00\x20ftypisom" + b"\x00" * 32
WORKER_PERM_REPLY_RE = re.compile(r"^provider\.control\.reply\.[A-Za-z0-9._-]+$")


class _Abort:
    def __init__(self) -> None:
        self.checks = 0

    def check(self) -> None:
        self.checks += 1

    def run(self, argv: Any) -> Any:
        Path(argv[-1]).write_bytes(MP4_BYTES)
        return subprocess.CompletedProcess(argv, 0)


class _InMemoryStorage(FakeStorage):
    def head_object(self, bucket: str, key: str) -> dict[str, Any] | None:
        return {"ContentLength": len(self.puts[key][0])} if key in self.puts else None

    def fetch_bytes(self, bucket: str, key: str, abort: Any) -> bytes:
        return self.puts[key][0] if key in self.puts else b""


class _RecordingVideoAdapter:
    def __init__(
        self,
        *,
        on_submit: Callable[[], str] | None = None,
        on_await: Callable[[str, Any], VideoArtifact] | None = None,
        on_cancel: Callable[[str], CancelResult] | None = None,
    ) -> None:
        self.submitted = 0
        self.awaited: list[str] = []
        self.cancelled: list[str] = []
        self._on_submit = on_submit
        self._on_await = on_await
        self._on_cancel = on_cancel

    def video_identity(self) -> VideoIdentity:
        return VideoIdentity("wan", "wan-video/wan-2.2-5b-fast", 5, "9:16", "480p", False)

    def vendor_scope(self) -> dict[str, str]:
        return {"vendor": "wan"}

    def probe_video(self, task_id: str) -> VendorProbe:
        return "running"

    def submit_video(self, request: VideoRequest) -> str:
        self.submitted += 1
        if self._on_submit is not None:
            return self._on_submit()
        return f"pred-{self.submitted}"

    def await_video(self, task_id: str, abort: Any) -> VideoArtifact:
        self.awaited.append(task_id)
        if self._on_await is not None:
            return self._on_await(task_id, abort)
        return VideoArtifact(MP4_BYTES, task_id)

    def cancel_video(self, task_id: str) -> CancelResult:
        self.cancelled.append(task_id)
        if self._on_cancel is not None:
            return self._on_cancel(task_id)
        return CancelResult(confirmed=True, status="canceled")


class _ScriptedRpcBroker:
    """In-memory pika BlockingConnection substitute driving ControlClient._on_reply."""

    def __init__(
        self,
        responder: Callable[[MediaControlRequest, Any, _ScriptedRpcChannel], None],
    ) -> None:
        self.responder = responder
        self.connect_attempts = 0
        self.fail_connect_times = 0
        self.declared_reply_queues: list[tuple[str, bool, bool]] = []
        self.published: list[tuple[str, str, bytes, Any]] = []

    def connect(self, _amqp_url: str) -> _ScriptedRpcConnection:
        self.connect_attempts += 1
        if self.fail_connect_times > 0:
            self.fail_connect_times -= 1
            raise pika.exceptions.AMQPConnectionError("broker unreachable")
        return _ScriptedRpcConnection(self)


class _ScriptedRpcConnection:
    def __init__(self, broker: _ScriptedRpcBroker) -> None:
        self.broker = broker
        self.is_open = True
        self.closed = False
        self._chan = _ScriptedRpcChannel(self)

    def channel(self) -> _ScriptedRpcChannel:
        return self._chan

    def process_data_events(self, time_limit: float = 0.0) -> None:
        self._chan.drain(time_limit)

    def close(self) -> None:
        self.is_open = False
        self.closed = True


class _ScriptedRpcChannel:
    def __init__(self, conn: _ScriptedRpcConnection) -> None:
        self.conn = conn
        self.is_open = True
        self.callback: Any = None
        self.inbox: list[tuple[Any, bytes]] = []

    def queue_declare(
        self, *, queue: str, durable: bool, exclusive: bool, auto_delete: bool
    ) -> None:
        assert not durable
        assert WORKER_PERM_REPLY_RE.fullmatch(queue)
        self.conn.broker.declared_reply_queues.append((queue, exclusive, auto_delete))

    def basic_consume(self, *, queue: str, on_message_callback: Any, auto_ack: bool) -> str:
        assert auto_ack
        self.callback = on_message_callback
        return "ctag-1"

    def basic_publish(
        self, *, exchange: str, routing_key: str, body: bytes, properties: Any
    ) -> None:
        self.conn.broker.published.append((exchange, routing_key, body, properties))
        req = MediaControlRequest.model_validate_json(body)
        self.conn.broker.responder(req, properties, self)

    def enqueue_reply(self, properties: Any, body: bytes) -> None:
        self.inbox.append((properties, body))

    def drain(self, time_limit: float) -> None:
        while self.inbox and self.callback is not None:
            props, body = self.inbox.pop(0)
            self.callback(self, None, props, body)
        if not self.inbox and time_limit > 0:
            time.sleep(min(time_limit, 0.01))

    def close(self) -> None:
        self.is_open = False


def _video_job(*, attempt: int = 1, with_ledger: bool = True) -> ProviderJob:
    payload: dict[str, Any] = {
        "schemaVersion": "1",
        "jobId": str(JOB_ID),
        "runId": str(RUN_ID),
        "correlationId": str(RUN_ID),
        "stepOrder": 5,
        "capability": "generate_video",
        "params": {
            "profile": "vertical_ad_clip_v1",
            "payload": {"prompt": "Product clip", "sourceRevisionId": REVISION_ID},
        },
        "outputPrefix": f"runs/{RUN_ID}/steps/5/attempts/{attempt}/",
        "attempt": attempt,
        "maxRuntimeSeconds": 120,
        "issuedAt": "2026-11-18T10:00:00Z",
    }
    if with_ledger:
        payload["vendorLedger"] = {"requestKey": "a" * 64, "candidates": []}
    return ProviderJob.model_validate(payload)


def _reply_props(correlation_id: str | None) -> Any:
    return pika.BasicProperties(correlation_id=correlation_id, content_type="application/json")


def _reply_bytes(
    req: MediaControlRequest, decision: str, **override: Any
) -> bytes:
    body = {
        "schemaVersion": "1",
        "runId": str(req.runId),
        "jobId": str(req.jobId),
        "attempt": req.attempt,
        "decision": decision,
    } | override
    return json.dumps(body).encode("utf-8")


def _context(
    storage: FakeStorage, control: ControlClient, reports: list[Any], abort: Any | None = None
) -> ExecutionContext:
    return ExecutionContext(
        storage=storage,
        bucket="flowcortex-media",
        output_prefix=f"runs/{RUN_ID}/steps/5/attempts/1/",
        abort=abort or _Abort(),
        report=reports.append,
        control=control,
    )


def test_cancel_before_submit_blocks_submit_and_fails_with_sibling_failed() -> None:
    def responder(req: MediaControlRequest, props: Any, ch: _ScriptedRpcChannel) -> None:
        ch.enqueue_reply(
            _reply_props(props.correlation_id), _reply_bytes(req, "cancel_sibling_failed")
        )

    broker = _ScriptedRpcBroker(responder)
    client = ControlClient(
        ControlConfig(amqp_url="amqp://unused", worker_id="worker/1"),
        connect=broker.connect,
    )
    adapter = _RecordingVideoAdapter()
    reports: list[Any] = []
    storage = _InMemoryStorage()
    job = _video_job()

    with client_guard(client), pytest.raises(PermanentError) as exc_info:
        execute(job, adapter, _context(storage, client, reports))

    assert exc_info.value.code == "sibling_failed"
    assert adapter.submitted == 0
    assert adapter.cancelled == []
    assert broker.declared_reply_queues[0][1:] == (True, True)


def test_cancel_during_submit_cancels_immediately_once_prediction_id_received(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls = 0

    def responder(req: MediaControlRequest, props: Any, ch: _ScriptedRpcChannel) -> None:
        nonlocal calls
        calls += 1
        decision = "continue" if calls == 1 else "cancel_sibling_failed"
        ch.enqueue_reply(_reply_props(props.correlation_id), _reply_bytes(req, decision))

    broker = _ScriptedRpcBroker(responder)
    client = ControlClient(
        ControlConfig(amqp_url="amqp://unused", poll_interval_seconds=0.02),
        connect=broker.connect,
    )
    job = _video_job()
    reports: list[Any] = []
    context = _context(FakeStorage(), client, reports)
    session = client.open_session(RUN_ID, JOB_ID, 1, abort=context.abort)
    monkeypatch.setattr(client, "open_session", lambda *args, **kwargs: session)

    def slow_submit() -> str:
        assert session.wake_event.wait(timeout=5.0)
        assert session.is_sibling_failed()
        return "pred-during-submit"

    adapter = _RecordingVideoAdapter(on_submit=slow_submit)
    with client_guard(client), pytest.raises(PermanentError) as exc_info:
        execute(job, adapter, context)

    assert exc_info.value.code == "sibling_failed"
    assert adapter.submitted == 1
    assert adapter.awaited == []
    assert adapter.cancelled == ["pred-during-submit"]
    assert any(r.status == "failed" and r.vendorJobId == "pred-during-submit" for r in reports)


def test_cancel_during_polling_wakes_sleep_immediately_and_never_resubmits() -> None:
    calls = 0

    def responder(req: MediaControlRequest, props: Any, ch: _ScriptedRpcChannel) -> None:
        nonlocal calls
        calls += 1
        decision = "continue" if calls == 1 else "cancel_sibling_failed"
        ch.enqueue_reply(_reply_props(props.correlation_id), _reply_bytes(req, decision))

    broker = _ScriptedRpcBroker(responder)
    client = ControlClient(
        ControlConfig(amqp_url="amqp://unused", poll_interval_seconds=0.03),
        connect=broker.connect,
    )

    def long_sleep_await(task_id: str, abort: Any) -> VideoArtifact:
        abort.wait(10.0)
        abort.check()
        return VideoArtifact(MP4_BYTES, task_id)

    adapter = _RecordingVideoAdapter(on_await=long_sleep_await)
    reports: list[Any] = []
    started = time.monotonic()

    with client_guard(client), pytest.raises(PermanentError) as exc_info:
        execute(_video_job(), adapter, _context(FakeStorage(), client, reports))

    elapsed = time.monotonic() - started
    assert elapsed < 2.0
    assert exc_info.value.code == "sibling_failed"
    assert adapter.submitted == 1
    assert adapter.cancelled == ["pred-1"]


def test_completed_clip_before_cancel_takes_effect_is_kept() -> None:
    def responder(req: MediaControlRequest, props: Any, ch: _ScriptedRpcChannel) -> None:
        ch.enqueue_reply(_reply_props(props.correlation_id), _reply_bytes(req, "continue"))

    broker = _ScriptedRpcBroker(responder)
    client = ControlClient(
        ControlConfig(amqp_url="amqp://unused", poll_interval_seconds=5.0),
        connect=broker.connect,
    )
    adapter = _RecordingVideoAdapter()
    reports: list[Any] = []

    with client_guard(client):
        outcome = execute(_video_job(), adapter, _context(FakeStorage(), client, reports))

    assert outcome.data["providerTaskId"] == "pred-1"
    assert adapter.cancelled == []
    assert any(f.name == "clip.mp4" for f in outcome.files)


def test_unconfirmed_cancel_logs_task_id_and_does_not_resubmit(caplog: Any) -> None:
    calls = 0

    def responder(req: MediaControlRequest, props: Any, ch: _ScriptedRpcChannel) -> None:
        nonlocal calls
        calls += 1
        decision = "continue" if calls == 1 else "cancel_sibling_failed"
        ch.enqueue_reply(_reply_props(props.correlation_id), _reply_bytes(req, decision))

    broker = _ScriptedRpcBroker(responder)
    client = ControlClient(
        ControlConfig(amqp_url="amqp://unused", poll_interval_seconds=0.02),
        connect=broker.connect,
    )

    def long_sleep_await(task_id: str, abort: Any) -> VideoArtifact:
        abort.wait(5.0)
        abort.check()
        return VideoArtifact(MP4_BYTES, task_id)

    adapter = _RecordingVideoAdapter(
        on_await=long_sleep_await,
        on_cancel=lambda task_id: CancelResult(confirmed=False, reason="HTTP 502 from Replicate"),
    )
    reports: list[Any] = []

    with client_guard(client), pytest.raises(PermanentError) as exc_info:
        execute(_video_job(), adapter, _context(FakeStorage(), client, reports))

    assert exc_info.value.code == "sibling_failed"
    assert "cancel unconfirmed" in exc_info.value.message
    assert "pred-1" in exc_info.value.message
    assert "pred-1" in caplog.text
    assert adapter.submitted == 1


def test_lost_control_connection_blocks_submit_until_connected_and_never_cancels() -> None:
    def responder(req: MediaControlRequest, props: Any, ch: _ScriptedRpcChannel) -> None:
        ch.enqueue_reply(_reply_props(props.correlation_id), _reply_bytes(req, "continue"))

    broker = _ScriptedRpcBroker(responder)
    broker.fail_connect_times = 2
    client = ControlClient(
        ControlConfig(
            amqp_url="amqp://unused",
            reconnect_backoff_seconds=0.01,
            poll_interval_seconds=5.0,
        ),
        connect=broker.connect,
    )
    adapter = _RecordingVideoAdapter()
    reports: list[Any] = []

    with client_guard(client):
        outcome = execute(_video_job(), adapter, _context(FakeStorage(), client, reports))

    assert broker.connect_attempts == 3
    assert adapter.submitted == 1
    assert adapter.cancelled == []
    assert outcome.data["providerTaskId"] == "pred-1"


def test_lost_control_connection_while_running_keeps_reconnecting_without_cancelling() -> None:
    calls = 0

    def responder(req: MediaControlRequest, props: Any, ch: _ScriptedRpcChannel) -> None:
        nonlocal calls
        calls += 1
        if calls == 2:
            raise pika.exceptions.StreamLostError("connection dropped")
        ch.enqueue_reply(_reply_props(props.correlation_id), _reply_bytes(req, "continue"))

    broker = _ScriptedRpcBroker(responder)
    client = ControlClient(
        ControlConfig(
            amqp_url="amqp://unused",
            poll_interval_seconds=0.02,
            reconnect_backoff_seconds=0.01,
        ),
        connect=broker.connect,
    )

    def wait_two_polls(task_id: str, abort: Any) -> VideoArtifact:
        deadline = time.monotonic() + 2.0
        while calls < 3 and time.monotonic() < deadline:
            abort.wait(0.02)
        return VideoArtifact(MP4_BYTES, task_id)

    adapter = _RecordingVideoAdapter(on_await=wait_two_polls)
    reports: list[Any] = []

    with client_guard(client):
        outcome = execute(_video_job(), adapter, _context(FakeStorage(), client, reports))

    assert calls >= 3
    assert adapter.cancelled == []
    assert outcome.data["providerTaskId"] == "pred-1"


def test_stale_control_reply_raises_stale_delivery_and_ignores_bad_replies() -> None:
    def responder(req: MediaControlRequest, props: Any, ch: _ScriptedRpcChannel) -> None:
        # 1) wrong correlation id -> ignored
        ch.enqueue_reply(_reply_props("wrong-corr"), _reply_bytes(req, "cancel_sibling_failed"))
        # 2) malformed JSON -> ignored
        ch.enqueue_reply(_reply_props(props.correlation_id), b"{not json")
        # 3) wrong attempt -> ignored
        ch.enqueue_reply(
            _reply_props(props.correlation_id),
            _reply_bytes(req, "cancel_sibling_failed", attempt=req.attempt + 1),
        )
        # 4) valid discard_stale matching this attempt
        ch.enqueue_reply(_reply_props(props.correlation_id), _reply_bytes(req, "discard_stale"))

    broker = _ScriptedRpcBroker(responder)
    client = ControlClient(ControlConfig(amqp_url="amqp://unused"), connect=broker.connect)
    adapter = _RecordingVideoAdapter()
    reports: list[Any] = []

    with client_guard(client), pytest.raises(StaleDelivery):
        execute(_video_job(), adapter, _context(FakeStorage(), client, reports))

    assert adapter.submitted == 0


def test_discard_stale_through_consumer_acks_without_result() -> None:
    def responder(req: MediaControlRequest, props: Any, ch: _ScriptedRpcChannel) -> None:
        ch.enqueue_reply(_reply_props(props.correlation_id), _reply_bytes(req, "discard_stale"))

    broker = _ScriptedRpcBroker(responder)
    client = ControlClient(ControlConfig(amqp_url="amqp://unused"), connect=broker.connect)
    conn = FakeConnection()
    job_dict = json.loads(_video_job().root.model_dump_json())
    conn.deliver(job_dict)

    def parse_job(body: bytes) -> Any:
        return ProviderJob.model_validate_json(body).root

    def execute_job(job: Any, runner: Any, report: Any) -> Any:
        ctx = ExecutionContext(
            storage=FakeStorage(),
            bucket="flowcortex-media",
            output_prefix=job.outputPrefix,
            abort=runner,
            report=report,
            control=client,
        )
        return execute(ProviderJob(root=job), FixtureProviderAdapter(), ctx)

    with client_guard(client):
        run_consumer(
            conn,
            storage=FakeStorage(),
            queue="provider.video.jobs",
            exchange="provider",
            parse_job=parse_job,
            execute_job=execute_job,
            until=settled,
        )

    assert published(conn, "result") == []
    assert conn.consume.acks == [1]


def test_real_rabbitmq_control_rpc_roundtrip(rabbitmq: pika.ConnectionParameters) -> None:
    admin_conn = pika.BlockingConnection(rabbitmq)
    admin_ch = admin_conn.channel()
    admin_ch.exchange_declare(CONTROL_EXCHANGE, exchange_type="direct", durable=True)
    admin_ch.queue_declare(CONTROL_EXCHANGE + ".control", durable=True)
    admin_ch.queue_bind(
        CONTROL_EXCHANGE + ".control", CONTROL_EXCHANGE, routing_key=CONTROL_ROUTING_KEY
    )

    received: list[MediaControlRequest] = []
    stop_server = threading.Event()

    def serve() -> None:
        srv_conn = pika.BlockingConnection(rabbitmq)
        srv_ch = srv_conn.channel()
        try:
            while not stop_server.is_set():
                method, props, body = srv_ch.basic_get("provider.control", auto_ack=True)
                if method is None:
                    time.sleep(0.05)
                    continue
                req = MediaControlRequest.model_validate_json(body)
                received.append(req)
                assert WORKER_PERM_REPLY_RE.fullmatch(props.reply_to)
                resp = MediaControlResponse(
                    schemaVersion="1",
                    runId=req.runId,
                    jobId=req.jobId,
                    attempt=req.attempt,
                    decision="continue",
                )
                srv_ch.basic_publish(
                    exchange="",
                    routing_key=props.reply_to,
                    body=resp.model_dump_json().encode("utf-8"),
                    properties=pika.BasicProperties(
                        correlation_id=props.correlation_id,
                        content_type="application/json",
                    ),
                )
        finally:
            srv_conn.close()

    server_thread = threading.Thread(target=serve, daemon=True)
    server_thread.start()
    creds = rabbitmq.credentials
    amqp_url = (
        f"amqp://{creds.username}:{creds.password}@{rabbitmq.host}:{rabbitmq.port}/"
        f"{rabbitmq.virtual_host.lstrip('/')}"
    )
    client = ControlClient(
        ControlConfig(amqp_url=amqp_url, worker_id="video worker@1", request_timeout_seconds=5.0)
    )
    try:
        client.start()
        decision = client.check_once(RUN_ID, JOB_ID, 2)
        assert decision == "continue"
        assert len(received) == 1
        assert received[0].attempt == 2
    finally:
        stop_server.set()
        client.close()
        server_thread.join(timeout=3.0)
        admin_conn.close()


def test_cancel_before_submit_cancels_locally_recorded_task() -> None:
    def responder(req: MediaControlRequest, props: Any, ch: _ScriptedRpcChannel) -> None:
        ch.enqueue_reply(
            _reply_props(props.correlation_id), _reply_bytes(req, "cancel_sibling_failed")
        )

    broker = _ScriptedRpcBroker(responder)
    client = ControlClient(ControlConfig(amqp_url="amqp://unused"), connect=broker.connect)
    adapter = _RecordingVideoAdapter()
    reports: list[Any] = []
    storage = _InMemoryStorage()
    job = _video_job()
    key = _video_idempotency_key(
        VideoRequest("Product clip", UUID(REVISION_ID)), adapter.video_identity()
    )
    ctx = _context(storage, client, reports)
    _store_video_task(ctx, "pred-existing-42", key, adapter.video_identity())

    with client_guard(client), pytest.raises(PermanentError) as exc_info:
        execute(job, adapter, ctx)

    assert exc_info.value.code == "sibling_failed"
    assert adapter.submitted == 0
    assert adapter.cancelled == ["pred-existing-42"]
    assert any(r.status == "failed" and r.vendorJobId == "pred-existing-42" for r in reports)


def test_closed_control_client_stops_initial_wait_and_closes_partial_connection() -> None:
    def responder(req: MediaControlRequest, props: Any, ch: _ScriptedRpcChannel) -> None:
        ch.enqueue_reply(_reply_props(props.correlation_id), _reply_bytes(req, "continue"))

    broker = _ScriptedRpcBroker(responder)
    client = ControlClient(
        ControlConfig(amqp_url="amqp://unused", reconnect_backoff_seconds=0.01),
        connect=broker.connect,
    )
    client.close()
    session = client.open_session(RUN_ID, JOB_ID, 1, abort=_Abort())

    with pytest.raises(ControlClientClosed):
        session.await_initial()
    with pytest.raises(ControlClientClosed):
        client.start()

    created_conns: list[_ScriptedRpcConnection] = []

    def failing_channel_connect(amqp_url: str) -> _ScriptedRpcConnection:
        conn = broker.connect(amqp_url)
        created_conns.append(conn)

        def fail_channel() -> Any:
            raise pika.exceptions.AMQPChannelError("channel open failed")

        conn.channel = fail_channel  # type: ignore[method-assign]
        return conn

    broken_client = ControlClient(
        ControlConfig(amqp_url="amqp://unused", request_timeout_seconds=0.5),
        connect=failing_channel_connect,
    )
    try:
        with pytest.raises(pika.exceptions.AMQPChannelError):
            broken_client.check_once(RUN_ID, JOB_ID, 1)
        assert len(created_conns) == 1
        assert created_conns[0].closed is True
    finally:
        broken_client.close()


class client_guard:
    def __init__(self, client: ControlClient) -> None:
        self.client = client

    def __enter__(self) -> ControlClient:
        self.client.start()
        return self.client

    def __exit__(self, *args: Any) -> None:
        self.client.close()
