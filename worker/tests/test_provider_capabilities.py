from __future__ import annotations

import io
import json
import wave
from typing import Any
from unittest.mock import MagicMock

import pytest
from pydantic import ValidationError

from flowcortex_worker.provider.adapter import (
    FixtureProviderAdapter,
    SpeechIdentity,
    SpeechRequest,
)
from flowcortex_worker.provider.capabilities import (
    ExecutionContext,
    _speech_idempotency_key,
    execute,
)
from flowcortex_worker.provider.contracts.provider_job import GenerateSpeechJob, ProviderJob
from flowcortex_worker.provider.contracts.provider_script_result import ProviderScriptData
from flowcortex_worker.provider.contracts.provider_speech_result import ProviderSpeechData
from flowcortex_worker.provider.contracts.provider_storyboard_result import ProviderStoryboardData

JOB_ID = "5f0c7c1e-8f7e-4a53-9a38-2f6d6f3f9c11"
RUN_ID = "0b7e1d2c-3a4b-4c5d-8e6f-7a8b9c0d1e2f"
REVISION_ID = "a71ea3ba-d72a-4c2c-985c-3a7fd98853c4"
BUCKET = "flowcortex-media"


def _storage() -> Any:
    return MagicMock()


def _empty_context() -> ExecutionContext:
    storage = MagicMock()
    storage.head_object.return_value = None
    storage.fetch_bytes.return_value = b""
    return ExecutionContext(
        storage=storage,
        bucket=BUCKET,
        output_prefix=f"runs/{RUN_ID}/steps/3/attempts/1/",
        abort=MagicMock(),
    )


