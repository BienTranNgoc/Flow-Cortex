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

from .adapter import CapabilityProviderAdapter, FixtureProviderAdapter, ProviderAdapter
from .capabilities import ExecutionContext, execute
from .contracts.provider_job import ProviderJob
from .edge_adapter import PROVIDER_EDGE, EdgeSpeechAdapter, config_from_env
from .openai_adapter import (
    _OPENAI_BASE_URL,
    _OPENAI_SCRIPT_MODEL,
    PROVIDER_OPENAI,
    select_adapter,
)
from .seedance_adapter import PROVIDER_SEEDANCE, SeedanceAdapter, SeedanceConfig
from .veo_adapter import PROVIDER_VEO, VeoAdapter, VeoConfig
from .wan_adapter import PROVIDER_WAN, WanAdapter, WanConfig

log = logging.getLogger("flowcortex_provider_worker")

PROVIDER_FIXTURE = "fixture"
TELEMETRY_FLUSH_SECONDS = 5.0
JOB_THREAD_PREFIX = "provider-job"
MAX_TRANSIENT_REDELIVERIES = 3


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
        request = job.root if hasattr(job, "root") else job
        context = ExecutionContext(
            storage=storage,
            bucket=storage.bucket,
            output_prefix=request.outputPrefix,
            abort=runner,
        )
        outcome = execute(ProviderJob(root=job), adapter, context)
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
            max_transient_redeliveries=MAX_TRANSIENT_REDELIVERIES,
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


def _build_adapter() -> CapabilityProviderAdapter:
    """Build independently configurable script and speech providers.

    `PROVIDER_ADAPTER` remains the backwards-compatible fallback for both selectors. New
    deployments should set `SCRIPT_PROVIDER` and `SPEECH_PROVIDER` explicitly.
    """
    legacy = os.environ.get("PROVIDER_ADAPTER", PROVIDER_FIXTURE).strip().lower()
    script_provider = os.environ.get("SCRIPT_PROVIDER", "").strip().lower() or legacy
    speech_provider = os.environ.get("SPEECH_PROVIDER", "").strip().lower() or legacy
    video_provider = os.environ.get("VIDEO_PROVIDER", PROVIDER_FIXTURE).strip().lower()
    legacy_openai_key = os.environ.get("OPENAI_API_KEY", "").strip()
    script_api_key = (
        os.environ.get("SCRIPT_PROVIDER_API_KEY", "").strip() or legacy_openai_key
    )
    speech_api_key = (
        os.environ.get("SPEECH_PROVIDER_API_KEY", "").strip() or legacy_openai_key
    )
    legacy_base_url = os.environ.get("FLOWCORTEX_PROVIDER_BASE_URL", "").strip()
    script_base_url = (
        os.environ.get("SCRIPT_PROVIDER_BASE_URL", "").strip()
        or legacy_base_url
        or _OPENAI_BASE_URL
    )
    script_model = (
        os.environ.get("SCRIPT_PROVIDER_MODEL", "").strip()
        or os.environ.get("FLOWCORTEX_PROVIDER_SCRIPT_MODEL", "").strip()
        or _OPENAI_SCRIPT_MODEL
    )
    speech_base_url = (
        os.environ.get("SPEECH_PROVIDER_BASE_URL", "").strip()
        or legacy_base_url
        or _OPENAI_BASE_URL
    )
    speech_model = os.environ.get("SPEECH_PROVIDER_MODEL", "").strip() or _OPENAI_SCRIPT_MODEL
    generic_video_key = os.environ.get("VIDEO_PROVIDER_API_KEY", "").strip()
    generic_video_url = os.environ.get("VIDEO_PROVIDER_BASE_URL", "").strip()
    generic_video_model = os.environ.get("VIDEO_PROVIDER_MODEL", "").strip()
    if video_provider == PROVIDER_SEEDANCE:
        video_api_key = os.environ.get("SEEDANCE_API_KEY", "").strip() or generic_video_key
        video_base_url = os.environ.get("SEEDANCE_BASE_URL", "").strip() or generic_video_url
        video_model = os.environ.get("SEEDANCE_MODEL", "").strip() or generic_video_model
    elif video_provider == PROVIDER_VEO:
        video_api_key = os.environ.get("VEO_API_KEY", "").strip() or generic_video_key
        video_base_url = os.environ.get("VEO_BASE_URL", "").strip() or generic_video_url
        video_model = os.environ.get("VEO_MODEL", "").strip() or generic_video_model
    elif video_provider == PROVIDER_WAN:
        video_api_key = os.environ.get("WAN_API_KEY", "").strip() or generic_video_key
        video_base_url = os.environ.get("WAN_BASE_URL", "").strip() or generic_video_url
        video_model = os.environ.get("WAN_MODEL", "").strip() or generic_video_model
    else:
        video_api_key = generic_video_key
        video_base_url = generic_video_url
        video_model = generic_video_model

    script_adapter = _build_script_adapter(
        script_provider, script_api_key, script_base_url, script_model
    )
    speech_adapter = _build_speech_adapter(
        speech_provider, speech_api_key, speech_base_url, speech_model
    )
    video_adapter = _build_video_adapter(
        video_provider, video_api_key, video_base_url, video_model
    )
    log.info(
        "provider adapters selected",
        extra={
            "scriptProvider": script_provider,
            "speechProvider": speech_provider,
            "videoProvider": video_provider,
        },
    )
    return CapabilityProviderAdapter(script_adapter, speech_adapter, video_adapter)


