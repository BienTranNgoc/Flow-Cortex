"""Shared fixtures: a fake pika connection, a fake object store, the pinned containers."""

from __future__ import annotations

import json
import queue
import subprocess
import threading
import time
import uuid
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import boto3
import jsonschema
import pika
import pika.exceptions
import pytest
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

RABBITMQ_IMAGE = "rabbitmq:4.3.6-management"
SILO_IMAGE = (
    "pgsty/silo:RELEASE.2026-09-16T00-00-00Z"
    "@sha256:635197cb9f36d01bee221d34d1c7d7960f6a95c48b0b6c01d99cd13bdae51a46"
)
UPLOAD_BUCKET = "flowcortex-uploads"
MEDIA_BUCKET = "flowcortex-media"
CONTRACTS = Path(__file__).resolve().parents[2] / "contracts"

SPANS = InMemorySpanExporter()


@pytest.fixture(scope="session", autouse=True)
def _telemetry() -> None:
    # Global providers can be set once per process, so the whole session shares one.
    from flowcortex_worker import telemetry

    tel = telemetry.build(
        {
            "OTEL_TRACES_EXPORTER": "none",
            "OTEL_LOGS_EXPORTER": "none",
            "OTEL_METRICS_EXPORTER": "none",
        }
    )
    # The same redaction wrapper the OTLP exporter gets, so tests see what would be exported.
    # The same redaction wrapper the OTLP exporter gets, so tests see what would be exported.
    tel.tracer_provider.add_span_processor(SimpleSpanProcessor(telemetry.Redacting(SPANS)))
    tel.install()


@pytest.fixture
def spans() -> InMemorySpanExporter:
    SPANS.clear()
    return SPANS


def assert_valid(body: bytes, schema: str) -> dict[str, Any]:
    """Validates a published body against contracts/<schema>.schema.json."""
    message: dict[str, Any] = json.loads(body)
    document = json.loads((CONTRACTS / f"{schema}.schema.json").read_text())
    jsonschema.Draft202012Validator(
        document, format_checker=jsonschema.Draft202012Validator.FORMAT_CHECKER
    ).validate(message)
    return message


def job_body(**change: Any) -> dict[str, Any]:
    run_id = change.get("runId", str(uuid.uuid4()))
    step = change.get("stepOrder", 1)
    body: dict[str, Any] = {
        "schemaVersion": "1",
        "jobId": str(uuid.uuid4()),
        "runId": run_id,
        "correlationId": run_id,
        "stepOrder": step,
        "capability": "probe",
        "inputs": [{"key": "media", "bucket": UPLOAD_BUCKET, "objectKey": "u-1/up-1"}],
        "params": {},
        "outputPrefix": f"runs/{run_id}/steps/{step}/",
        "attempt": 1,
        "maxRuntimeSeconds": 300,
        "maxInputBytes": 524_288_000,
        "issuedAt": datetime.now(UTC).isoformat(),
    }
    return body | change


# --- fake pika ------------------------------------------------------------------


@dataclass
class Published:
    exchange: str
    routing_key: str
    body: bytes
    properties: pika.BasicProperties
    mandatory: bool
    thread: threading.Thread


@dataclass
class FakeMethod:
    delivery_tag: int
    redelivered: bool = False


class FakeChannel:
    def __init__(self, connection: FakeConnection) -> None:
        self.connection = connection
        self.is_open = True
        self.prefetch: int | None = None
        self.confirming = False
        self.consuming: tuple[str, Callable[..., None]] | None = None
        self.cancel_callbacks: list[Callable[[Any], None]] = []
        self.cancelled = False
        self.published: list[Published] = []
        self.acks: list[int] = []
        self.rejects: list[tuple[int, bool]] = []
        self.fail_publish_routing_keys: set[str] = set()

    def _check(self) -> None:
        self.connection.check_thread()
        if not self.is_open:
            raise pika.exceptions.ChannelWrongStateError("closed")

    def basic_qos(self, prefetch_count: int) -> None:
        self.prefetch = prefetch_count

    def confirm_delivery(self) -> None:
        self.confirming = True

    def add_on_cancel_callback(self, callback: Callable[[Any], None]) -> None:
        self.cancel_callbacks.append(callback)

    def basic_consume(
        self, queue: str, on_message_callback: Callable[..., None], auto_ack: bool = False
    ) -> str:
        assert auto_ack is False
        if self.connection.consume_error is not None:
            raise self.connection.consume_error
        self.consuming = (queue, on_message_callback)
        return "ctag-1"

    def basic_cancel(self, consumer_tag: str) -> list[Any]:
        self._check()
        self.cancelled = True
        return []

    def basic_publish(
        self,
        exchange: str,
        routing_key: str,
        body: bytes,
        properties: pika.BasicProperties,
        mandatory: bool = False,
    ) -> None:
        self._check()
        if routing_key in self.fail_publish_routing_keys:
            raise pika.exceptions.UnroutableError([])
        self.published.append(
            Published(
                exchange, routing_key, body, properties, mandatory, threading.current_thread()
            )
        )

    def basic_ack(self, delivery_tag: int) -> None:
        self._check()
        self.acks.append(delivery_tag)

    def basic_reject(self, delivery_tag: int, requeue: bool = True) -> None:
        self._check()
        self.rejects.append((delivery_tag, requeue))


