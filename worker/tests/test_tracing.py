"""One job is one trace: the worker continues the API's trace across its thread hand-off."""

from __future__ import annotations

import io
import json
import logging
import uuid
from pathlib import Path
from typing import Any

import pytest
from conftest import (
    MEDIA_BUCKET,
    UPLOAD_BUCKET,
    FakeConnection,
    FakeStorage,
    Silo,
    captured_logs,
    job_body,
    make_clip,
    published,
    run_consumer,
    settled,
)
from opentelemetry._logs import LogRecord
from opentelemetry.sdk._logs.export import InMemoryLogRecordExporter, SimpleLogRecordProcessor
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from opentelemetry.trace import SpanKind

from flowcortex_worker import telemetry
from flowcortex_worker.contracts.media_job import MediaJobInput
from flowcortex_worker.runtime import ProcessRunner
from flowcortex_worker.storage import Storage

TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
PARENT = f"00-{TRACE_ID}-00f067aa0ba902b7-01"


@pytest.fixture(scope="module")
def clip(tmp_path_factory: pytest.TempPathFactory) -> Path:
    return make_clip(tmp_path_factory.mktemp("clip") / "clip.mp4")


def probe_job(conn: FakeConnection, clip: Path, **props: Any) -> dict[str, Any]:
    body = job_body()
    conn.deliver(body, **props)
    run_consumer(conn, storage=FakeStorage(content=clip.read_bytes()), until=settled, timeout=30)
    return body


def job_span(spans: InMemorySpanExporter) -> Any:
    [span] = [s for s in spans.get_finished_spans() if s.name == "process media.jobs"]
    return span


def test_job_span_continues_the_inbound_trace(spans: InMemorySpanExporter, clip: Path) -> None:
    probe_job(FakeConnection(), clip, headers={"traceparent": PARENT})

    span = job_span(spans)
    assert format(span.context.trace_id, "032x") == TRACE_ID
    assert format(span.parent.span_id, "016x") == "00f067aa0ba902b7"
    assert span.kind == SpanKind.CONSUMER


def test_published_messages_carry_the_job_span_context(
    spans: InMemorySpanExporter, clip: Path
) -> None:
    conn = FakeConnection()
    body = probe_job(conn, clip, headers={"traceparent": PARENT, "baggage": "origin=api"})

    span = job_span(spans)
    expected = f"00-{TRACE_ID}-{format(span.context.span_id, '016x')}-01"
    messages = published(conn, "progress") + published(conn, "result")
    assert len(messages) >= 2
    for message in messages:
        assert message.properties.headers["traceparent"] == expected
        entries = set(message.properties.headers["baggage"].split(","))
        assert entries == {"origin=api", f"runId={body['runId']}"}


def test_ffprobe_span_names_the_executable_and_exit_code_but_not_argv(
    spans: InMemorySpanExporter, clip: Path
) -> None:
    probe_job(FakeConnection(), clip)

    [ffprobe] = [s for s in spans.get_finished_spans() if s.name == "ffprobe"]
    assert ffprobe.attributes is not None
    assert ffprobe.attributes["process.executable.name"] == "ffprobe"
    assert ffprobe.attributes["process.exit.code"] == 0
    assert ffprobe.parent is not None
    assert ffprobe.parent.span_id == job_span(spans).context.span_id
    values = " ".join(str(v) for v in ffprobe.attributes.values())
    assert "-show_streams" not in values
    assert "/tmp" not in values


def test_store_calls_are_traced_without_signed_url_parts(
    spans: InMemorySpanExporter, silo: Silo
) -> None:
    client = silo.client()
    key = f"u-1/{uuid.uuid4()}"
    client.put_object(Bucket=UPLOAD_BUCKET, Key=key, Body=b"x")
    storage = Storage(client, MEDIA_BUCKET, UPLOAD_BUCKET)
    with storage.fetched(
        MediaJobInput(key="media", bucket=UPLOAD_BUCKET, objectKey=key), 10, ProcessRunner()
    ):
        pass
    storage.put(
        f"runs/{uuid.uuid4()}/steps/1/video-details.json",
        b"{}",
        "application/json",
        ProcessRunner(),
    )

    exported = spans.get_finished_spans()
    assert {"S3.HeadObject", "S3.PutObject"} <= {s.name for s in exported}
    for span in exported:
        for name, value in (span.attributes or {}).items():
            assert "X-Amz-" not in str(value), f"{span.name}.{name} carries a signed URL part"


