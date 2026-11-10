from __future__ import annotations

import io
import json
import wave
from typing import Any
from unittest.mock import MagicMock
from uuid import UUID

import pytest
from pydantic import ValidationError

from flowcortex_worker.contracts.media_progress import VendorTaskReport
from flowcortex_worker.errors import PermanentError
from flowcortex_worker.provider.adapter import (
    FixtureProviderAdapter,
    SpeechIdentity,
    SpeechRequest,
    VendorProbe,
    VideoArtifact,
    VideoIdentity,
    VideoRequest,
)
from flowcortex_worker.provider.capabilities import (
    ExecutionContext,
    _speech_idempotency_key,
    _video_idempotency_key,
    execute,
)
from flowcortex_worker.provider.contracts.provider_job import GenerateSpeechJob, ProviderJob
from flowcortex_worker.provider.contracts.provider_script_result import ProviderScriptData
from flowcortex_worker.provider.contracts.provider_speech_result import ProviderSpeechData
from flowcortex_worker.provider.contracts.provider_storyboard_result import ProviderStoryboardData
from flowcortex_worker.provider.contracts.provider_video_result import ProviderVideoData
from flowcortex_worker.provider.vendor_jobs import (
    VendorJobEnded,
    VendorJobTimeout,
    VendorResultGone,
    vendor_fingerprint,
    vendor_scope,
)

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


def test_storyboard_fetches_and_passes_reference_image() -> None:
    request = job(
        "generate_storyboard",
        "product_ad_storyboard_v1",
        {"script": "Approved script", "sourceRevisionId": REVISION_ID},
    )
    request["inputs"] = [
        {"key": "image_1", "bucket": "flowcortex-media", "objectKey": "runs/r/inputs/image_1"}
    ]
    storage = MagicMock()
    image = b"\x89PNG\r\n\x1a\nproduct"
    storage.fetch_bytes.return_value = image
    adapter = MagicMock()
    adapter.generate_storyboard.return_value = MagicMock(
        scene_1_prompt="Scene 1", scene_2_prompt="Scene 2"
    )

    execute(
        ProviderJob.model_validate(request),
        adapter,
        ExecutionContext(storage, BUCKET, f"runs/{RUN_ID}/steps/3/attempts/1/", MagicMock()),
    )

    adapter.generate_storyboard.assert_called_once()
    assert adapter.generate_storyboard.call_args.kwargs.get("reference_image") == image


def test_speech_is_typed_wav_that_fits_the_ten_second_video() -> None:
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
    assert 4_000 <= outcome.data["durationMs"] <= 10_000
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


# --- vendor job ledger ---------------------------------------------------------------------

REQUEST_KEY = "c" * 64
LEDGER_ROW = "d6a5f6f0-6b3c-4f43-9c0e-1b2a3c4d5e6f"
VIDEO_PROMPT = "A cap rotating on a pedestal"


class LedgerVideoAdapter:
    def __init__(self, events: list[str], failures: dict[str, Exception] | None = None) -> None:
        self.events = events
        self.failures = failures or {}
        self.submitted: list[str] = []
        self.awaited: list[str] = []

    def video_identity(self) -> VideoIdentity:
        return VideoIdentity("seedance", "seedance-1.5-pro", 5, "9:16", "480p", False)

    def vendor_scope(self) -> dict[str, str]:
        return vendor_scope("https://dashscope.aliyuncs.com/api/v1", "sk-test")

    def submit_video(self, request: VideoRequest) -> str:
        task_id = f"new-{len(self.submitted) + 1}"
        self.submitted.append(task_id)
        self.events.append(f"submit:{task_id}")
        return task_id

    def probe_video(self, task_id: str) -> VendorProbe:
        raise AssertionError("probes come from the context registry")

    def await_video(self, task_id: str, abort: Any) -> VideoArtifact:
        self.events.append(f"await:{task_id}")
        self.awaited.append(task_id)
        if task_id in self.failures:
            raise self.failures[task_id]
        return VideoArtifact(b"\0\0\0\x20ftypisom" + b"\0" * 32, task_id)


def video_fingerprint(adapter: LedgerVideoAdapter) -> str:
    return vendor_fingerprint(
        adapter.video_identity(),
        adapter.vendor_scope(),
        VideoRequest(VIDEO_PROMPT, UUID(REVISION_ID)),
    )


