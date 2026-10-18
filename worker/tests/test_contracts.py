"""The generated contract models accept reference messages and reject what the schemas forbid."""

import json
from typing import Any

import pytest
from conftest import CONTRACTS
from pydantic import ValidationError

from flowcortex_worker.contracts.media_job import MediaJob
from flowcortex_worker.contracts.media_progress import MediaProgress
from flowcortex_worker.contracts.media_result import MediaResult
from flowcortex_worker.provider.contracts.provider_job import ProviderJob
from flowcortex_worker.provider.contracts.provider_script_result import ProviderScriptData
from flowcortex_worker.provider.contracts.provider_speech_result import ProviderSpeechData

JOB_ID = "5f0c7c1e-8f7e-4a53-9a38-2f6d6f3f9c11"
RUN_ID = "0b7e1d2c-3a4b-4c5d-8e6f-7a8b9c0d1e2f"
REVISION_ID = "a71ea3ba-d72a-4c2c-985c-3a7fd98853c4"
IDEMPOTENCY_KEY = "a" * 64
AT = "2026-09-30T12:00:00Z"
ATTEMPT_PREFIX = f"runs/{RUN_ID}/steps/1/attempts/1/"


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


def provider_job(capability: str) -> dict[str, Any]:
    params = (
        {
            "profile": "product_ad_v1",
            "payload": {
                "productName": "Máy lọc nước",
                "description": "Lọc sạch nước đầu",
                "cta": "Mua ngay",
            },
        }
        if capability == "generate_script"
        else {
            "profile": "vi_voice_v1",
            "payload": {"script": "Mua máy lọc nước ngay", "sourceRevisionId": REVISION_ID},
        }
    )
    return {
        "schemaVersion": "1",
        "jobId": JOB_ID,
        "runId": RUN_ID,
        "correlationId": RUN_ID,
        "stepOrder": 1,
        "capability": capability,
        "params": params,
        "outputPrefix": ATTEMPT_PREFIX,
        "attempt": 1,
        "maxRuntimeSeconds": 120,
        "issuedAt": AT,
    }


def provider_result_data() -> dict[str, Any]:
    return {"profile": "product_ad_v1", "language": "vi", "script": "Mua máy lọc nước ngay"}


def provider_speech_data() -> dict[str, Any]:
    return {
        "profile": "vi_voice_v1",
        "durationMs": 4200,
        "provider": "edge",
        "voice": "vi-VN-HoaiMyNeural",
        "speed": "+0%",
        "sourceRevisionId": REVISION_ID,
        "idempotencyKey": IDEMPOTENCY_KEY,
        "format": "wav",
    }


def test_reference_messages_validate() -> None:
    MediaJob.model_validate(job())
    MediaResult.model_validate(result())
    MediaProgress.model_validate(progress())


def test_provider_jobs_and_result_data_validate() -> None:
    ProviderJob.model_validate(provider_job("generate_script"))
    ProviderJob.model_validate(provider_job("generate_speech"))
    ProviderScriptData.model_validate(provider_result_data())
    ProviderSpeechData.model_validate(provider_speech_data())


@pytest.mark.parametrize(
    ("capability", "change"),
    [
        ("generate_script", {"params": {"profile": "product_ad_v1"}}),
        ("generate_speech", {"params": {"profile": "product_ad_v1", "payload": {"script": "x"}}}),
        (
            "generate_speech",
            {"params": {"profile": "vi_voice_v1", "payload": {"script": "x"}}},
        ),
        (
            "generate_script",
            {"params": {"profile": "product_ad_v1", "payload": {"cta": "Mua ngay"}}},
        ),
        ("generate_script", {"capability": "generate_speech"}),
        (
            "generate_script",
            {
                "params": {
                    "profile": "product_ad_v1",
                    "payload": {"productName": "x" * 201, "description": "d", "cta": "c"},
                    "extra": 1,
                }
            },
        ),
        ("generate_script", {"outputPrefix": f"runs/{RUN_ID}/steps/1/"}),
        ("generate_script", {"outputPrefix": "/etc/"}),
        ("generate_script", {"attempt": 0}),
    ],
    ids=[
        "script-params-missing-payload",
        "speech-params-wrong-profile",
        "speech-payload-missing-revision",
        "script-payload-missing-required",
        "capability-does-not-match-params",
        "unknown-key-in-params",
        "output-prefix-without-attempts",
        "output-prefix-outside-runs",
        "attempt-below-1",
    ],
)
def test_provider_job_values_outside_the_schema_fail(
    capability: str, change: dict[str, Any]
) -> None:
    with pytest.raises(ValidationError):
        ProviderJob.model_validate(provider_job(capability) | change)


@pytest.mark.parametrize(
    ("change", "model"),
    [
        ({"language": "fr"}, ProviderScriptData),
        ({"profile": "other"}, ProviderScriptData),
        ({"script": "x" * 9000}, ProviderScriptData),
        ({"unexpected": "value"}, ProviderScriptData),
        (provider_speech_data() | {"durationMs": 0}, ProviderSpeechData),
        (provider_speech_data() | {"profile": "other"}, ProviderSpeechData),
    ],
    ids=[
        "language-not-vi",
        "script-profile-wrong",
        "script-over-8000",
        "unknown-property",
        "speech-duration-below-1",
        "speech-profile-wrong",
    ],
)
def test_provider_result_data_outside_the_schema_fails(
    change: dict[str, Any], model: type[ProviderScriptData | ProviderSpeechData]
) -> None:
    with pytest.raises(ValidationError):
        model.model_validate(provider_result_data() | change)


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


def test_extract_audio_contract_values_validate() -> None:
    MediaJob.model_validate(job() | {"capability": "extract_audio"})
    MediaResult.model_validate(
        result()
        | {
            "status": "error",
            "outputs": [],
            "error": {
                "code": "audio_extraction_failed",
                "message": "no audio",
                "retryable": False,
            },
        }
    )


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


def test_transient_budget_matches_the_broker_delivery_limit() -> None:
    from flowcortex_worker.provider import __main__ as provider_main

    limits = json.loads((CONTRACTS / "limits.json").read_text())

    assert limits["providerJobDeliveryLimit"] == provider_main.MAX_TRANSIENT_REDELIVERIES


def test_render_target_matches_the_shared_video_duration() -> None:
    from flowcortex_worker.handlers import render_video

    limits = json.loads((CONTRACTS / "limits.json").read_text())

    assert limits["productVideoDurationSeconds"] == render_video.TARGET_DURATION_SECONDS


def test_fixture_speech_fits_the_video_for_the_longest_script() -> None:
    from unittest.mock import MagicMock
    from uuid import uuid4

    from flowcortex_worker.provider.adapter import FixtureProviderAdapter, SpeechRequest

    limits = json.loads((CONTRACTS / "limits.json").read_text())
    speech = FixtureProviderAdapter().generate_speech(
        SpeechRequest("a" * 8000, uuid4()), MagicMock()
    )

    assert speech.duration_ms <= limits["productVideoDurationSeconds"] * 1000
