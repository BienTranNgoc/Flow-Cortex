"""The generated contract models accept reference messages and reject what the schemas forbid."""

from typing import Any

import pytest
from pydantic import ValidationError

from flowcortex_worker.contracts.media_job import MediaJob
from flowcortex_worker.contracts.media_progress import MediaProgress
from flowcortex_worker.contracts.media_result import MediaResult

JOB_ID = "5f0c7c1e-8f7e-4a53-9a38-2f6d6f3f9c11"
RUN_ID = "0b7e1d2c-3a4b-4c5d-8e6f-7a8b9c0d1e2f"
AT = "2026-09-30T12:00:00Z"


def job() -> dict[str, Any]:
    return {
        "schemaVersion": "1",
        "jobId": JOB_ID,
        "runId": RUN_ID,
        "correlationId": RUN_ID,
        "stepOrder": 1,
        "capability": "probe",
        "inputs": [{"key": "source", "bucket": "flowcortex-uploads", "objectKey": "u-1/up-1"}],
        "params": {},
        "outputPrefix": f"runs/{RUN_ID}/steps/1/",
        "attempt": 1,
        "maxRuntimeSeconds": 300,
        "maxInputBytes": 524288000,
        "issuedAt": AT,
    }


def result() -> dict[str, Any]:
    return {
        "schemaVersion": "1",
        "jobId": JOB_ID,
        "runId": RUN_ID,
        "attempt": 1,
        "deliveryCount": 0,
        "status": "completed",
        "outputs": [
            {
                "name": "video-details.json",
                "bucket": "flowcortex-media",
                "objectKey": f"runs/{RUN_ID}/steps/1/video-details.json",
                "mime": "application/json",
                "bytes": 812,
            }
        ],
        "data": {"durationSeconds": 1.0},
        "finishedAt": AT,
        "workerId": "worker-1",
    }


def progress() -> dict[str, Any]:
    return {
        "schemaVersion": "1",
        "jobId": JOB_ID,
        "runId": RUN_ID,
        "attempt": 1,
        "deliveryCount": 0,
        "kind": "started",
        "at": AT,
    }


def test_reference_messages_validate() -> None:
    MediaJob.model_validate(job())
    MediaResult.model_validate(result())
    MediaProgress.model_validate(progress())


def test_error_result_validates() -> None:
    message = result() | {
        "status": "error",
        "outputs": [],
        "error": {"code": "input_rejected", "message": "object missing", "retryable": False},
    }
    MediaResult.model_validate(message)


def test_job_without_capability_fails() -> None:
    message = job()
    del message["capability"]
    with pytest.raises(ValidationError):
        MediaJob.model_validate(message)


@pytest.mark.parametrize(
    ("model", "message"),
    [
        (MediaJob, job() | {"capability": "transcode"}),
        (MediaResult, result() | {"status": "done"}),
        (
            MediaResult,
            result()
            | {"status": "error", "error": {"code": "boom", "message": "x", "retryable": False}},
        ),
    ],
    ids=["capability", "result-status", "error-code"],
)
def test_unknown_enum_value_fails(
    model: type[MediaJob | MediaResult], message: dict[str, Any]
) -> None:
    with pytest.raises(ValidationError):
        model.model_validate(message)


@pytest.mark.parametrize(
    "message",
    [
        result() | {"error": {"code": "probe_failed", "message": "x", "retryable": False}},
        result() | {"status": "error"},
    ],
    ids=["completed-with-error-block", "error-without-error-block"],
)
def test_status_and_error_block_mismatch_fails(message: dict[str, Any]) -> None:
    with pytest.raises(ValidationError):
        MediaResult.model_validate(message)


def test_url_input_fails() -> None:
    message = job() | {"inputs": [{"key": "source", "url": "https://example.com/clip.mp4"}]}
    with pytest.raises(ValidationError):
        MediaJob.model_validate(message)


def test_progress_kind_outside_started_or_heartbeat_fails() -> None:
    message = progress() | {"kind": "finished"}
    with pytest.raises(ValidationError):
        MediaProgress.model_validate(message)


@pytest.mark.parametrize(
    "change",
    [
        {"stepOrder": "1"},
        {"attempt": 0},
        {"schemaVersion": "2"},
        {"issuedAt": "2026-09-30T12:00:00"},
        {"outputPrefix": "/etc/"},
        {"outputPrefix": f"runs/{RUN_ID}/steps/0/"},
    ],
    ids=[
        "string-for-integer",
        "attempt-below-1",
        "schema-version",
        "naive-timestamp",
        "output-prefix-outside-runs",
        "output-prefix-step-0",
    ],
)
def test_job_values_outside_the_schema_fail(change: dict[str, Any]) -> None:
    payload = job() | change

    with pytest.raises(ValidationError):
        MediaJob.model_validate(payload)