def video_job(candidates: list[dict[str, Any]] | None) -> ProviderJob:
    request = job(
        "generate_video",
        "vertical_ad_clip_v1",
        {"prompt": VIDEO_PROMPT, "sourceRevisionId": REVISION_ID},
    )
    if candidates is not None:
        request["vendorLedger"] = {"requestKey": REQUEST_KEY, "candidates": candidates}
    return ProviderJob.model_validate(request)


def row(adapter: LedgerVideoAdapter, job_id: str = "cgt-1", **change: Any) -> dict[str, Any]:
    return {
        "vendorTaskId": LEDGER_ROW,
        "vendor": "seedance",
        "model": "seedance-1.5-pro",
        "fingerprint": video_fingerprint(adapter),
        "vendorJobId": job_id,
        "handle": {},
    } | change


def video_context(
    events: list[str], probes: dict[str, Any] | None = None, storage: Any = None
) -> tuple[ExecutionContext, list[VendorTaskReport]]:
    reports: list[VendorTaskReport] = []

    def report(item: VendorTaskReport) -> None:
        events.append(f"report:{item.status}:{item.vendorTaskId or 'new'}")
        reports.append(item)

    store = storage or MagicMock()
    if storage is None:
        store.head_object.return_value = None
    context = ExecutionContext(
        store,
        BUCKET,
        f"runs/{RUN_ID}/steps/1/attempts/1/",
        MagicMock(),
        report=report,
        probes=probes if probes is not None else {"seedance": lambda task_id, handle: "running"},
    )
    return context, reports


@pytest.fixture
def skip_ffmpeg(monkeypatch: pytest.MonkeyPatch) -> None:
    from flowcortex_worker.provider import capabilities

    monkeypatch.setattr(capabilities, "_normalize_video", lambda body, context: body)


@pytest.mark.usefixtures("skip_ffmpeg")
def test_an_attachable_candidate_is_waited_on_instead_of_submitting() -> None:
    events: list[str] = []
    adapter = LedgerVideoAdapter(events)
    context, reports = video_context(events)

    outcome = execute(video_job([row(adapter)]), adapter, context)

    assert adapter.submitted == []
    assert adapter.awaited == ["cgt-1"]
    assert reports == []
    ProviderVideoData.model_validate(outcome.data)
    assert outcome.data["providerTaskId"] == "cgt-1"
    assert outcome.data["fingerprint"] == video_fingerprint(adapter)
    assert outcome.data["requestKey"] == REQUEST_KEY


@pytest.mark.usefixtures("skip_ffmpeg")
def test_without_a_qualifying_candidate_the_new_job_is_recorded_before_the_wait() -> None:
    events: list[str] = []
    adapter = LedgerVideoAdapter(events)
    context, reports = video_context(events)
    stale = row(adapter, fingerprint="e" * 64)

    outcome = execute(video_job([stale]), adapter, context)

    assert events == ["submit:new-1", "report:running:new", "await:new-1"]
    [report] = reports
    assert report.fingerprint == video_fingerprint(adapter)
    assert report.requestKey == REQUEST_KEY
    assert report.vendorJobId == "new-1"
    assert report.handle == {}
    assert report.vendorCreatedAt is not None
    assert outcome.data["requestKey"] == REQUEST_KEY


@pytest.mark.usefixtures("skip_ffmpeg")
def test_a_job_the_vendor_ended_is_reported_before_the_step_fails() -> None:
    events: list[str] = []
    adapter = LedgerVideoAdapter(
        events, {"cgt-1": VendorJobEnded("expired", "Seedance task ended with status expired")}
    )
    context, reports = video_context(events)

    with pytest.raises(PermanentError) as failure:
        execute(video_job([row(adapter)]), adapter, context)

    assert failure.value.code == "provider_request_failed"
    assert [(r.vendorTaskId, r.status) for r in reports] == [(UUID(LEDGER_ROW), "expired")]
    assert adapter.submitted == []


