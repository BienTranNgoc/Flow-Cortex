"""OpenTelemetry for the worker: bootstrapped in code, configured from the standard env vars.

Spans are always created, so trace ids reach the logs even when no collector runs; an
exporter is attached per signal only when its `OTEL_*_EXPORTER` is `otlp` (the default).
"""

from __future__ import annotations

import json
import logging
import os
import re
import threading
from collections.abc import Iterator, Mapping, Sequence
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any

from opentelemetry import baggage, metrics, trace
from opentelemetry import context as otel_context
from opentelemetry._logs import set_logger_provider
from opentelemetry.attributes import BoundedAttributes
from opentelemetry.exporter.otlp.proto.http._log_exporter import OTLPLogExporter
from opentelemetry.exporter.otlp.proto.http.metric_exporter import OTLPMetricExporter
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.instrumentation.botocore import BotocoreInstrumentor
from opentelemetry.instrumentation.logging import LoggingInstrumentor
from opentelemetry.sdk._logs import LoggerProvider, LogRecordProcessor, ReadWriteLogRecord
from opentelemetry.sdk._logs.export import BatchLogRecordProcessor
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import MetricReader, PeriodicExportingMetricReader
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import Event, ReadableSpan, SpanLimits, TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor, SpanExporter, SpanExportResult
from opentelemetry.util.types import Attributes

log = logging.getLogger(__name__)

SERVICE_NAME = "flow-worker"
ATTRIBUTE_LENGTH_LIMIT = 4096
_SIGNALS = {
    "traces": "OTEL_TRACES_EXPORTER",
    "metrics": "OTEL_METRICS_EXPORTER",
    "logs": "OTEL_LOGS_EXPORTER",
}


@dataclass
class Telemetry:
    tracer_provider: TracerProvider
    meter_provider: MeterProvider
    logger_provider: LoggerProvider
    exporting: list[str] = field(default_factory=list)

    def install(self) -> None:
        trace.set_tracer_provider(self.tracer_provider)
        metrics.set_meter_provider(self.meter_provider)
        set_logger_provider(self.logger_provider)
        BotocoreInstrumentor().instrument(tracer_provider=self.tracer_provider)  # type: ignore[no-untyped-call]
        LoggingInstrumentor().instrument(
            tracer_provider=self.tracer_provider,
            inject_trace_context=True,
            enable_log_auto_instrumentation="logs" in self.exporting,
        )
        _add_job_fields_to_records()

    def shutdown(self, timeout_seconds: float) -> None:
        """Flushes and stops every provider, giving up after the timeout."""

        def stop() -> None:
            millis = int(timeout_seconds * 1000)
            self.tracer_provider.force_flush(millis)
            self.tracer_provider.shutdown()
            self.meter_provider.shutdown(timeout_millis=millis)
            self.logger_provider.shutdown()

        worker = threading.Thread(target=stop, name="telemetry-shutdown", daemon=True)
        worker.start()
        worker.join(timeout_seconds)
        if worker.is_alive():
            log.warning("telemetry flush timed out", extra={"timeoutSeconds": timeout_seconds})


def build(environ: Mapping[str, str] = os.environ) -> Telemetry:
    protocol = environ.get("OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf")
    if protocol != "http/protobuf":
        raise ValueError(
            f"OTEL_EXPORTER_OTLP_PROTOCOL={protocol} is not supported, only http/protobuf"
        )
    exporting = [signal for signal, var in _SIGNALS.items() if _exporter(environ, var)]
    resource = Resource.create({"service.name": environ.get("OTEL_SERVICE_NAME", SERVICE_NAME)})

    tracer_provider = TracerProvider(
        resource=resource, span_limits=SpanLimits(max_attribute_length=ATTRIBUTE_LENGTH_LIMIT)
    )
    if "traces" in exporting:
        tracer_provider.add_span_processor(BatchSpanProcessor(Redacting(OTLPSpanExporter())))
    readers: list[MetricReader] = []
    if "metrics" in exporting:
        readers.append(PeriodicExportingMetricReader(OTLPMetricExporter()))
    logger_provider = LoggerProvider(resource=resource)
    # First, so every processor added after it sees the capped values.
    logger_provider.add_log_record_processor(CapLogAttributes())
    if "logs" in exporting:
        logger_provider.add_log_record_processor(BatchLogRecordProcessor(OTLPLogExporter()))
    return Telemetry(
        tracer_provider=tracer_provider,
        meter_provider=MeterProvider(resource=resource, metric_readers=readers),
        logger_provider=logger_provider,
        exporting=exporting,
    )


class CapLogAttributes(LogRecordProcessor):
    """Truncates log record attribute values to `ATTRIBUTE_LENGTH_LIMIT`.

    This SDK's `LoggerProvider` takes no limits argument; log records read theirs only from
    the `OTEL_*_LENGTH_LIMIT` env vars, so the cap is applied here instead.
    """

    def on_emit(self, log_record: ReadWriteLogRecord) -> None:
        log_record.log_record.attributes = BoundedAttributes(
            maxlen=log_record.limits.max_log_record_attributes,
            attributes=log_record.log_record.attributes,
            immutable=False,
            max_value_len=ATTRIBUTE_LENGTH_LIMIT,
            extended_attributes=True,
        )

    def shutdown(self) -> None:
        # Nothing to release: the processor holds no exporter, buffer or thread.
        pass

    def force_flush(self, timeout_millis: int = 30000) -> bool:
        return True