def _wav(duration_ms: int) -> bytes:
    output = io.BytesIO()
    with wave.open(output, "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(16_000)
        audio.writeframes(b"\0\0" * (duration_ms * 16))
    return output.getvalue()


def job(capability: str, profile: str, payload: dict[str, Any]) -> dict[str, Any]:
    return {
        "schemaVersion": "1",
        "jobId": JOB_ID,
        "runId": RUN_ID,
        "correlationId": RUN_ID,
        "stepOrder": 1,
        "capability": capability,
        "params": {"profile": profile, "payload": payload},
        "outputPrefix": f"runs/{RUN_ID}/steps/1/attempts/1/",
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
        _empty_context(),
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
        _empty_context(),
    )

    assert "Giá: 299.000đ" in outcome.data["script"]
    assert "Ưu đãi: Giảm 10% hôm nay" in outcome.data["script"]


def test_product_script_fetches_and_passes_reference_image() -> None:
    request = job(
        "generate_script",
        "product_ad_v1",
        {"productName": "Bình giữ nhiệt", "description": "Giữ đồ uống tiện lợi"},
    )
    request["inputs"] = [
        {"key": "image_1", "bucket": "flowcortex-media", "objectKey": "runs/r/inputs/image_1"}
    ]
    storage = MagicMock()
    image = b"\x89PNG\r\n\x1a\nproduct"
    storage.fetch_bytes.return_value = image
    adapter = MagicMock()
    adapter.generate_script.return_value = "Kịch bản có hình ảnh"

    execute(
        ProviderJob.model_validate(request),
        adapter,
        ExecutionContext(storage, BUCKET, f"runs/{RUN_ID}/steps/1/attempts/1/", MagicMock()),
    )

    adapter.generate_script.assert_called_once()
    assert adapter.generate_script.call_args.args[1] == image


def test_storyboard_creates_two_typed_scenes_from_approved_revision() -> None:
    outcome = execute(
        ProviderJob.model_validate(
            job(
                "generate_storyboard",
                "product_ad_storyboard_v1",
                {"script": "Approved script", "sourceRevisionId": REVISION_ID},
            )
        ),
        FixtureProviderAdapter(),
        _empty_context(),
    )

    ProviderStoryboardData.model_validate(outcome.data)
    assert outcome.data["sourceRevisionId"] == REVISION_ID
    assert outcome.data["scene1Prompt"] != outcome.data["scene2Prompt"]


def test_speech_is_typed_wav_between_fifteen_and_thirty_seconds() -> None:
    outcome = execute(
        ProviderJob.model_validate(
            job(
                "generate_speech",
                "vi_voice_v1",
                {"script": "Mua ngay hôm nay", "sourceRevisionId": REVISION_ID},
            )
        ),
        FixtureProviderAdapter(),
        _empty_context(),
    )

    ProviderSpeechData.model_validate(outcome.data)
    assert 15_000 <= outcome.data["durationMs"] <= 30_000
    assert len(outcome.files) == 2
    assert outcome.data["sourceRevisionId"] == REVISION_ID
    assert outcome.data["provider"] == "fixture"
    assert len(outcome.data["idempotencyKey"]) == 64
    with wave.open(io.BytesIO(outcome.files[0].body), "rb") as wav:
        assert wav.getnchannels() == 1
        assert wav.getframerate() == 16_000


def test_speech_generation_receives_the_job_kill_switch() -> None:
    adapter = MagicMock(wraps=FixtureProviderAdapter())
    context = _empty_context()

    execute(
        ProviderJob.model_validate(
            job(
                "generate_speech",
                "vi_voice_v1",
                {"script": "Mua ngay hôm nay", "sourceRevisionId": REVISION_ID},
            )
        ),
        adapter,
        context,
    )

    [(_, abort)] = [call.args for call in adapter.generate_speech.call_args_list]
    assert abort is context.abort


def test_speech_reuses_existing_artifact_when_manifest_matches() -> None:
    storage = MagicMock()
    request = ProviderJob.model_validate(
        job(
            "generate_speech",
            "vi_voice_v1",
            {"script": "Mua ngay hôm nay", "sourceRevisionId": REVISION_ID},
        )
    )
    assert isinstance(request.root, GenerateSpeechJob)
    stored_wav = _wav(17_500)
    identity = SpeechIdentity("fixture", "silence", "+0%", 16_000)
    stored_manifest = {
        "profile": "vi_voice_v1",
        "durationMs": 17_500,
        "provider": identity.provider,
        "voice": identity.voice,
        "speed": "+0%",
        "sourceRevisionId": REVISION_ID,
        "idempotencyKey": _speech_idempotency_key(
            SpeechRequest("Mua ngay hôm nay", request.root.params.payload.sourceRevisionId),
            identity,
        ),
        "format": "wav",
    }
    storage.head_object.side_effect = [{}, {}]
    storage.fetch_bytes.side_effect = [
        json.dumps(stored_manifest, ensure_ascii=False, sort_keys=True).encode("utf-8"),
        stored_wav,
    ]

    adapter = MagicMock()
    adapter.speech_identity.return_value = identity

    outcome = execute(
        request,
        adapter,
        ExecutionContext(
            storage=storage,
            bucket=BUCKET,
            output_prefix=f"runs/{RUN_ID}/steps/3/attempts/1/",
            abort=MagicMock(),
        ),
    )

    ProviderSpeechData.model_validate(outcome.data)
    assert outcome.data == stored_manifest
    assert outcome.files[0].body == stored_wav
    assert all(file.stored for file in outcome.files)
    assert outcome.files[1].body.decode() == json.dumps(
        stored_manifest, ensure_ascii=False, sort_keys=True
    )
    adapter.generate_speech.assert_not_called()


def test_speech_regenerates_when_manifest_revision_mismatches() -> None:
    storage = MagicMock()
    storage.head_object.side_effect = [{}, {}]
    storage.fetch_bytes.side_effect = [
        json.dumps(
            {
                "profile": "vi_voice_v1",
                "durationMs": 17_500,
                "provider": "edge",
                "voice": "vi-VN-HoaiMyNeural",
                "speed": "+0%",
                "sourceRevisionId": "11111111-1111-1111-1111-111111111111",
                "idempotencyKey": "b" * 64,
                "format": "wav",
            }
        ).encode("utf-8"),
        b"stale",
    ]

    adapter = FixtureProviderAdapter()
    outcome = execute(
        ProviderJob.model_validate(
            job(
                "generate_speech",
                "vi_voice_v1",
                {"script": "Mua ngay hôm nay", "sourceRevisionId": REVISION_ID},
            )
        ),
        adapter,
        ExecutionContext(
            storage=storage,
            bucket=BUCKET,
            output_prefix=f"runs/{RUN_ID}/steps/3/attempts/1/",
            abort=MagicMock(),
        ),
    )

    ProviderSpeechData.model_validate(outcome.data)
    assert outcome.data["sourceRevisionId"] == REVISION_ID
    assert storage.fetch_bytes.call_count == 1


def test_speech_regenerates_when_manifest_missing() -> None:
    storage = MagicMock()
    storage.head_object.side_effect = [None]

    outcome = execute(
        ProviderJob.model_validate(
            job(
                "generate_speech",
                "vi_voice_v1",
                {"script": "Mua ngay hôm nay", "sourceRevisionId": REVISION_ID},
            )
        ),
        FixtureProviderAdapter(),
        ExecutionContext(
            storage=storage,
            bucket=BUCKET,
            output_prefix=f"runs/{RUN_ID}/steps/3/attempts/1/",
            abort=MagicMock(),
        ),
    )

    ProviderSpeechData.model_validate(outcome.data)
    assert outcome.data["sourceRevisionId"] == REVISION_ID
    storage.fetch_bytes.assert_not_called()


def test_speech_regenerates_when_idempotency_key_mismatches() -> None:
    storage = MagicMock()
    storage.head_object.side_effect = [{}, {}]
    storage.fetch_bytes.return_value = json.dumps({
        "profile": "vi_voice_v1",
        "durationMs": 17_500,
        "provider": "edge",
        "voice": "vi-VN-OldVoice",
        "speed": "+0%",
        "sourceRevisionId": REVISION_ID,
        "idempotencyKey": "b" * 64,
        "format": "wav",
    }).encode()

    outcome = execute(
        ProviderJob.model_validate(
            job(
                "generate_speech",
                "vi_voice_v1",
                {"script": "Mua ngay hôm nay", "sourceRevisionId": REVISION_ID},
            )
        ),
        FixtureProviderAdapter(),
        ExecutionContext(
            storage=storage,
            bucket=BUCKET,
            output_prefix=f"runs/{RUN_ID}/steps/3/attempts/1/",
            abort=MagicMock(),
        ),
    )

    assert outcome.data["provider"] == "fixture"
    assert storage.fetch_bytes.call_count == 1


def test_speech_regenerates_when_stored_manifest_is_invalid() -> None:
    storage = MagicMock()
    storage.head_object.side_effect = [{}, {}]
    storage.fetch_bytes.return_value = b"not-json"

    outcome = execute(
        ProviderJob.model_validate(
            job(
                "generate_speech",
                "vi_voice_v1",
                {"script": "Mua ngay hôm nay", "sourceRevisionId": REVISION_ID},
            )
        ),
        FixtureProviderAdapter(),
        ExecutionContext(
            storage=storage,
            bucket=BUCKET,
            output_prefix=f"runs/{RUN_ID}/steps/3/attempts/1/",
            abort=MagicMock(),
        ),
    )

    assert outcome.data["provider"] == "fixture"


@pytest.mark.parametrize(
    "message",
    [
        job(
            "generate_script",
            "product_ad_v2",
            {"productName": "A", "description": "B", "cta": "C"},
        ),
        job(
            "generate_speech",
            "vi_voice_v1",
            {"script": "", "sourceRevisionId": REVISION_ID},
        ),
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
