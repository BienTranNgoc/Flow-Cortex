"""Dedicated-connection RabbitMQ control client for parallel clip cancellation."""

from __future__ import annotations

import contextlib
import logging
import queue
import re
import secrets
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any, Literal
from uuid import UUID, uuid4

import pika
from pika.exceptions import AMQPError
from pydantic import ValidationError

from ..contracts.media_control_request import MediaControlRequest
from ..contracts.media_control_response import MediaControlResponse
from ..errors import StaleDelivery

log = logging.getLogger(__name__)

CONTROL_EXCHANGE = "provider"
CONTROL_ROUTING_KEY = "control"
CONTROL_REPLY_PREFIX = "provider.control.reply."
DEFAULT_POLL_INTERVAL_SECONDS = 5.0
DEFAULT_REQUEST_TIMEOUT_SECONDS = 5.0
DEFAULT_RECONNECT_BACKOFF_SECONDS = 0.5

Decision = Literal["continue", "cancel_sibling_failed", "discard_stale"]


class SiblingFailedSignal(Exception):
    """Raised when the backend instructs this clip delivery to cancel because its sibling failed."""


class ControlClientClosed(RuntimeError):
    """Raised when the dedicated control client or session is closing."""


@dataclass(frozen=True)
class ControlConfig:
    amqp_url: str
    worker_id: str = "provider-video-worker"
    poll_interval_seconds: float = DEFAULT_POLL_INTERVAL_SECONDS
    request_timeout_seconds: float = DEFAULT_REQUEST_TIMEOUT_SECONDS
    reconnect_backoff_seconds: float = DEFAULT_RECONNECT_BACKOFF_SECONDS


@dataclass
class _RpcCall:
    request: MediaControlRequest
    done: threading.Event
    decision: Decision | None = None
    error: Exception | None = None


def _sanitize_worker_id(worker_id: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9._-]", "-", worker_id).strip(".-")
    return cleaned or "worker"


def _default_connect(amqp_url: str) -> Any:
    return pika.BlockingConnection(pika.URLParameters(amqp_url))


class JobControlSession:
    """Per-delivery control state: blocks before submit and polls every interval while active."""

    def __init__(
        self,
        client: ControlClient,
        run_id: UUID,
        job_id: UUID,
        attempt: int,
        abort: Any,
    ) -> None:
        self._client = client
        self._run_id = run_id
        self._job_id = job_id
        self._attempt = attempt
        self._abort = abort
        self.wake_event = threading.Event()
        self._sibling_failed = threading.Event()
        self._stale = threading.Event()
        self._closed = threading.Event()
        self._poll_thread: threading.Thread | None = None

    def await_initial(self) -> Decision:
        """Poll until the backend returns a valid initial control decision."""
        while True:
            self._abort.check()
            if self._closed.is_set():
                raise ControlClientClosed("Control session closed")
            try:
                decision = self._client.check_once(self._run_id, self._job_id, self._attempt)
                if decision == "cancel_sibling_failed":
                    self._sibling_failed.set()
                    self.wake_event.set()
                elif decision == "discard_stale":
                    self._stale.set()
                    self.wake_event.set()
                return decision
            except ControlClientClosed:
                raise
            except Exception as exc:
                log.warning(
                    "Initial control check failed, retrying: runId=%s jobId=%s attempt=%s err=%s",
                    self._run_id,
                    self._job_id,
                    self._attempt,
                    exc,
                )
                self._sleep_with_abort(self._client.reconnect_backoff_seconds)

    def start_polling(self) -> None:
        if self._poll_thread is not None:
            return
        self._poll_thread = threading.Thread(
            target=self._poll_loop,
            name=f"control-poll-{self._job_id}",
            daemon=True,
        )
        self._poll_thread.start()

    def close(self) -> None:
        self._closed.set()
        self.wake_event.set()
        if self._poll_thread is not None and self._poll_thread.is_alive():
            self._poll_thread.join(timeout=2.0)

    def is_sibling_failed(self) -> bool:
        return self._sibling_failed.is_set()

    def is_stale(self) -> bool:
        return self._stale.is_set()

    def raise_if_aborted(self) -> None:
        if self._stale.is_set():
            raise StaleDelivery(f"Job {self._job_id} attempt {self._attempt} discarded as stale")
        if self._sibling_failed.is_set():
            raise SiblingFailedSignal(f"Sibling failed for run {self._run_id} job {self._job_id}")

    def wrapped_abort(self) -> _ControlledAbort:
        return _ControlledAbort(self._abort, self)

    def _poll_loop(self) -> None:
        while not self._closed.is_set():
            wait_seconds = self._client.poll_interval_seconds
            if self._closed.wait(timeout=wait_seconds):
                return
            while not self._closed.is_set():
                try:
                    self._abort.check()
                except Exception:
                    self.wake_event.set()
                    return
                try:
                    decision = self._client.check_once(self._run_id, self._job_id, self._attempt)
                except ControlClientClosed:
                    return
                except Exception as exc:
                    log.warning(
                        "Control poll failed, reconnecting: runId=%s jobId=%s attempt=%s err=%s",
                        self._run_id,
                        self._job_id,
                        self._attempt,
                        exc,
                    )
                    if self._closed.wait(timeout=self._client.reconnect_backoff_seconds):
                        return
                    continue
                if decision == "cancel_sibling_failed":
                    self._sibling_failed.set()
                    self.wake_event.set()
                    return
                if decision == "discard_stale":
                    self._stale.set()
                    self.wake_event.set()
                    return
                break

    def _sleep_with_abort(self, seconds: float) -> None:
        deadline = time.monotonic() + max(0.0, seconds)
        while not self._closed.is_set():
            self._abort.check()
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return
            if self._closed.wait(timeout=min(remaining, 0.25)):
                return


