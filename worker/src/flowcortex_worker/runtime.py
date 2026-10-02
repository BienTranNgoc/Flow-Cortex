"""Process control around jobs: killable handler subprocesses and the reconnecting supervisor."""

from __future__ import annotations

import logging
import subprocess
import threading
from collections.abc import Callable
from pathlib import Path
from typing import TYPE_CHECKING, Any

import pika.exceptions
from opentelemetry import trace
from opentelemetry.trace import Status, StatusCode

if TYPE_CHECKING:
    from .consumer import Consumer

log = logging.getLogger(__name__)
tracer = trace.get_tracer(__name__)

RECONNECT_CAP_SECONDS = 60.0


class ProcessKilled(Exception):
    def __init__(self, reason: str) -> None:
        super().__init__(f"subprocess killed: {reason}")
        self.reason = reason


class ProcessRunner:
    """A job's kill switch: its subprocesses and transfers stop when the watchdog, a cancel
    or a shutdown kills it."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._process: subprocess.Popen[bytes] | None = None
        self._killed: str | None = None
        self._on_kill: list[Callable[[], None]] = []

    @property
    def killed(self) -> str | None:
        return self._killed

    def check(self) -> None:
        if self._killed is not None:
            raise ProcessKilled(self._killed)

    def on_kill(self, callback: Callable[[], None]) -> None:
        with self._lock:
            if self._killed is None:
                self._on_kill.append(callback)
                return
        callback()

    def run(self, argv: list[str]) -> subprocess.CompletedProcess[bytes]:
        executable = Path(argv[0]).name
        # argv is not recorded: it holds local paths and, for later capabilities, parameters.
        with tracer.start_as_current_span(
            executable, attributes={"process.executable.name": executable}
        ) as span:
            with self._lock:
                if self._killed is not None:
                    raise ProcessKilled(self._killed)
                process = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                self._process = process
            try:
                stdout, stderr = process.communicate()
            finally:
                with self._lock:
                    self._process = None
            span.set_attribute("process.exit.code", process.returncode)
            if self._killed is not None:
                span.set_status(Status(StatusCode.ERROR, "killed"))
                raise ProcessKilled(self._killed)
            if process.returncode != 0:
                span.set_status(Status(StatusCode.ERROR, f"exit code {process.returncode}"))
            return subprocess.CompletedProcess(argv, process.returncode, stdout, stderr)

    def kill(self, reason: str) -> None:
        with self._lock:
            if self._killed is None:
                self._killed = reason
            if self._process is not None and self._process.poll() is None:
                self._process.kill()
            callbacks, self._on_kill = self._on_kill, []
        for callback in callbacks:
            try:
                callback()
            except Exception as exc:
                log.warning("stopping a transfer failed", extra={"error": type(exc).__name__})


class Supervisor:
    """Owns the broker connection on its thread: connects, runs a consumer, reconnects."""

    def __init__(
        self,
        connect: Callable[[], Any],
        make_consumer: Callable[[Any], Consumer],
        stop: threading.Event,
        wait: Callable[[float], Any],
    ) -> None:
        self._connect = connect
        self._make_consumer = make_consumer
        self._stop = stop
        self._wait = wait

    def run(self) -> None:
        failures = 0
        while not self._stop.is_set():
            try:
                connection = self._connect()
            except pika.exceptions.AMQPConnectionError as exc:
                failures = self._back_off(failures, f"broker unreachable: {exc!r}")
                continue
            consumer = self._make_consumer(connection)
            try:
                reason = consumer.run()
            except (pika.exceptions.AMQPError, ChannelLost) as exc:
                reason = f"connection lost: {exc!r}"
            finally:
                _close_quietly(connection)
            # Only a consumer that got going resets the backoff; a queue that cannot be
            # consumed (missing, or declared with other arguments) keeps backing off.
            if consumer.consuming:
                failures = 0
            if reason != "stopped" and not self._stop.is_set():
                failures = self._back_off(failures, reason)

    def _back_off(self, failures: int, reason: str) -> int:
        delay = min(2.0**failures, RECONNECT_CAP_SECONDS)
        log.warning("reconnecting to the broker", extra={"reason": reason, "delaySeconds": delay})
        self._wait(delay)
        return failures + 1


class ChannelLost(Exception):
    """A channel the consumer depends on was closed by the broker."""


def _close_quietly(connection: Any) -> None:
    try:
        if connection.is_open:
            connection.close()
    except Exception as exc:
        log.debug("closing a broken connection failed", extra={"reason": repr(exc)})