def test_job_log_lines_carry_trace_and_job_ids(spans: InMemorySpanExporter, clip: Path) -> None:
    stream = io.StringIO()
    handler = logging.StreamHandler(stream)
    handler.setFormatter(telemetry.JsonFormatter())
    root = logging.getLogger()
    root.addHandler(handler)
    previous = root.level
    root.setLevel(logging.INFO)
    try:
        body = probe_job(FakeConnection(), clip, headers={"traceparent": PARENT})
    finally:
        root.removeHandler(handler)
        root.setLevel(previous)

    lines = [json.loads(line) for line in stream.getvalue().splitlines()]
    job_lines = [line for line in lines if line.get("jobId") == body["jobId"]]
    assert job_lines
    for line in job_lines:
        assert line["traceId"] == TRACE_ID
        assert len(line["spanId"]) == 16
        assert line["runId"] == line["correlationId"] == body["runId"]
        assert line["capability"] == "probe"
        assert "otelTraceID" not in line
    assert any(line.get("outcome") == "completed" for line in job_lines)


def test_exporters_none_attaches_no_exporter_but_still_creates_spans() -> None:
    tel = telemetry.build(
        {
            "OTEL_TRACES_EXPORTER": "none",
            "OTEL_LOGS_EXPORTER": "none",
            "OTEL_METRICS_EXPORTER": "none",
        }
    )

    assert tel.exporting == []
    span = tel.tracer_provider.get_tracer("t").start_span("s")
    assert span.get_span_context().is_valid
    span.end()
    assert tel.tracer_provider.resource.attributes["service.name"] == "flow-worker"


def test_otlp_exporters_attach_per_signal() -> None:
    tel = telemetry.build(
        {
            "OTEL_SERVICE_NAME": "flow-worker",
            "OTEL_EXPORTER_OTLP_PROTOCOL": "http/protobuf",
            "OTEL_TRACES_EXPORTER": "otlp",
            "OTEL_LOGS_EXPORTER": "none",
            "OTEL_METRICS_EXPORTER": "otlp",
        }
    )

    assert tel.exporting == ["traces", "metrics"]
    tel.shutdown(timeout_seconds=2)


NO_EXPORT = {
    "OTEL_TRACES_EXPORTER": "none",
    "OTEL_LOGS_EXPORTER": "none",
    "OTEL_METRICS_EXPORTER": "none",
}


def test_span_and_event_attribute_values_are_capped_at_4096_characters() -> None:
    tel = telemetry.build(NO_EXPORT)
    exported = InMemorySpanExporter()
    tel.tracer_provider.add_span_processor(SimpleSpanProcessor(exported))

    with tel.tracer_provider.get_tracer("test").start_as_current_span("long") as span:
        span.set_attribute("detail", "x" * 5000)
        span.add_event("failed", {"detail": "y" * 5000})

    [finished] = exported.get_finished_spans()
    assert finished.attributes is not None
    assert finished.attributes["detail"] == "x" * 4096
    assert finished.events[0].attributes is not None
    assert finished.events[0].attributes["detail"] == "y" * 4096
    tel.shutdown(timeout_seconds=2)


def test_log_record_attribute_values_are_capped_at_4096_characters() -> None:
    tel = telemetry.build(NO_EXPORT)
    exported = InMemoryLogRecordExporter()  # type: ignore[no-untyped-call]
    tel.logger_provider.add_log_record_processor(SimpleLogRecordProcessor(exported))

    tel.logger_provider.get_logger("test").emit(LogRecord(attributes={"detail": "x" * 5000}))

    [record] = exported.get_finished_logs()
    assert record.log_record.attributes is not None
    assert record.log_record.attributes["detail"] == "x" * 4096
    tel.shutdown(timeout_seconds=2)


def test_unsupported_otlp_protocol_fails_fast() -> None:
    with pytest.raises(ValueError, match="grpc"):
        telemetry.build({"OTEL_EXPORTER_OTLP_PROTOCOL": "grpc"})


def test_every_line_logged_during_a_job_carries_its_ids(silo: Silo, clip: Path) -> None:
    key = f"u-1/{uuid.uuid4()}"
    silo.client().upload_file(str(clip), UPLOAD_BUCKET, key)
    body = job_body(inputs=[{"key": "media", "bucket": UPLOAD_BUCKET, "objectKey": key}])
    conn = FakeConnection()
    conn.deliver(body)
    storage = Storage(silo.client(), MEDIA_BUCKET, UPLOAD_BUCKET)

    with captured_logs() as lines:
        run_consumer(conn, storage=storage, until=settled, timeout=30)

    owner_thread = {"consuming", "stopping: no new jobs"}
    job_lines = [line for line in lines if line["message"] not in owner_thread]
    assert {line["message"] for line in job_lines} >= {"input downloaded", "output stored"}
    for line in job_lines:
        assert line["runId"] == body["runId"], line
        assert line["jobId"] == body["jobId"], line
    # Without inbound baggage the worker still sends the run id as baggage.
    for message in conn.publish.published:
        assert message.properties.headers["baggage"] == f"runId={body['runId']}"
