"""The files the worker records equal the entries of contracts/step-file-outputs.json."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest
from test_provider_capabilities import (
    LedgerVideoAdapter,
    _empty_context,
    job,
    video_context,
    video_job,
)

from flowcortex_worker.handlers import (
    align_audio,
    caption_burn,
    extract_audio,
    fetch_video,
    probe,
    render_video,
)
from flowcortex_worker.provider import capabilities
from flowcortex_worker.provider.adapter import FixtureProviderAdapter
from flowcortex_worker.provider.capabilities import execute
from flowcortex_worker.provider.contracts.provider_job import ProviderJob

CONTRACT = Path(__file__).resolve().parents[2] / "contracts" / "step-file-outputs.json"
REVISION_ID = "a71ea3ba-d72a-4c2c-985c-3a7fd98853c4"


def _declared(step_type: str) -> set[str]:
    types = json.loads(CONTRACT.read_text(encoding="utf-8"))["types"]
    assert step_type in types, f"{step_type} has no entry in contracts/step-file-outputs.json"
    return set(types[step_type])


@pytest.mark.parametrize(
    ("step_type", "written"),
    [
        ("probe", {probe.REPORT_NAME}),
        ("fetch_video", {fetch_video.VIDEO_OUTPUT, fetch_video.AUDIO_OUTPUT}),
        ("caption_burn", {caption_burn.VIDEO_NAME, caption_burn.PREVIEW_NAME}),
        ("extract_audio", {extract_audio.OUTPUT_NAME}),
        ("align_audio", {align_audio.OUTPUT_NAME, align_audio.MANIFEST_NAME}),
        ("render_video", {render_video.VIDEO_NAME, render_video.MANIFEST_NAME}),
    ],
    ids=lambda value: value if isinstance(value, str) else "names",
)
def test_a_media_handler_writes_the_files_its_type_declares(
    step_type: str, written: set[str]
) -> None:
    assert written == _declared(step_type), f"{step_type} writes different files than declared"


def _file_names(outcome: capabilities.ProviderOutcome) -> set[str]:
    return {file.name for file in outcome.files}


def _provider_job(capability: str, profile: str, payload: dict[str, Any]) -> ProviderJob:
    return ProviderJob.model_validate(job(capability, profile, payload))


def test_generate_speech_writes_the_files_its_type_declares() -> None:
    outcome = execute(
        _provider_job(
            "generate_speech",
            "vi_voice_v1",
            {"script": "Mua ngay hôm nay", "sourceRevisionId": REVISION_ID},
        ),
        FixtureProviderAdapter(),
        _empty_context(),
    )

    assert _file_names(outcome) == _declared("generate_speech")


def test_generate_video_writes_the_files_its_type_declares(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(capabilities, "_normalize_video", lambda body, context: body)
    events: list[str] = []
    context, _ = video_context(events)

    outcome = execute(video_job(None), LedgerVideoAdapter(events), context)

    assert _file_names(outcome) == _declared("generate_video")


def test_generate_script_writes_the_files_its_type_declares() -> None:
    outcome = execute(
        _provider_job(
            "generate_script",
            "product_ad_v1",
            {"productName": "Bình giữ nhiệt", "description": "Giữ đồ uống tiện lợi"},
        ),
        FixtureProviderAdapter(),
        _empty_context(),
    )

    assert _file_names(outcome) == _declared("generate_script")


def test_generate_storyboard_writes_the_files_its_type_declares() -> None:
    outcome = execute(
        _provider_job(
            "generate_storyboard",
            "product_ad_storyboard_v1",
            {"script": "Approved script", "sourceRevisionId": REVISION_ID},
        ),
        FixtureProviderAdapter(),
        _empty_context(),
    )

    assert _file_names(outcome) == _declared("generate_storyboard")
