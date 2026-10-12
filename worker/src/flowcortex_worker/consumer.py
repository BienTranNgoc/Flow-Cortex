"""Consumes media.jobs on one pika connection owned by the calling thread.

pika is not thread-safe: the job thread and the heartbeat thread reach the connection only
through `add_callback_threadsafe`, and every ack, reject and publish runs on the owner thread.
"""

from __future__ import annotations

import contextvars
import json
import logging
import threading
import time
from collections.abc import Callable
from concurrent.futures import Executor, Future
from contextlib import AbstractContextManager, ExitStack
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Literal, Protocol, TypeVar
from uuid import UUID

import pika
import pika.exceptions
from opentelemetry import context as otel_context
from opentelemetry import metrics, propagate, trace
from opentelemetry.trace import Span, SpanKind, Status, StatusCode
from pydantic import BaseModel, ValidationError

from . import telemetry
from .contracts.media_job import MediaJob, MediaJobInput
from .contracts.media_progress import MediaProgress
from .contracts.media_result import CompletedResult, ErrorResult, MediaError, MediaOutput
from .errors import ErrorCode, PermanentError, TransientError, backoff_seconds
from .handlers import Handler
from .handlers.registry import resolve as resolve_capability
from .runtime import ChannelLost, ProcessKilled, ProcessRunner
from .storage import output_key

JOBS_QUEUE = "media.jobs"
EXCHANGE = "media"
RESULT_KEY = "result"
PROGRESS_KEY = "progress"
CONNECTION_CLOSED = "connection closed"

log = logging.getLogger(__name__)
tracer = trace.get_tracer(__name__)
meter = metrics.get_meter(__name__)
jobs_finished = meter.create_counter("media.worker.jobs", description="Jobs by final outcome")
job_duration = meter.create_histogram("media.worker.job.duration", unit="s")

T = TypeVar("T")
Settle = Literal["ack", "requeue", "dead-letter"]
Result = CompletedResult | ErrorResult


class ObjectStore(Protocol):
    bucket: str

    def fetched(
        self, ref: MediaJobInput, max_bytes: int, abort: ProcessRunner
    ) -> AbstractContextManager[Path]: ...

    def put(self, key: str, body: bytes | Path, mime: str, abort: ProcessRunner) -> int: ...


class Abandoned(Exception):
    """The delivery can no longer be settled here; the broker redelivers it."""


@dataclass
class Delivery:
    channel: Any
    tag: int
    props: pika.BasicProperties
    body: bytes
    count: int
    runner: ProcessRunner = field(default_factory=ProcessRunner)
    settled: bool = False


def delivery_count(props: pika.BasicProperties, redelivered: bool) -> int:
    """Earlier deliveries of this message; the broker omits the header on the first one."""
    value = (props.headers or {}).get("x-delivery-count")
    if value is not None:
        return int(value)
    return 1 if redelivered else 0


