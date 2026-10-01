"""Entry point: `python -m flowcortex_worker`."""

import logging
import os
import signal
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from types import FrameType
from typing import Any

import pika

from . import telemetry
from .config import Settings
from .consumer import Consumer
from .healthcheck import LIVENESS_FILE
from .runtime import Supervisor
from .storage import Storage, client_from, remove_leftovers

log = logging.getLogger("flowcortex_worker")

TELEMETRY_FLUSH_SECONDS = 5.0
JOB_THREAD_PREFIX = "job"


def main() -> int:
    telemetry.configure_logging()
    settings = Settings()
    tel = telemetry.build()
    tel.install()

    stop = threading.Event()

    def on_signal(signum: int, frame: FrameType | None) -> None:
        log.info("shutdown requested", extra={"signal": signal.Signals(signum).name})
        stop.set()

    signal.signal(signal.SIGTERM, on_signal)
    signal.signal(signal.SIGINT, on_signal)

    params = pika.URLParameters(settings.amqp_url.get_secret_value())
    params.heartbeat = 60
    params.blocked_connection_timeout = 300
    params.client_properties = {"connection_name": f"flow-worker {settings.worker_id}"}
    storage = Storage(client_from(settings), settings.s3_bucket, settings.s3_upload_bucket)
    executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix=JOB_THREAD_PREFIX)

    def make_consumer(connection: Any) -> Consumer:
        return Consumer(
            connection,
            storage=storage,
            worker_id=settings.worker_id,
            heartbeat_seconds=settings.heartbeat_seconds,
            stop=stop,
            executor=executor,
            liveness_file=LIVENESS_FILE,
        )

    def wait_alive(seconds: float) -> None:
        # The reconnect backoff reaches the liveness file's max age, so keep touching it.
        deadline = time.monotonic() + seconds
        while not stop.is_set() and (left := deadline - time.monotonic()) > 0:
            LIVENESS_FILE.touch()
            stop.wait(min(left, 1.0))

    log.info("worker starting", extra={"workerId": settings.worker_id})
    remove_leftovers()
    LIVENESS_FILE.touch()
    Supervisor(
        connect=lambda: pika.BlockingConnection(params),
        make_consumer=make_consumer,
        stop=stop,
        wait=wait_alive,
    ).run()
    executor.shutdown(wait=False, cancel_futures=True)
    busy = [t for t in threading.enumerate() if t.name.startswith(JOB_THREAD_PREFIX)]
    for thread in busy:
        thread.join(1.0)
    stuck = any(thread.is_alive() for thread in busy)
    log.info("worker stopped", extra={"jobThreadStuck": stuck})
    tel.shutdown(TELEMETRY_FLUSH_SECONDS)
    if stuck:
        # The interpreter would join the job thread at exit and outlast the stop grace;
        # its delivery was already returned or will be by the closed connection.
        logging.shutdown()
        os._exit(0)
    return 0


if __name__ == "__main__":
    sys.exit(main())