class FakeConnection:
    """Runs threadsafe callbacks on the thread that calls process_data_events, like pika."""

    def __init__(self) -> None:
        self.owner = threading.current_thread()
        self.channels: list[FakeChannel] = []
        self.callbacks: queue.Queue[Callable[[], None]] = queue.Queue()
        self.deliveries: list[tuple[FakeMethod, pika.BasicProperties, bytes]] = []
        self.hooks: list[Callable[[FakeConnection], None]] = []
        self.thread_violations: list[str] = []
        self.consume_error: Exception | None = None
        self.is_open = True
        self.closed = False

    def check_thread(self) -> None:
        if threading.current_thread() is not self.owner:
            self.thread_violations.append(threading.current_thread().name)

    def channel(self) -> FakeChannel:
        ch = FakeChannel(self)
        self.channels.append(ch)
        return ch

    @property
    def consume(self) -> FakeChannel:
        return self.channels[0]

    @property
    def publish(self) -> FakeChannel:
        return self.channels[1]

    def deliver(self, body: dict[str, Any] | bytes, **props: Any) -> None:
        redelivered = bool(props.pop("redelivered", False))
        raw = body if isinstance(body, bytes) else json.dumps(body).encode()
        tag = len(self.deliveries) + 1
        self.deliveries.append((FakeMethod(tag, redelivered), pika.BasicProperties(**props), raw))

    def add_callback_threadsafe(self, callback: Callable[[], None]) -> None:
        if self.closed:
            raise pika.exceptions.ConnectionWrongStateError("closed")
        self.callbacks.put(callback)

    def process_data_events(self, time_limit: float = 0) -> None:
        self.owner = threading.current_thread()
        for hook in list(self.hooks):
            hook(self)
        if self.consume.consuming and self.deliveries and not self.consume.cancelled:
            method, props, body = self.deliveries.pop(0)
            self.consume.consuming[1](self.consume, method, props, body)
        deadline = time.monotonic() + min(time_limit, 0.05)
        while True:
            try:
                callback = self.callbacks.get(timeout=max(0.0, deadline - time.monotonic()))
            except queue.Empty:
                return
            callback()

    def cancel_consumer(self) -> None:
        for callback in self.consume.cancel_callbacks:
            callback(None)

    def close(self) -> None:
        self.closed = True
        self.is_open = False


def published(conn: FakeConnection, routing_key: str) -> list[Published]:
    return [p for p in conn.publish.published if p.routing_key == routing_key]


# --- fake object store ------------------------------------------------------------


@dataclass
class FakeStorage:
    bucket: str = MEDIA_BUCKET
    content: bytes = b"media"
    error: Exception | None = None
    stall: bool = False
    fetched_paths: list[Path] = field(default_factory=list)
    puts: dict[str, tuple[bytes, str]] = field(default_factory=dict)

    @contextmanager
    def fetched(self, ref: Any, max_bytes: int, abort: Any) -> Iterator[Path]:
        if self.error is not None:
            raise self.error
        # A transfer that never finishes on its own: only a kill of the job ends it.
        while self.stall and abort.killed is None:
            time.sleep(0.01)
        abort.check()
        path = Path(f"/tmp/fake-input-{uuid.uuid4()}")
        path.write_bytes(self.content)
        self.fetched_paths.append(path)
        try:
            yield path
        finally:
            path.unlink()

    def put(self, key: str, body: bytes, mime: str, abort: Any) -> None:
        abort.check()
        self.puts[key] = (body, mime)


def wait_for(predicate: Callable[[], bool], timeout: float = 5.0) -> None:
    deadline = time.monotonic() + timeout
    while not predicate():
        if time.monotonic() > deadline:
            raise AssertionError("condition not reached in time")
        time.sleep(0.01)


@contextmanager
def captured_logs() -> Iterator[list[dict[str, Any]]]:
    """Collects every record, formatted by the worker's JSON formatter, as parsed dicts."""
    import io
    import logging

    from flowcortex_worker.telemetry import JsonFormatter

    stream = io.StringIO()
    handler = logging.StreamHandler(stream)
    handler.setFormatter(JsonFormatter())
    root = logging.getLogger()
    previous = root.level
    root.addHandler(handler)
    root.setLevel(logging.INFO)
    lines: list[dict[str, Any]] = []
    try:
        yield lines
    finally:
        root.removeHandler(handler)
        root.setLevel(previous)
        lines.extend(json.loads(line) for line in stream.getvalue().splitlines())