class Consumer:
    def __init__(
        self,
        connection: Any,
        *,
        storage: ObjectStore,
        worker_id: str,
        heartbeat_seconds: float,
        stop: threading.Event,
        executor: Executor,
        liveness_file: Path,
        grace_seconds: float = 30.0,
        resolve: Callable[[str], Handler] | None = None,
        queue: str = JOBS_QUEUE,
        exchange: str = EXCHANGE,
        parse_job: Callable[[bytes], Any] | None = None,
        execute_job: Callable[[Any, ProcessRunner], Any] | None = None,
        wait: Callable[[float], Any] | None = None,
    ) -> None:
        self._connection = connection
        self._storage = storage
        self._worker_id = worker_id
        self._heartbeat_seconds = heartbeat_seconds
        self._stop = stop
        self._executor = executor
        self._liveness_file = liveness_file
        self._grace_seconds = grace_seconds
        self._resolve = resolve or resolve_capability
        self._queue = queue
        self._exchange = exchange
        self._parse_job = parse_job or MediaJob.model_validate_json
        self._execute_job = execute_job
        self._wait = wait or self._backoff_wait
        self._closed = threading.Event()
        self._cancelled = False
        self._in_flight: Delivery | None = None
        self._consume_channel: Any = None
        self._publish_channel: Any = None
        self._consumer_tag = ""
        self.consuming = False

    # --- owner thread ---------------------------------------------------------

    def run(self) -> str:
        """Consumes until stopped ("stopped") or cancelled by the broker ("cancelled")."""
        try:
            self._consume_channel = self._connection.channel()
            self._consume_channel.basic_qos(prefetch_count=1)
            self._consume_channel.add_on_cancel_callback(self._on_cancel)
            self._publish_channel = self._connection.channel()
            self._publish_channel.confirm_delivery()
            self._consumer_tag = self._consume_channel.basic_consume(
                self._queue, self._on_message, auto_ack=False
            )
            self.consuming = True
            log.info("consuming", extra={"queue": self._queue, "workerId": self._worker_id})
            while True:
                if self._stop.is_set():
                    self._drain()
                    return "stopped"
                if self._cancelled:
                    return "cancelled"
                self._connection.process_data_events(time_limit=1)
                self._liveness_file.touch()
                if not (self._consume_channel.is_open and self._publish_channel.is_open):
                    raise ChannelLost("the broker closed a worker channel")
        finally:
            self._closed.set()
            if self._in_flight is not None:
                self._in_flight.runner.kill(CONNECTION_CLOSED)

    def _on_message(
        self, channel: Any, method: Any, props: pika.BasicProperties, body: bytes
    ) -> None:
        delivery = Delivery(
            channel, method.delivery_tag, props, body, delivery_count(props, method.redelivered)
        )
        self._in_flight = delivery
        self._executor.submit(self._process, delivery).add_done_callback(_log_crash)

    def _on_cancel(self, frame: Any) -> None:
        log.warning("the broker cancelled the consumer; reconnecting", extra={"queue": self._queue})
        self._cancelled = True
        if self._in_flight is not None:
            self._in_flight.runner.kill("consumer cancelled")

    def _drain(self) -> None:
        log.info("stopping: no new jobs", extra={"graceSeconds": self._grace_seconds})
        self._consume_channel.basic_cancel(self._consumer_tag)
        deadline = time.monotonic() + self._grace_seconds
        while self._in_flight is not None and time.monotonic() < deadline:
            self._connection.process_data_events(time_limit=0.2)
            self._liveness_file.touch()
        delivery = self._in_flight
        if delivery is not None:
            delivery.runner.kill("shutdown")
            self._settle_now(delivery, "requeue")
            log.warning("in-flight job returned to the queue at shutdown")

    def _settle_now(self, delivery: Delivery, action: Settle) -> None:
        if delivery.settled:
            raise Abandoned("already returned to the queue")
        if not delivery.channel.is_open:
            raise Abandoned("channel closed")
        if action == "ack":
            delivery.channel.basic_ack(delivery.tag)
        else:
            delivery.channel.basic_reject(delivery.tag, requeue=action == "requeue")
        delivery.settled = True
        if self._in_flight is delivery:
            self._in_flight = None

    # --- job thread -----------------------------------------------------------

    def _backoff_wait(self, seconds: float) -> None:
        # Ends early on shutdown (the job is returned at once) and on a lost connection
        # (the broker already has the message back).
        deadline = time.monotonic() + seconds
        while not (self._stop.is_set() or self._closed.is_set()):
            left = deadline - time.monotonic()
            if left <= 0:
                return
            self._closed.wait(min(left, 0.2))

    def _process(self, delivery: Delivery) -> None:
        parent = propagate.extract(delivery.props.headers or {})
        # Attached, not only passed as the parent, so the inbound baggage (runId) travels on.
        token = otel_context.attach(parent)
        try:
            self._process_in(delivery, parent)
        finally:
            otel_context.detach(token)

    def _process_in(self, delivery: Delivery, parent: otel_context.Context) -> None:
        with tracer.start_as_current_span(
            f"process {self._queue}",
            context=parent,
            kind=SpanKind.CONSUMER,
            attributes={
                "messaging.system": "rabbitmq",
                "messaging.operation.type": "process",
                "messaging.destination.name": self._queue,
            },
        ) as span:
            try:
                job = self._parse_job(delivery.body)
            except ValidationError as exc:
                self._refuse(delivery, span, exc)
                return
            span.set_attribute("messaging.message.id", str(job.jobId))
            ids = _ids(job.jobId, job.runId, job.attempt, delivery.count) | {
                "capability": job.capability
            }
            with telemetry.job_scope(ids):
                self._answer(
                    delivery, span, ids, job.runId, lambda s: self._execute(job, delivery, s), job
                )

    def _refuse(self, delivery: Delivery, span: Span, exc: ValidationError) -> None:
        reason = "; ".join(
            f"{'.'.join(str(p) for p in error['loc']) or 'body'}: {error['msg']}"
            for error in exc.errors()[:3]
        )
        salvaged = _salvage_ids(delivery.body)
        if salvaged is None:
            log.error(
                "unreadable job dead-lettered",
                extra={"messageId": delivery.props.message_id, "error": reason},
            )
            span.set_status(Status(StatusCode.ERROR, "unreadable job"))
            try:
                self._call_in_main(lambda: self._settle_now(delivery, "dead-letter"))
            except Abandoned as abandoned:
                log.warning("unreadable job not settled", extra={"reason": str(abandoned)})
            return
        job_id, run_id, attempt = salvaged
        ids = _ids(job_id, run_id, attempt, delivery.count)

        def refusal(stack: ExitStack) -> Result:
            return self._error(
                job_id, run_id, attempt, delivery.count, "contract_violation", reason
            )

        with telemetry.job_scope(ids):
            self._answer(delivery, span, ids, run_id, refusal, None)

    def _answer(
        self,
        delivery: Delivery,
        span: Span,
        ids: dict[str, Any],
        run_id: UUID,
        build: Callable[[ExitStack], Result],
        job: Any | None,
    ) -> None:
        """Publishes `started`, builds and publishes the result, then acks; retries on failure."""
        began = time.monotonic()
        job_context = otel_context.get_current()
        heartbeat = _Heartbeat(
            self._heartbeat_seconds,
            lambda: self._beat(job, delivery, job_context) if job else None,
        )
        outcome = "abandoned"
        try:
            # Inputs stay on disk until the result is out, then the stack removes them.
            with ExitStack() as stack:
                if job is not None:
                    self._publish(run_id, PROGRESS_KEY, self._progress(job, delivery, "started"))
                    heartbeat.start()
                result = build(stack)
                # No heartbeat may follow the result: stop it before the result goes out.
                heartbeat.stop(join=True)
                self._publish(
                    run_id, RESULT_KEY, result, message_id=f"{result.jobId}:{result.attempt}:result"
                )
            self._call_in_main(lambda: self._settle_now(delivery, "ack"))
            outcome = result.status if isinstance(result, CompletedResult) else result.error.code
            if isinstance(result, ErrorResult):
                span.set_status(Status(StatusCode.ERROR, result.error.code))
            log.info("job answered", extra={"outcome": outcome, "durationMs": _ms_since(began)})
        except Abandoned as abandoned:
            log.warning("job abandoned; the broker redelivers it", extra={"reason": str(abandoned)})
        except Exception as exc:
            outcome = "retried"
            retry_after = exc.retry_after_seconds if isinstance(exc, TransientError) else None
            delay = retry_after if retry_after is not None else backoff_seconds(delivery.count)
            error, reason = _describe(exc)
            span.add_event(
                "exception", {"exception.type": error, "exception.message": reason or error}
            )
            span.set_status(Status(StatusCode.ERROR, error))
            log.warning(
                "transient failure; returning the job after a backoff",
                extra={"delaySeconds": delay, "error": error, "reason": reason},
            )
            self._wait(delay)
            try:
                self._call_in_main(lambda: self._settle_now(delivery, "requeue"))
            except Abandoned as abandoned:
                log.warning(
                    "job not returned; the broker redelivers it", extra={"reason": str(abandoned)}
                )
        finally:
            heartbeat.stop()
            attributes = {"capability": str(ids.get("capability", "unknown")), "outcome": outcome}
            jobs_finished.add(1, attributes)
            job_duration.record(time.monotonic() - began, attributes)

    def _execute(self, job: Any, delivery: Delivery, stack: ExitStack) -> Result:
        runner = delivery.runner
        watchdog = threading.Timer(job.maxRuntimeSeconds, runner.kill, args=("run_timeout",))
        watchdog.daemon = True
        watchdog.start()
        try:
            legacy_prefix = f"runs/{job.runId}/steps/{job.stepOrder}/"
            attempt_prefix = f"{legacy_prefix}attempts/{job.attempt}/"
            if job.outputPrefix not in (legacy_prefix, attempt_prefix):
                raise PermanentError("contract_violation", "outputPrefix does not match the step")
            if self._execute_job is None:
                handler = self._resolve(job.capability)
                inputs = {
                    ref.key: stack.enter_context(
                        self._storage.fetched(ref, job.maxInputBytes, runner)
                    )
                    for ref in job.inputs
                }
                outcome = handler(job, inputs, runner)
            else:
                outcome = self._execute_job(job, runner)
            if runner.killed is not None:
                raise ProcessKilled(runner.killed)
            outputs = []
            for file in outcome.files:
                key = output_key(job, file.name)
                size = self._storage.put(key, file.body, file.mime, runner)
                outputs.append(
                    MediaOutput(
                        name=file.name,
                        bucket=self._storage.bucket,
                        objectKey=key,
                        mime=file.mime,
                        bytes=size,
                    )
                )
            return CompletedResult(
                schemaVersion="1",
                jobId=job.jobId,
                runId=job.runId,
                attempt=job.attempt,
                deliveryCount=delivery.count,
                status="completed",
                outputs=outputs,
                data=outcome.data,
                finishedAt=datetime.now(UTC),
                workerId=self._worker_id,
            )
        except PermanentError as exc:
            if runner.killed is None:
                return self._error(
                    job.jobId, job.runId, job.attempt, delivery.count, exc.code, exc.message
                )
            return self._killed(job, delivery, runner.killed)
        except Exception:
            if runner.killed is None:
                raise
            return self._killed(job, delivery, runner.killed)
        finally:
            watchdog.cancel()

    def _killed(self, job: MediaJob, delivery: Delivery, reason: str) -> Result:
        if reason != "run_timeout":
            raise Abandoned(f"stopped: {reason}")
        return self._error(
            job.jobId,
            job.runId,
            job.attempt,
            delivery.count,
            "run_timeout",
            f"stopped after the {job.maxRuntimeSeconds}-second maximum run time",
        )

    def _error(
        self, job_id: UUID, run_id: UUID, attempt: int, count: int, code: ErrorCode, message: str
    ) -> ErrorResult:
        return ErrorResult(
            schemaVersion="1",
            jobId=job_id,
            runId=run_id,
            attempt=attempt,
            deliveryCount=count,
            status="error",
            error=MediaError(code=code, message=message, retryable=False),
            finishedAt=datetime.now(UTC),
            workerId=self._worker_id,
        )

    def _progress(
        self, job: MediaJob, delivery: Delivery, kind: Literal["started", "heartbeat"]
    ) -> MediaProgress:
        return MediaProgress(
            schemaVersion="1",
            jobId=job.jobId,
            runId=job.runId,
            attempt=job.attempt,
            deliveryCount=delivery.count,
            kind=kind,
            at=datetime.now(UTC),
        )

    def _beat(self, job: MediaJob, delivery: Delivery, job_context: Any) -> None:
        try:
            self._publish(
                job.runId,
                PROGRESS_KEY,
                self._progress(job, delivery, "heartbeat"),
                context=job_context,
            )
        except Exception as exc:
            error, reason = _describe(exc)
            log.warning("heartbeat not published", extra={"error": error, "reason": reason})

    def _publish(
        self,
        run_id: UUID,
        routing_key: str,
        message: BaseModel,
        *,
        message_id: str | None = None,
        context: Any = None,
    ) -> None:
        headers: dict[str, Any] = {}
        propagate.inject(headers, context=context)
        props = pika.BasicProperties(
            content_type="application/json",
            delivery_mode=2,
            message_id=message_id,
            correlation_id=str(run_id),
            headers=headers,
        )
        # Absent optionals stay absent: the schemas forbid an explicit null.
        body = message.model_dump_json(exclude_none=True).encode()
        self._call_in_main(
            lambda: self._publish_channel.basic_publish(
                self._exchange, routing_key, body, props, mandatory=True
            )
        )

    def _call_in_main(self, fn: Callable[[], T]) -> T:
        """Runs `fn` on the connection's thread and returns its result or raises its error."""
        future: Future[T] = Future()

        def call() -> None:
            if not future.set_running_or_notify_cancel():
                return
            try:
                future.set_result(fn())
            except Exception as exc:
                future.set_exception(exc)
            except BaseException as exc:
                # The waiting thread still learns the outcome; an exit keeps unwinding this one.
                future.set_exception(exc)
                raise

        if self._closed.is_set():
            raise Abandoned(CONNECTION_CLOSED)
        try:
            self._connection.add_callback_threadsafe(call)
        except pika.exceptions.AMQPError as exc:
            raise Abandoned(CONNECTION_CLOSED) from exc
        while True:
            try:
                return future.result(timeout=0.25)
            except TimeoutError:
                if self._closed.is_set() and future.cancel():
                    raise Abandoned("connection closed before the broker answered") from None


