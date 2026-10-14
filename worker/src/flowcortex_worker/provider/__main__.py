"""Entrypoint for the provider worker container."""

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

from flowcortex_worker import telemetry
from flowcortex_worker.config import Settings
from flowcortex_worker.consumer import Consumer
from flowcortex_worker.healthcheck import prepare_state_dir
from flowcortex_worker.runtime import ProcessRunner, Supervisor
from flowcortex_worker.storage import Storage, client_from

from .adapter import FixtureProviderAdapter, ProviderAdapter
from .capabilities import execute
from .contracts.provider_job import ProviderJob
from .openai_adapter import (
    PROVIDER_OPENAI,
    select_adapter,
)

log = logging.getLogger("flowcortex_provider_worker")

PROVIDER_FIXTURE = "fixture"
TELEMETRY_FLUSH_SECONDS = 5.0
JOB_THREAD_PREFIX = "provider-job"


def main() -> int:
    telemetry.configure_logging()
    settings = Settings()
    prepare_state_dir(settings.worker_state_dir)
    liveness_file = settings.liveness_file
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
    params.client_properties = {"connection_name": f"provider-worker {settings.worker_id}"}
    storage = Storage(client_from(settings), settings.s3_bucket, settings.s3_upload_bucket)
    adapter: ProviderAdapter = _build_adapter()
    executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix=JOB_THREAD_PREFIX)

    def parse_job(body: bytes) -> Any:
        return ProviderJob.model_validate_json(body).root

    def execute_job(job: Any, runner: ProcessRunner) -> Any:
        runner.check()
        outcome = execute(ProviderJob(root=job), adapter)
        runner.check()
        return outcome

    def make_consumer(connection: Any) -> Consumer:
        return Consumer(
            connection,
            storage=storage,
            worker_id=settings.worker_id,
            heartbeat_seconds=settings.heartbeat_seconds,
            stop=stop,
            executor=executor,
            liveness_file=liveness_file,
            queue="provider.jobs",
            exchange="provider",
            parse_job=parse_job,
            execute_job=execute_job,
        )

    def wait_alive(seconds: float) -> None:
        deadline = time.monotonic() + seconds
        while not stop.is_set() and (left := deadline - time.monotonic()) > 0:
            liveness_file.touch()
            stop.wait(min(left, 1.0))

    log.info("provider worker starting", extra={"workerId": settings.worker_id})
    liveness_file.touch()
    Supervisor(
        connect=lambda: pika.BlockingConnection(params),
        make_consumer=make_consumer,
        stop=stop,
        wait=wait_alive,
    ).run()
    executor.shutdown(wait=False, cancel_futures=True)
    busy = [thread for thread in threading.enumerate() if thread.name.startswith(JOB_THREAD_PREFIX)]
    for thread in busy:
        thread.join(1.0)
    stuck = any(thread.is_alive() for thread in busy)
    log.info("provider worker stopped", extra={"jobThreadStuck": stuck})
    tel.shutdown(TELEMETRY_FLUSH_SECONDS)
    if stuck:
        logging.shutdown()
        os._exit(0)
    return 0


def _build_adapter() -> ProviderAdapter:
    """Pick the adapter from env. Default is `fixture` — no secrets needed, fully deterministic.
    Set PROVIDER_ADAPTER=openai (and OPENAI_API_KEY) to use the real model; any other value
    fails at startup rather than silently serving fixture output."""
    provider = os.environ.get("PROVIDER_ADAPTER", "fixture").strip().lower()
    if provider == PROVIDER_OPENAI:
        chosen = select_adapter(provider, os.environ.get("OPENAI_API_KEY", "").strip())
        log.info("provider adapter selected", extra={"provider": provider})
        return chosen
    if provider != PROVIDER_FIXTURE:
        raise RuntimeError(
            f"Unknown PROVIDER_ADAPTER '{provider}'; accepted values: {PROVIDER_FIXTURE}, "
            f"{PROVIDER_OPENAI}"
        )
    log.info("provider adapter selected", extra={"provider": PROVIDER_FIXTURE})
    return FixtureProviderAdapter()


if __name__ == "__main__":
    sys.exit(main())
