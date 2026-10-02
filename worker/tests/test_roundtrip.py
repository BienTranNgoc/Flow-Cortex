"""A job through the real broker and store: pinned RabbitMQ and Silo, no internet."""

from __future__ import annotations

import json
import threading
import time
import uuid
from collections.abc import Iterator
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any

import pika
import pytest
from conftest import (
    MEDIA_BUCKET,
    UPLOAD_BUCKET,
    Silo,
    assert_valid,
    declare_topology,
    job_body,
    make_clip,
)

from flowcortex_worker.consumer import Consumer
from flowcortex_worker.errors import TransientError
from flowcortex_worker.runtime import Supervisor
from flowcortex_worker.storage import Storage

FAIL_ONCE_AFTER_PUT: set[str] = set()


class FailOnceAfterPut(Storage):
    """Stores the output, then fails once for chosen runs, so the broker redelivers the job."""

    def put(self, key: str, body: bytes, mime: str, abort: Any) -> None:
        super().put(key, body, mime, abort)
        run_id = key.split("/")[1]
        if run_id in FAIL_ONCE_AFTER_PUT:
            FAIL_ONCE_AFTER_PUT.discard(run_id)
            raise TransientError("store hiccup after the output was written")


@pytest.fixture(scope="module")
def worker(rabbitmq: pika.ConnectionParameters, silo: Silo) -> Iterator[None]:
    setup = pika.BlockingConnection(rabbitmq)
    declare_topology(setup.channel())
    setup.close()

    stop = threading.Event()
    executor = ThreadPoolExecutor(max_workers=1)

    def make_consumer(conn: Any) -> Consumer:
        return Consumer(
            conn,
            storage=FailOnceAfterPut(silo.client(), MEDIA_BUCKET, UPLOAD_BUCKET),
            worker_id="worker-roundtrip",
            heartbeat_seconds=0.02,
            stop=stop,
            executor=executor,
            liveness_file=Path(f"/tmp/fake-alive-{uuid.uuid4()}"),
            grace_seconds=5.0,
            # The backoff length is covered by the unit tests; here only the redelivery matters.
            wait=lambda seconds: stop.wait(0.1),
        )

    supervisor = Supervisor(
        connect=lambda: pika.BlockingConnection(rabbitmq),
        make_consumer=make_consumer,
        stop=stop,
        wait=stop.wait,
    )
    thread = threading.Thread(target=supervisor.run, name="worker-main")
    thread.start()
    yield
    stop.set()
    thread.join(timeout=30)
    executor.shutdown(wait=True)
    assert not thread.is_alive()


def publish_job(params: pika.ConnectionParameters, body: dict[str, Any]) -> None:
    conn = pika.BlockingConnection(params)
    channel = conn.channel()
    channel.confirm_delivery()
    channel.basic_publish(
        "media",
        "job",
        json.dumps(body).encode(),
        pika.BasicProperties(
            content_type="application/json",
            delivery_mode=2,
            message_id=body["jobId"],
            correlation_id=body["runId"],
        ),
        mandatory=True,
    )
    conn.close()


def collect(
    params: pika.ConnectionParameters,
    queue: str,
    job_id: str,
    count: int,
    timeout: float = 60,
    until_kind: str | None = None,
) -> list[tuple[pika.BasicProperties, dict[str, Any]]]:
    """Reads `count` messages for one job; tests run one at a time, so others are leftovers."""
    conn = pika.BlockingConnection(params)
    channel = conn.channel()
    found: list[tuple[pika.BasicProperties, dict[str, Any]]] = []
    deadline = time.monotonic() + timeout

    def enough() -> bool:
        if until_kind is None:
            return len(found) >= count
        return sum(1 for _, message in found if message.get("kind") == until_kind) >= count

    while not enough() and time.monotonic() < deadline:
        method, props, body = channel.basic_get(queue, auto_ack=True)
        if method is None:
            time.sleep(0.1)
            continue
        message = json.loads(body)
        if message.get("jobId") == job_id:
            found.append((props, message))
    conn.close()
    assert enough(), f"only {len(found)} messages for {job_id} on {queue}, wanted {count}"
    return found


def upload_clip(silo: Silo, tmp_path: Path) -> str:
    key = f"u-1/{uuid.uuid4()}"
    silo.client().upload_file(str(make_clip(tmp_path / "clip.mp4")), UPLOAD_BUCKET, key)
    return key


def media_job(key: str) -> dict[str, Any]:
    return job_body(inputs=[{"key": "media", "bucket": UPLOAD_BUCKET, "objectKey": key}])


def test_uploaded_object_is_probed_and_reported(
    worker: None, rabbitmq: pika.ConnectionParameters, silo: Silo, tmp_path: Path
) -> None:
    body = media_job(upload_clip(silo, tmp_path))
    publish_job(rabbitmq, body)

    [(props, result)] = collect(rabbitmq, "media.results", body["jobId"], 1)
    assert_valid(json.dumps(result).encode(), "media-result")
    assert result["status"] == "completed"
    assert result["data"]["videoCodec"] == "h264"
    assert props.correlation_id == body["runId"]
    key = f"runs/{body['runId']}/steps/1/video-details.json"
    assert [o["objectKey"] for o in result["outputs"]] == [key]
    stored = silo.client().get_object(Bucket=MEDIA_BUCKET, Key=key)
    assert json.loads(stored["Body"].read())["format"]["format_name"].startswith("mov,mp4")

    progress = collect(rabbitmq, "media.progress", body["jobId"], 2)
    kinds = [message["kind"] for _, message in progress]
    assert kinds[0] == "started"
    assert "heartbeat" in kinds
    for _, message in progress:
        assert_valid(json.dumps(message).encode(), "media-progress")


def test_a_redelivered_job_converges_on_the_same_keys(
    worker: None, rabbitmq: pika.ConnectionParameters, silo: Silo, tmp_path: Path
) -> None:
    body = media_job(upload_clip(silo, tmp_path))
    FAIL_ONCE_AFTER_PUT.add(body["runId"])
    publish_job(rabbitmq, body)

    [(_, result)] = collect(rabbitmq, "media.results", body["jobId"], 1)

    assert result["status"] == "completed"
    assert result["deliveryCount"] == 1
    progress = collect(rabbitmq, "media.progress", body["jobId"], 2, until_kind="started")
    assert [m["deliveryCount"] for _, m in progress if m["kind"] == "started"] == [0, 1]
    listed = silo.client().list_objects_v2(Bucket=MEDIA_BUCKET, Prefix=f"runs/{body['runId']}/")
    assert [o["Key"] for o in listed["Contents"]] == [
        f"runs/{body['runId']}/steps/1/video-details.json"
    ]


def test_expired_upload_is_rejected(
    worker: None, rabbitmq: pika.ConnectionParameters, silo: Silo
) -> None:
    body = media_job(f"u-1/{uuid.uuid4()}")
    publish_job(rabbitmq, body)

    [(_, result)] = collect(rabbitmq, "media.results", body["jobId"], 1)
    assert result["error"]["code"] == "input_rejected"
    assert result["error"]["retryable"] is False