class _Heartbeat:
    JOIN_SECONDS = 5.0

    def __init__(self, interval: float, beat: Callable[[], None]) -> None:
        self._interval = interval
        self._beat = beat
        self._stopped = threading.Event()
        # The job's context (trace, baggage, log ids) follows the beats into their thread.
        context = contextvars.copy_context()
        self._thread = threading.Thread(
            target=context.run, args=(self._loop,), name="heartbeat", daemon=True
        )

    def start(self) -> None:
        self._thread.start()

    def stop(self, join: bool = False) -> None:
        self._stopped.set()
        if join and self._thread.is_alive():
            self._thread.join(self.JOIN_SECONDS)

    def _loop(self) -> None:
        while not self._stopped.wait(self._interval):
            self._beat()


def _describe(exc: BaseException) -> tuple[str, str | None]:
    """The exception's type, and its message only when the worker wrote it (no URLs)."""
    own = (PermanentError, TransientError, ProcessKilled, Abandoned)
    return type(exc).__name__, str(exc) if isinstance(exc, own) else None


def _ids(job_id: UUID, run_id: UUID, attempt: int, count: int) -> dict[str, Any]:
    return {
        "jobId": str(job_id),
        "runId": str(run_id),
        "correlationId": str(run_id),
        "attempt": attempt,
        "deliveryCount": count,
    }


def _salvage_ids(body: bytes) -> tuple[UUID, UUID, int] | None:
    """The ids an error result needs, from a job that failed validation, if they are usable."""
    try:
        loaded = json.loads(body)
        attempt = loaded["attempt"]
        if not isinstance(attempt, int) or isinstance(attempt, bool) or attempt < 1:
            return None
        return UUID(str(loaded["jobId"])), UUID(str(loaded["runId"])), attempt
    except (ValueError, KeyError, TypeError):
        return None


def _ms_since(began: float) -> int:
    return round((time.monotonic() - began) * 1000)


def _log_crash(future: Future[None]) -> None:
    exc = future.exception()
    if exc is not None:
        error, reason = _describe(exc)
        log.error("job thread crashed", extra={"error": error, "reason": reason})
