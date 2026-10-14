from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from .adapter import ProviderAdapter
from .contracts.provider_job import GenerateScriptJob, GenerateSpeechJob, ProviderJob


class UnsupportedCapability(ValueError):
    pass


@dataclass(frozen=True)
class ProviderFile:
    name: str
    mime: str
    body: bytes


@dataclass(frozen=True)
class ProviderOutcome:
    data: dict[str, Any]
    files: tuple[ProviderFile, ...] = ()


Handler = Callable[[GenerateScriptJob | GenerateSpeechJob, ProviderAdapter], ProviderOutcome]


def execute(job: ProviderJob, adapter: ProviderAdapter) -> ProviderOutcome:
    request = job.root
    key = (request.capability, request.params.profile)
    try:
        handler = _CAPABILITIES[key]
    except KeyError as exc:
        raise UnsupportedCapability(f"Unsupported provider capability/profile: {key}") from exc
    return handler(request, adapter)


def _generate_script(
    job: GenerateScriptJob | GenerateSpeechJob, adapter: ProviderAdapter
) -> ProviderOutcome:
    if not isinstance(job, GenerateScriptJob):
        raise UnsupportedCapability("generate_script received the wrong job shape")
    script = adapter.generate_script(job.params.payload)
    return ProviderOutcome(
        data={"profile": "product_ad_v1", "language": "vi", "script": script}
    )


def _generate_speech(
    job: GenerateScriptJob | GenerateSpeechJob, adapter: ProviderAdapter
) -> ProviderOutcome:
    if not isinstance(job, GenerateSpeechJob):
        raise UnsupportedCapability("generate_speech received the wrong job shape")
    speech = adapter.generate_speech(job.params.payload.script)
    return ProviderOutcome(
        data={"profile": "vi_voice_v1", "durationMs": speech.duration_ms},
        files=(ProviderFile("speech.wav", speech.mime, speech.body),),
    )


_CAPABILITIES: dict[tuple[str, str], Handler] = {
    ("generate_script", "product_ad_v1"): _generate_script,
    ("generate_speech", "vi_voice_v1"): _generate_speech,
}