class _ControlledAbort:
    def __init__(self, base_abort: Any, session: JobControlSession) -> None:
        self._base = base_abort
        self._session = session

    def check(self) -> None:
        self._base.check()
        self._session.raise_if_aborted()

    def run(self, argv: Any) -> Any:
        return self._base.run(argv)

    def wait(self, seconds: float) -> bool:
        deadline = time.monotonic() + max(0.0, seconds)
        while True:
            self.check()
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return False
            if self._session.wake_event.wait(timeout=min(remaining, 0.2)):
                self.check()
                return True


class ControlClient:
    """Single dedicated RabbitMQ control connection for provider-video-worker."""

    def __init__(
        self,
        config: ControlConfig,
        connect: Callable[[str], Any] = _default_connect,
    ) -> None:
        self._config = config
        self._connect = connect
        self._safe_worker = _sanitize_worker_id(config.worker_id)
        self._calls: queue.Queue[_RpcCall] = queue.Queue()
        self._stop = threading.Event()
        self._closed = False
        self._thread: threading.Thread | None = None
        self._lock = threading.Lock()
        self._connection: Any = None
        self._channel: Any = None
        self._reply_queue: str | None = None
        self._consumer_tag: str | None = None
        self._in_flight: dict[str, tuple[_RpcCall, UUID, UUID, int]] = {}

    @property
    def poll_interval_seconds(self) -> float:
        return self._config.poll_interval_seconds

    @property
    def reconnect_backoff_seconds(self) -> float:
        return self._config.reconnect_backoff_seconds

    def start(self) -> None:
        with self._lock:
            if self._closed:
                raise ControlClientClosed("Control client is closed")
            if self._thread is not None and self._thread.is_alive():
                return
            self._stop.clear()
            self._thread = threading.Thread(
                target=self._run_loop,
                name="provider-control-conn",
                daemon=True,
            )
            self._thread.start()

    def close(self) -> None:
        with self._lock:
            self._closed = True
            self._stop.set()
            thread = self._thread
        if thread is not None and thread.is_alive():
            thread.join(timeout=5.0)
        self._fail_pending(ControlClientClosed("Control client stopped"))

    def open_session(
        self, run_id: UUID, job_id: UUID, attempt: int, *, abort: Any
    ) -> JobControlSession:
        return JobControlSession(self, run_id, job_id, attempt, abort)

    def check_once(self, run_id: UUID, job_id: UUID, attempt: int) -> Decision:
        if self._closed or self._stop.is_set():
            raise ControlClientClosed("Control client is closed")
        self.start()
        req = MediaControlRequest(schemaVersion="1", runId=run_id, jobId=job_id, attempt=attempt)
        call = _RpcCall(request=req, done=threading.Event())
        self._calls.put(call)
        wait_limit = max(self._config.request_timeout_seconds + 2.0, 2.0)
        if not call.done.wait(timeout=wait_limit):
            raise TimeoutError(f"Control check timed out for job {job_id}")
        if call.error is not None:
            raise call.error
        if call.decision is None:
            raise RuntimeError("Control call finished without decision")
        return call.decision

    def _run_loop(self) -> None:
        try:
            while not self._stop.is_set():
                try:
                    call = self._calls.get(timeout=0.25)
                except queue.Empty:
                    self._service_idle()
                    continue
                self._execute_call(call)
        finally:
            self._close_connection()

    def _service_idle(self) -> None:
        if self._connection is None:
            return
        try:
            self._connection.process_data_events(time_limit=0.05)
        except (AMQPError, OSError):
            self._close_connection()

    def _execute_call(self, call: _RpcCall) -> None:
        req = call.request
        correlation_id = uuid4().hex
        try:
            self._ensure_connected()
            ch = self._channel
            conn = self._connection
            reply_queue = self._reply_queue
            assert ch is not None and conn is not None and reply_queue is not None
            self._in_flight[correlation_id] = (call, req.runId, req.jobId, req.attempt)
            props = pika.BasicProperties(
                reply_to=reply_queue,
                correlation_id=correlation_id,
                content_type="application/json",
                delivery_mode=1,
            )
            ch.basic_publish(
                exchange=CONTROL_EXCHANGE,
                routing_key=CONTROL_ROUTING_KEY,
                body=req.model_dump_json().encode("utf-8"),
                properties=props,
            )
            deadline = time.monotonic() + self._config.request_timeout_seconds
            while not call.done.is_set() and not self._stop.is_set():
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError(
                        f"Control RPC timed out for job {req.jobId} attempt {req.attempt}"
                    )
                conn.process_data_events(time_limit=min(remaining, 0.2))
            if self._stop.is_set() and not call.done.is_set():
                raise ControlClientClosed("Control client stopped during RPC")
        except Exception as exc:
            if not call.done.is_set():
                call.error = exc
                call.done.set()
            if isinstance(exc, (AMQPError, OSError, TimeoutError)):
                self._close_connection()
        finally:
            self._in_flight.pop(correlation_id, None)

    def _ensure_connected(self) -> None:
        if self._connection is not None and self._channel is not None:
            is_open = getattr(self._connection, "is_open", True)
            ch_open = getattr(self._channel, "is_open", True)
            if is_open and ch_open:
                return
            self._close_connection()
        conn = self._connect(self._config.amqp_url)
        try:
            ch = conn.channel()
            reply_queue = f"{CONTROL_REPLY_PREFIX}{self._safe_worker}.{secrets.token_hex(6)}"
            ch.queue_declare(queue=reply_queue, durable=False, exclusive=True, auto_delete=True)
            tag = ch.basic_consume(
                queue=reply_queue, on_message_callback=self._on_reply, auto_ack=True
            )
        except Exception:
            with contextlib.suppress(Exception):
                conn.close()
            raise
        self._connection = conn
        self._channel = ch
        self._reply_queue = reply_queue
        self._consumer_tag = tag

    def _on_reply(self, _ch: Any, _method: Any, properties: Any, body: bytes) -> None:
        corr = getattr(properties, "correlation_id", None) if properties is not None else None
        if not corr or corr not in self._in_flight:
            log.warning("Ignoring control reply with unknown correlationId=%s", corr)
            return
        call, run_id, job_id, attempt = self._in_flight[corr]
        try:
            response = MediaControlResponse.model_validate_json(body)
        except ValidationError as exc:
            log.warning("Ignoring invalid control response for jobId=%s: %s", job_id, exc)
            return
        if response.runId != run_id or response.jobId != job_id or response.attempt != attempt:
            log.warning(
                "Ignoring mismatched control response: expected=(%s,%s,%s) got=(%s,%s,%s)",
                run_id,
                job_id,
                attempt,
                response.runId,
                response.jobId,
                response.attempt,
            )
            return
        call.decision = response.decision
        call.done.set()

    def _close_connection(self) -> None:
        ch = self._channel
        conn = self._connection
        self._channel = None
        self._connection = None
        self._reply_queue = None
        self._consumer_tag = None
        if ch is not None:
            with contextlib.suppress(Exception):
                ch.close()
        if conn is not None:
            with contextlib.suppress(Exception):
                conn.close()

    def _fail_pending(self, error: Exception) -> None:
        while True:
            try:
                call = self._calls.get_nowait()
            except queue.Empty:
                break
            if not call.done.is_set():
                call.error = error
                call.done.set()