@pytest.mark.usefixtures("skip_ffmpeg")
def test_a_new_job_the_vendor_ended_is_reported_by_its_vendor_job_id() -> None:
    events: list[str] = []
    adapter = LedgerVideoAdapter(
        events, {"new-1": VendorJobEnded("failed", "Seedance task ended with status failed")}
    )
    context, reports = video_context(events)

    with pytest.raises(PermanentError) as failure:
        execute(video_job([]), adapter, context)

    assert failure.value.code == "provider_request_failed"
    assert events == ["submit:new-1", "report:running:new", "await:new-1", "report:failed:new"]
    verdict = reports[-1]
    assert (verdict.vendor, verdict.vendorJobId, verdict.vendorTaskId) == ("seedance", "new-1", None)
    assert verdict.fingerprint is None


@pytest.mark.usefixtures("skip_ffmpeg")
def test_a_job_without_a_ledger_that_the_vendor_ended_reports_nothing() -> None:
    events: list[str] = []
    adapter = LedgerVideoAdapter(events, {"new-1": VendorJobEnded("failed", "ended")})
    context, reports = video_context(events)

    with pytest.raises(PermanentError):
        execute(video_job(None), adapter, context)

    assert reports == []


@pytest.mark.usefixtures("skip_ffmpeg")
def test_an_attached_clip_that_is_gone_is_replaced_in_the_same_execution() -> None:
    events: list[str] = []
    adapter = LedgerVideoAdapter(events, {"cgt-1": VendorResultGone("clip answered 404")})
    context, reports = video_context(events)

    outcome = execute(video_job([row(adapter)]), adapter, context)

    assert events == [
        "await:cgt-1",
        f"report:gone:{LEDGER_ROW}",
        "submit:new-1",
        "report:running:new",
        "await:new-1",
    ]
    assert [r.status for r in reports] == ["gone", "running"]
    assert outcome.data["providerTaskId"] == "new-1"


@pytest.mark.usefixtures("skip_ffmpeg")
def test_a_replacement_that_is_gone_too_is_not_replaced_again() -> None:
    events: list[str] = []
    adapter = LedgerVideoAdapter(
        events,
        {"cgt-1": VendorResultGone("gone"), "new-1": VendorResultGone("gone again")},
    )
    context, _ = video_context(events)

    with pytest.raises(VendorResultGone):
        execute(video_job([row(adapter)]), adapter, context)

    assert adapter.submitted == ["new-1"]


@pytest.mark.usefixtures("skip_ffmpeg")
def test_a_wait_budget_that_ends_on_an_attached_job_names_its_ledger_row() -> None:
    events: list[str] = []
    adapter = LedgerVideoAdapter(events, {"cgt-1": VendorJobTimeout("seedance", "cgt-1")})
    context, reports = video_context(events)

    with pytest.raises(VendorJobTimeout) as timeout:
        execute(video_job([row(adapter)]), adapter, context)

    assert LEDGER_ROW in str(timeout.value)
    assert reports == []


@pytest.mark.usefixtures("skip_ffmpeg")
def test_a_job_without_a_ledger_runs_as_before() -> None:
    events: list[str] = []
    adapter = LedgerVideoAdapter(events)
    context, reports = video_context(events)

    outcome = execute(video_job(None), adapter, context)

    assert events == ["submit:new-1", "await:new-1"]
    assert reports == []
    assert "fingerprint" not in outcome.data
    assert "requestKey" not in outcome.data
    ProviderVideoData.model_validate(outcome.data)


@pytest.mark.usefixtures("skip_ffmpeg")
def test_a_checkpoint_from_this_attempt_skips_the_ledger_and_the_submit() -> None:
    events: list[str] = []
    adapter = LedgerVideoAdapter(events)
    probed: list[str] = []

    def probe(task_id: str, handle: dict[str, Any]) -> VendorProbe:
        probed.append(task_id)
        return "running"

    request = VideoRequest(VIDEO_PROMPT, UUID(REVISION_ID))
    checkpoint = {
        "idempotencyKey": _video_idempotency_key(request, adapter.video_identity()),
        "model": "seedance-1.5-pro",
        "provider": "seedance",
        "providerTaskId": "checkpointed",
    }
    storage = MagicMock()
    storage.head_object.return_value = {}
    storage.fetch_bytes.return_value = json.dumps(checkpoint).encode()
    context, reports = video_context(events, {"seedance": probe}, storage)

    outcome = execute(video_job([row(adapter)]), adapter, context)

    assert events == ["await:checkpointed"]
    assert probed == [] and reports == []
    assert outcome.data["providerTaskId"] == "checkpointed"
    assert outcome.data["requestKey"] == REQUEST_KEY
