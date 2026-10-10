from __future__ import annotations

import io
import wave
from typing import Any

import pytest
from pydantic import ValidationError

from flowcortex_provider_worker.adapter import FixtureProviderAdapter
from flowcortex_provider_worker.capabilities import execute
from flowcortex_provider_worker.contracts.provider_job import ProviderJob
from flowcortex_provider_worker.contracts.provider_script_result import ProviderScriptData
from flowcortex_provider_worker.contracts.provider_speech_result import ProviderSpeechData

JOB_ID = "5f0c7c1e-8f7e-4a53-9a38-2f6d6f3f9c11"
RUN_ID = "0b7e1d2c-3a4b-4c5d-8e6f-7a8b9c0d1e2f"


def job(capability: str, profile: str, payload: dict[str, Any]) -> dict[str, Any]:
    return {
        "schemaVersion": "1",
        "jobId": JOB_ID,
        "runId": RUN_ID,
        "correlationId": RUN_ID,
        "stepOrder": 1,
        "capability": capability,
        "params": {"profile": profile, "payload": payload},
        "outputPrefix": f"runs/{RUN_ID}/steps/1/",
        "attempt": 1,
        "maxRuntimeSeconds": 30,
        "issuedAt": "2026-10-10T00:00:00Z",
    }


def test_product_script_uses_only_confirmed_optional_claims() -> None:
    payload = {
        "productName": "Bình giữ nhiệt",
        "description": "Giữ đồ uống tiện lợi",
        "cta": "Đặt mua ngay",
    }
    outcome = execute(
        ProviderJob.model_validate(job("generate_script", "product_ad_v1", payload)),
        FixtureProviderAdapter(),
    )

    ProviderScriptData.model_validate(outcome.data)
    assert "Bình giữ nhiệt" in outcome.data["script"]
    assert "Giá:" not in outcome.data["script"]
    assert "Ưu đãi:" not in outcome.data["script"]


def test_product_script_includes_confirmed_price_and_promotion() -> None:
    payload = {
        "productName": "Bình giữ nhiệt",
        "description": "Giữ đồ uống tiện lợi",
        "cta": "Đặt mua ngay",
        "price": "299.000đ",
        "promotion": "Giảm 10% hôm nay",
    }
    outcome = execute(
        ProviderJob.model_validate(job("generate_script", "product_ad_v1", payload)),
        FixtureProviderAdapter(),
    )

    assert "Giá: 299.000đ" in outcome.data["script"]
    assert "Ưu đãi: Giảm 10% hôm nay" in outcome.data["script"]


def test_speech_is_typed_wav_between_fifteen_and_thirty_seconds() -> None:
    outcome = execute(
        ProviderJob.model_validate(
            job("generate_speech", "vi_voice_v1", {"script": "Mua ngay hôm nay"})
        ),
        FixtureProviderAdapter(),
    )

    ProviderSpeechData.model_validate(outcome.data)
    assert 15_000 <= outcome.data["durationMs"] <= 30_000
    assert len(outcome.files) == 1
    with wave.open(io.BytesIO(outcome.files[0].body), "rb") as wav:
        assert wav.getnchannels() == 1
        assert wav.getframerate() == 16_000


@pytest.mark.parametrize(
    "message",
    [
        job(
            "generate_script",
            "product_ad_v2",
            {"productName": "A", "description": "B", "cta": "C"},
        ),
        job("generate_speech", "vi_voice_v1", {"script": ""}),
        job(
            "generate_script",
            "product_ad_v1",
            {"productName": "A", "description": "B", "cta": "C", "claim": "X"},
        ),
    ],
)
def test_contract_rejects_invalid_provider_payload(message: dict[str, Any]) -> None:
    with pytest.raises(ValidationError):
        ProviderJob.model_validate(message)