def _build_script_adapter(
    provider: str, api_key: str, base_url: str, model: str
) -> ProviderAdapter:
    if provider == PROVIDER_OPENAI:
        return select_adapter(provider, api_key, base_url=base_url, model=model)
    if provider in {PROVIDER_FIXTURE, PROVIDER_EDGE}:
        # `edge` preserves the old PROVIDER_ADAPTER=edge behaviour for existing environments.
        return FixtureProviderAdapter()
    raise RuntimeError(
        f"Unknown SCRIPT_PROVIDER '{provider}'; accepted values: "
        f"{PROVIDER_FIXTURE}, {PROVIDER_OPENAI}"
    )


def _build_speech_adapter(
    provider: str, api_key: str, base_url: str, model: str
) -> ProviderAdapter:
    if provider == PROVIDER_EDGE:
        return EdgeSpeechAdapter(config_from_env())
    if provider == PROVIDER_OPENAI:
        # Backwards compatibility: this adapter fails generate_speech explicitly until implemented.
        return select_adapter(provider, api_key, base_url=base_url, model=model)
    if provider == PROVIDER_FIXTURE:
        return FixtureProviderAdapter()
    raise RuntimeError(
        f"Unknown SPEECH_PROVIDER '{provider}'; accepted values: "
        f"{PROVIDER_FIXTURE}, {PROVIDER_EDGE}, {PROVIDER_OPENAI}"
    )


def _build_video_adapter(
    provider: str, api_key: str, base_url: str, model: str
) -> ProviderAdapter:
    if provider == PROVIDER_SEEDANCE:
        if not api_key:
            raise RuntimeError("Seedance video provider requires SEEDANCE_API_KEY")
        if not base_url:
            raise RuntimeError("Seedance video provider requires SEEDANCE_BASE_URL")
        return SeedanceAdapter(
            SeedanceConfig(
                api_key=api_key,
                base_url=base_url,
                model=model or "doubao-seedance-1-5-pro-251215",
                resolution=os.environ.get("SEEDANCE_RESOLUTION", "480p").strip().lower(),
                request_timeout_seconds=float(
                    os.environ.get("VIDEO_PROVIDER_REQUEST_TIMEOUT_SECONDS", "60")
                ),
                poll_interval_seconds=float(
                    os.environ.get("VIDEO_PROVIDER_POLL_INTERVAL_SECONDS", "5")
                ),
                poll_timeout_seconds=float(
                    os.environ.get("VIDEO_PROVIDER_POLL_TIMEOUT_SECONDS", "480")
                ),
            )
        )
    if provider == PROVIDER_VEO:
        if not api_key:
            raise RuntimeError("Veo video provider requires VEO_API_KEY")
        return VeoAdapter(
            VeoConfig(
                api_key=api_key,
                base_url=base_url or "https://generativelanguage.googleapis.com/v1beta",
                model=model or "veo-3.1-fast-generate-preview",
                request_timeout_seconds=float(
                    os.environ.get("VIDEO_PROVIDER_REQUEST_TIMEOUT_SECONDS", "60")
                ),
                poll_interval_seconds=float(
                    os.environ.get("VIDEO_PROVIDER_POLL_INTERVAL_SECONDS", "10")
                ),
                poll_timeout_seconds=float(
                    os.environ.get("VIDEO_PROVIDER_POLL_TIMEOUT_SECONDS", "480")
                ),
            )
        )
    if provider == PROVIDER_WAN:
        if not api_key:
            raise RuntimeError("Wan video provider requires WAN_API_KEY")
        return WanAdapter(
            WanConfig(
                api_key=api_key,
                base_url=base_url or "https://api.replicate.com/v1",
                model=model or "wan-video/wan-2.2-5b-fast",
                resolution=os.environ.get("WAN_RESOLUTION", "480p").strip().lower(),
                frames_per_second=int(os.environ.get("WAN_FRAMES_PER_SECOND", "24")),
                num_frames=int(os.environ.get("WAN_NUM_FRAMES", "121")),
                request_timeout_seconds=float(
                    os.environ.get("VIDEO_PROVIDER_REQUEST_TIMEOUT_SECONDS", "60")
                ),
                poll_interval_seconds=float(
                    os.environ.get("VIDEO_PROVIDER_POLL_INTERVAL_SECONDS", "5")
                ),
                poll_timeout_seconds=float(
                    os.environ.get("VIDEO_PROVIDER_POLL_TIMEOUT_SECONDS", "480")
                ),
            )
        )
    if provider == PROVIDER_FIXTURE:
        return FixtureProviderAdapter()
    raise RuntimeError(
        f"Unknown VIDEO_PROVIDER '{provider}'; accepted values: "
        f"{PROVIDER_FIXTURE}, {PROVIDER_SEEDANCE}, {PROVIDER_VEO}, {PROVIDER_WAN}"
    )


if __name__ == "__main__":
    sys.exit(main())
