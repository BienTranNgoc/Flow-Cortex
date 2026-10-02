"""Pins the RabbitMQ 4.3 quorum-queue behaviour the worker's retry and timeout design rests on.

An upgrade that changes any of these fails here first.
"""

from __future__ import annotations

import time
import uuid
from collections.abc import Iterator
from typing import Any

import pika
import pytest

DELIVERY_LIMIT = 3


@pytest.fixture
def jobs_queue(rabbitmq: pika.ConnectionParameters) -> Iterator[tuple[Any, str, str]]:
    """A fresh quorum queue with the jobs queue's arguments and its own dead-letter queue."""
    conn = pika.BlockingConnection(rabbitmq)
    channel = conn.channel()
    name = f"semantics.{uuid.uuid4()}"
    dlx = f"{name}.dlx"
    channel.exchange_declare(dlx, "direct", auto_delete=True)
    channel.queue_declare(f"{name}.dlq", durable=True, arguments={"x-queue-type": "quorum"})
    channel.queue_bind(f"{name}.dlq", dlx, routing_key=name)
    channel.queue_declare(
        name,
        durable=True,
        arguments={
            "x-queue-type": "quorum",
            "x-delivery-limit": DELIVERY_LIMIT,
            "x-dead-letter-exchange": dlx,
        },
    )
    channel.basic_publish("", name, b"job", pika.BasicProperties(delivery_mode=2))
    yield channel, name, f"{name}.dlq"
    channel.queue_delete(name)
    channel.queue_delete(f"{name}.dlq")
    conn.close()


def get(channel: Any, queue: str) -> tuple[Any, Any] | None:
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        method, props, _ = channel.basic_get(queue, auto_ack=False)
        if method is not None:
            return method, props
        time.sleep(0.05)
    return None


def delivery_count(props: Any) -> int | None:
    headers = props.headers or {}
    value = headers.get("x-delivery-count")
    return None if value is None else int(value)


def test_reject_is_counted_and_the_limit_dead_letters_after_four_deliveries(
    jobs_queue: tuple[Any, str, str],
) -> None:
    channel, queue, dlq = jobs_queue
    seen: list[int | None] = []

    while (delivered := get(channel, queue)) is not None:
        method, props = delivered
        seen.append(delivery_count(props))
        channel.basic_reject(method.delivery_tag, requeue=True)

    assert seen == [None, 1, 2, 3]
    dead = get(channel, dlq)
    assert dead is not None
    death = dead[1].headers["x-death"][0]
    assert death["reason"] == "delivery_limit"


def test_closing_the_channel_with_the_message_unacked_is_counted(
    jobs_queue: tuple[Any, str, str], rabbitmq: pika.ConnectionParameters
) -> None:
    channel, queue, dlq = jobs_queue
    conn = pika.BlockingConnection(rabbitmq)
    seen: list[int | None] = []

    for _ in range(DELIVERY_LIMIT + 2):
        worker = conn.channel()
        delivered = get(worker, queue)
        if delivered is None:
            break
        seen.append(delivery_count(delivered[1]))
        worker.close()
    conn.close()

    assert seen == [None, 1, 2, 3]
    dead = get(channel, dlq)
    assert dead is not None
    assert dead[1].headers["x-death"][0]["reason"] == "delivery_limit"


def test_nack_is_not_counted_and_never_reaches_the_limit(
    jobs_queue: tuple[Any, str, str],
) -> None:
    channel, queue, dlq = jobs_queue
    seen: list[int | None] = []

    for _ in range(DELIVERY_LIMIT + 3):
        delivered = get(channel, queue)
        assert delivered is not None, f"message left the queue after {len(seen)} nacks"
        method, props = delivered
        seen.append(delivery_count(props))
        channel.basic_nack(method.delivery_tag, requeue=True)

    assert set(seen) == {None}
    assert get(channel, queue) is not None
    assert channel.queue_declare(dlq, passive=True).method.message_count == 0


def test_consumer_timeout_cancels_the_consumer_and_returns_the_message(
    rabbitmq: pika.ConnectionParameters,
) -> None:
    conn = pika.BlockingConnection(rabbitmq)
    channel = conn.channel()
    queue = f"semantics.timeout.{uuid.uuid4()}"
    channel.queue_declare(
        queue,
        durable=True,
        arguments={"x-queue-type": "quorum", "x-consumer-timeout": 5000},
    )
    channel.basic_publish("", queue, b"job", pika.BasicProperties(delivery_mode=2))
    cancelled: list[float] = []
    received: list[float] = []
    channel.add_on_cancel_callback(lambda frame: cancelled.append(time.monotonic()))
    channel.basic_qos(prefetch_count=1)
    channel.basic_consume(
        queue, lambda ch, method, props, body: received.append(time.monotonic()), auto_ack=False
    )

    deadline = time.monotonic() + 150
    while not cancelled and time.monotonic() < deadline and channel.is_open:
        conn.process_data_events(time_limit=1)

    assert received, "the message was never delivered"
    assert channel.is_open, "the broker closed the channel instead of cancelling the consumer"
    assert cancelled, "the consumer timeout never cancelled the consumer"
    assert 4 <= cancelled[0] - received[0] <= 15

    # Deleting the queue later sends a second Basic.Cancel for the same tag, which pika
    # cannot map and turns into a lost connection; the worker reconnects after a cancel anyway.
    conn.close()
    conn = pika.BlockingConnection(rabbitmq)
    other = conn.channel()
    returned = get(other, queue)
    assert returned is not None, "the timed-out message did not return to the queue"
    # The timeout return is not counted against the delivery limit; only the acquisition is.
    assert delivery_count(returned[1]) is None
    assert returned[1].headers["x-acquired-count"] == 1
    other.queue_delete(queue)
    conn.close()