def _exporter(environ: Mapping[str, str], var: str) -> bool:
    value = environ.get(var, "otlp").strip().lower()
    if value not in ("otlp", "none"):
        raise ValueError(f"{var}={value} is not supported, only otlp or none")
    return value == "otlp"


_RECORD_FIELDS = set(vars(logging.LogRecord("", 0, "", 0, "", None, None))) | {
    "message",
    "asctime",
    "taskName",
}
_TRACE_FIELDS = {"otelTraceID": "traceId", "otelSpanID": "spanId"}
_DROPPED = {"otelTraceSampled", "otelServiceName"}


class JsonFormatter(logging.Formatter):
    """One JSON object per line; extras such as jobId/runId become top-level keys."""

    def format(self, record: logging.LogRecord) -> str:
        line: dict[str, Any] = {
            "ts": datetime.fromtimestamp(record.created, UTC).isoformat(timespec="milliseconds"),
            "level": record.levelname,
            "logger": record.name,
            "message": scrub(record.getMessage()),
        }
        for key, value in vars(record).items():
            if key in _RECORD_FIELDS or key in _DROPPED:
                continue
            if key in _TRACE_FIELDS:
                if value != "0":
                    line[_TRACE_FIELDS[key]] = value
                continue
            line[key] = value
        if record.exc_info:
            line["exception"] = scrub(self.formatException(record.exc_info))
        return json.dumps(line, default=str)


def configure_logging(level: int = logging.INFO) -> None:
    """JSON lines on stderr; call before `Telemetry.install`, which adds the OTLP log handler."""
    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter())
    logging.basicConfig(handlers=[handler], level=level, force=True)
    logging.getLogger("pika").setLevel(logging.WARNING)
    logging.getLogger("botocore").setLevel(logging.WARNING)


_URL = re.compile(r"[a-zA-Z][a-zA-Z0-9+.-]*://[^\s\"'<>]+")
REDACTED_URL = "<url>"


def scrub(value: str) -> str:
    return _URL.sub(REDACTED_URL, value)


def _scrubbed(attributes: Attributes) -> Attributes:
    if not attributes:
        return attributes
    return {k: scrub(v) if isinstance(v, str) else v for k, v in attributes.items()}


def _carries_url(attributes: Attributes) -> bool:
    return any(isinstance(v, str) and "://" in v for v in (attributes or {}).values())


class Redacting(SpanExporter):
    """Removes URLs from span and event attributes before export.

    botocore spans record connection errors whose message and stack trace carry the
    endpoint URL; objects are named by bucket and key only.
    """

    def __init__(self, inner: SpanExporter) -> None:
        self._inner = inner

    def export(self, spans: Sequence[ReadableSpan]) -> SpanExportResult:
        return self._inner.export([self._redact(span) for span in spans])

    def shutdown(self) -> None:
        self._inner.shutdown()

    def force_flush(self, timeout_millis: int = 30000) -> bool:
        return self._inner.force_flush(timeout_millis)

    @staticmethod
    def _redact(span: ReadableSpan) -> ReadableSpan:
        if not _carries_url(span.attributes) and not any(
            _carries_url(event.attributes) for event in span.events
        ):
            return span
        return ReadableSpan(
            name=span.name,
            context=span.context,
            parent=span.parent,
            resource=span.resource,
            attributes=_scrubbed(span.attributes),
            events=[
                Event(event.name, _scrubbed(event.attributes), event.timestamp)
                for event in span.events
            ],
            links=span.links,
            kind=span.kind,
            status=span.status,
            start_time=span.start_time,
            end_time=span.end_time,
            instrumentation_scope=span.instrumentation_scope,
        )


_job_ids: ContextVar[Mapping[str, Any] | None] = ContextVar("flowcortex_job_ids", default=None)


@contextmanager
def job_scope(ids: Mapping[str, Any]) -> Iterator[None]:
    """Everything logged or published inside carries the job's ids; runId also as baggage."""
    token = otel_context.attach(baggage.set_baggage("runId", str(ids["runId"])))
    ids_token = _job_ids.set(ids)
    try:
        yield
    finally:
        _job_ids.reset(ids_token)
        otel_context.detach(token)


def _add_job_fields_to_records() -> None:
    previous = logging.getLogRecordFactory()

    def factory(*args: Any, **kwargs: Any) -> logging.LogRecord:
        record = previous(*args, **kwargs)
        for key, value in (_job_ids.get() or {}).items():
            setattr(record, key, value)
        run_id = baggage.get_baggage("runId")
        if run_id is not None:
            record.runId = run_id
        return record

    logging.setLogRecordFactory(factory)