# --- running a consumer against the fakes -----------------------------------------


def run_consumer(
    conn: FakeConnection,
    *,
    storage: Any = None,
    resolve: Any = None,
    until: Callable[[FakeConnection], bool],
    timeout: float = 15,
    **kwargs: Any,
) -> Any:
    """Runs a Consumer on the calling thread until `until(conn)` holds, then stops it."""
    from concurrent.futures import ThreadPoolExecutor

    from flowcortex_worker.consumer import Consumer

    stop = threading.Event()
    deadline = time.monotonic() + timeout

    def stop_when_done(c: FakeConnection) -> None:
        if until(c):
            stop.set()
        elif time.monotonic() > deadline:
            stop.set()
            raise AssertionError("consumer did not reach the expected state in time")

    conn.hooks.append(stop_when_done)
    executor = ThreadPoolExecutor(max_workers=1)
    params: dict[str, Any] = {
        "storage": storage or FakeStorage(),
        "worker_id": "worker-test",
        "heartbeat_seconds": 30.0,
        "stop": stop,
        "executor": executor,
        "liveness_file": Path(f"/tmp/fake-alive-{uuid.uuid4()}"),
        "grace_seconds": 5.0,
    }
    if resolve is not None:
        params["resolve"] = resolve
    params |= kwargs
    consumer = Consumer(conn, **params)
    try:
        return consumer.run()
    finally:
        executor.shutdown(wait=True)


def settled(conn: FakeConnection) -> bool:
    return bool(conn.channels and (conn.consume.acks or conn.consume.rejects))


# --- containers -------------------------------------------------------------------


@pytest.fixture(scope="session")
def rabbitmq() -> Iterator[pika.ConnectionParameters]:
    from testcontainers.community.rabbitmq import RabbitMqContainer

    with RabbitMqContainer(RABBITMQ_IMAGE) as container:
        yield container.get_connection_params()


@dataclass(frozen=True)
class Silo:
    endpoint: str
    access_key: str
    secret_key: str

    def client(self) -> Any:
        return boto3.client(
            "s3",
            endpoint_url=self.endpoint,
            region_name="us-east-1",
            aws_access_key_id=self.access_key,
            aws_secret_access_key=self.secret_key,
            config=boto3.session.Config(s3={"addressing_style": "path"}),
        )


@pytest.fixture(scope="session")
def silo() -> Iterator[Silo]:
    from testcontainers.community.minio import MinioContainer

    container = MinioContainer(image=SILO_IMAGE)
    container.with_env("MINIO_ROOT_USER", container.access_key)
    container.with_env("MINIO_ROOT_PASSWORD", container.secret_key)
    with container:
        config = container.get_config()
        store = Silo(f"http://{config['endpoint']}", config["access_key"], config["secret_key"])
        client = store.client()
        for bucket in (UPLOAD_BUCKET, MEDIA_BUCKET):
            client.create_bucket(Bucket=bucket)
        yield store


def declare_topology(channel: Any, consumer_timeout_ms: int = 1_200_000) -> None:
    """Mirrors the topology the API declares; the worker itself declares nothing."""
    channel.exchange_declare("media.unrouted", "fanout", durable=True)
    channel.queue_declare("media.unrouted", durable=True, arguments={"x-queue-type": "quorum"})
    channel.queue_bind("media.unrouted", "media.unrouted")
    channel.exchange_declare(
        "media", "direct", durable=True, arguments={"alternate-exchange": "media.unrouted"}
    )
    channel.exchange_declare("media.dlx", "direct", durable=True)
    dead_letter = {"x-queue-type": "quorum", "x-dead-letter-exchange": "media.dlx"}
    queues = {
        "media.jobs": (
            "job",
            dead_letter | {"x-delivery-limit": 3, "x-consumer-timeout": consumer_timeout_ms},
        ),
        "media.results": ("result", dead_letter),
        "media.progress": ("progress", dead_letter),
    }
    for name, (key, arguments) in queues.items():
        channel.queue_declare(name, durable=True, arguments=arguments)
        channel.queue_bind(name, "media", routing_key=key)
        channel.queue_declare(f"{name}.dlq", durable=True, arguments={"x-queue-type": "quorum"})
        channel.queue_bind(f"{name}.dlq", "media.dlx", routing_key=key)


def make_clip(path: Path) -> Path:
    subprocess.run(
        [
            "ffmpeg", "-v", "error", "-y",
            "-f", "lavfi", "-i", "testsrc=duration=1:size=320x240:rate=25",
            "-f", "lavfi", "-i", "sine=duration=1",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest",
            str(path),
        ],
        check=True,
    )  # fmt: skip
    return path
