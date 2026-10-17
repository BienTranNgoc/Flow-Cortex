"""Provider adapter for OpenAI's Chat Completions API and any OpenAI-compatible endpoint
(Azure OpenAI, an internal proxy). Picks structured-output JSON Schema so the model returns the
product script fields directly — no second parse pass.

The adapter is conversation-blind: every call rebuilds the message list from the immutable
product payload. Prompt content, response body and the API key never leave this file's logging
boundary, and the worker redaction rule is enforced by the runtime logger (see
:mod:`flowcortex_worker.telemetry`).
"""

from __future__ import annotations

import json
import logging
import os
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import UTC, datetime
from email.utils import parsedate_to_datetime
from typing import Any

from ..errors import PermanentError, TransientError
from .adapter import (
    Abort,
    SpeechArtifact,
    SpeechIdentity,
    SpeechRequest,
    StoryboardArtifact,
    StoryboardRequest,
    VideoAbort,
    VideoArtifact,
    VideoIdentity,
    VideoRequest,
)
from .contracts.provider_job import ProductAdPayload

log = logging.getLogger("flowcortex_provider_worker.openai")

PROVIDER_OPENAI = "openai"

# Script-only today; TTS uses OpenAI's audio/speech endpoint and lives behind a different
# capability.
_OPENAI_SCRIPT_MODEL = os.environ.get("FLOWCORTEX_PROVIDER_SCRIPT_MODEL", "gpt-4o-mini")
# Default keeps the OpenAI endpoint. Set to a different `https://.../v1` URL to route through an
# OpenAI-compatible proxy (e.g. https://llm-proxy.example/v1); the worker still sends the standard
# `Authorization: Bearer` header.
_OPENAI_BASE_URL = os.environ.get("FLOWCORTEX_PROVIDER_BASE_URL") or "https://api.openai.com/v1"
# OpenAI or a proxy may sit behind a slow upstream — keep a generous read timeout so the broker's
# lease runs out, not the urllib call. The broker still owns redelivery on transient I/O.
_REQUEST_TIMEOUT_SECONDS = float(os.environ.get("FLOWCORTEX_PROVIDER_TIMEOUT_SECONDS", "60"))

# JSON Schema the model must return. Mirrors `ProductAdScript` from
# `contracts/provider-script-result.schema.json` — language and profile are not on the wire;
# only `script` is.
_SCRIPT_SCHEMA_NAME = "product_ad_script_v1"
_SCRIPT_RESPONSE_FORMAT: dict[str, Any] = {
    "type": "json_schema",
    "json_schema": {
        "name": _SCRIPT_SCHEMA_NAME,
        "strict": True,
        "schema": {
            "type": "object",
            "additionalProperties": False,
            "required": ["script"],
            "properties": {
                "script": {
                    "type": "string",
                    "minLength": 1,
                    "maxLength": 8000,
                    "description": (
                        "Vietnamese ad script: hook, benefits derived only from "
                        "confirmed inputs, and the call-to-action."
                    ),
                }
            },
        },
    },
}

_STORYBOARD_RESPONSE_FORMAT: dict[str, Any] = {
    "type": "json_schema",
    "json_schema": {
        "name": "product_ad_storyboard_v1",
        "strict": True,
        "schema": {
            "type": "object",
            "additionalProperties": False,
            "required": ["scene1Prompt", "scene2Prompt"],
            "properties": {
                "scene1Prompt": {"type": "string", "minLength": 1, "maxLength": 2500},
                "scene2Prompt": {"type": "string", "minLength": 1, "maxLength": 2500},
            },
        },
    },
}

_STORYBOARD_SYSTEM_PROMPT = (
    "You are a visual director for vertical short-form product advertisements. "
    "Convert the approved Vietnamese voice-over into exactly two distinct English video "
    "generation prompts, each designed for a five-second 9:16 clip. Scene 1 must be an "
    "attention-grabbing product hook. Scene 2 must demonstrate the primary benefit and end "
    "with a clean product beauty shot suitable for a CTA overlay. Specify subject action, "
    "environment, lighting and camera movement. Keep product appearance consistent. Do not "
    "request generated text, captions, logos, voice, music, unsupported claims or transitions "
    "inside a clip. Return only one JSON object with exactly two string fields named "
    "`scene1Prompt` and `scene2Prompt`; do not rename, nest or wrap these fields."
)

_SYSTEM_PROMPT = (
    "You are an expert Vietnamese short-form video advertising copywriter "
    "specializing in TikTok, Facebook Reels, and YouTube Shorts.\n"
    "Write a compelling, natural Vietnamese voice-over script for a "
    "10-second product advertisement.\n\n"

    "OUTPUT FORMAT:\n"
    "- Return ONLY a valid JSON object with exactly one string field: `script`.\n"
    "- Do not include markdown, explanations, or extra fields.\n\n"

    "SCRIPT STRUCTURE:\n"
    "1. HOOK: Start with one short, attention-grabbing sentence "
    "that sparks curiosity or highlights a relevant customer desire.\n"
    "2. BENEFITS: Use 1-2 concise sentences highlighting the product's "
    "most appealing benefits based strictly on the provided description. "
    "Focus on why customers would want the product rather than "
    "simply listing features.\n"
    "3. PRICE: If a price is provided, mention it naturally and accurately.\n"
    "4. PROMOTION: If a promotion is provided, mention it naturally "
    "without exaggeration.\n"
    "5. CTA: If a call-to-action is provided, end with the exact call-to-action "
    "sentence provided by the user. If no call-to-action is provided, generate a "
    "natural, compelling Vietnamese call-to-action that fits the product and urges "
    "the viewer to act (e.g. 'Khám phá ngay!', 'Mua ngay hôm nay!').\n\n"

    "STYLE GUIDELINES:\n"
    "- Write entirely in natural, conversational Vietnamese.\n"
    "- Use an energetic, engaging, modern advertising tone.\n"
    "- Make the script sound like a real Vietnamese content creator, "
    "not a robotic advertisement.\n"
    "- Prioritize emotional appeal, clarity, and memorable wording.\n"
    "- Use short sentences that sound smooth when spoken by TTS.\n"
    "- Avoid generic phrases, repetitive adjectives, and unnecessary filler.\n"
    "- Do not include emojis, hashtags, scene directions, or timestamps.\n\n"

    "DURATION CONSTRAINTS:\n"
    "- Target 22-30 Vietnamese whitespace-separated words in total.\n"
    "- Optimize for approximately 10 seconds of natural speech.\n"
    "- Prioritize the hook, strongest benefit, and CTA.\n"
    "- If the supplied CTA, price, or promotion makes the target "
    "impossible, preserve required information and minimize other text.\n\n"

    "FACTUAL ACCURACY:\n"
    "- Use ONLY product information explicitly provided in the input.\n"
    "- Never invent features, materials, discounts, guarantees, "
    "scarcity, customer reviews, or product performance claims.\n"
    "- Never invent a price or promotion.\n"
    "- If a CTA is provided, never modify it. If omitted, create an appropriate call-to-action.\n"
    "- Ensure the final script is coherent, persuasive, and ready "
    "for direct text-to-speech generation."
)


def _storyboard_prompts(result: dict[str, Any]) -> tuple[Any, Any]:
    """Read common OpenAI-compatible variants without weakening the public result contract."""
    first = result.get("scene1Prompt", result.get("scene_1_prompt", result.get("scene1")))
    second = result.get("scene2Prompt", result.get("scene_2_prompt", result.get("scene2")))
    scenes = result.get("scenes")
    if (first is None or second is None) and isinstance(scenes, list) and len(scenes) == 2:
        first, second = scenes
    return _prompt_text(first), _prompt_text(second)


def _prompt_text(value: Any) -> Any:
    if not isinstance(value, dict):
        return value
    for key in ("prompt", "visualPrompt", "visual_prompt", "description"):
        candidate = value.get(key)
        if isinstance(candidate, str):
            return candidate
    return value


@dataclass(frozen=True)
class OpenAIConfig:
    """Capability-scoped OpenAI-compatible endpoint configuration."""

    api_key: str
    base_url: str = _OPENAI_BASE_URL
    model: str = _OPENAI_SCRIPT_MODEL


@dataclass(frozen=True)
class OpenAIProviderAdapter:
    """Sends a Chat Completions request with structured-output JSON Schema.

    Hand-rolled over `urllib`: only `choices[0].message.content` is read, so extra fields from
    OpenAI-compatible proxies cannot break parsing.
    """

    config: OpenAIConfig

    def generate_script(self, payload: ProductAdPayload) -> str:
        script = self._request_script(payload)
        # One last defensive cap: an OpenAI-compatible server may ignore the requested schema, so
        # a future schema edit must keep this boundary check in lockstep.
        if not isinstance(script, str) or not script:
            raise PermanentError("provider_response_invalid", "provider returned an empty script")
        if len(script) > 8000:
            raise PermanentError(
                "provider_response_invalid", "provider script exceeds 8000 characters"
            )
        return script

    def generate_storyboard(self, request: StoryboardRequest) -> StoryboardArtifact:
        result = self._request_json(
            _STORYBOARD_SYSTEM_PROMPT,
            "Approved Vietnamese voice-over:\n" + request.script,
            _STORYBOARD_RESPONSE_FORMAT,
            temperature=0.6,
        )
        first, second = _storyboard_prompts(result)
        if not isinstance(first, str) or not isinstance(second, str):
            raise PermanentError(
                "provider_response_invalid", "provider storyboard fields must be strings"
            )
        first = first.strip()
        second = second.strip()
        if not first or not second or len(first) > 2500 or len(second) > 2500:
            raise PermanentError(
                "provider_response_invalid", "provider storyboard prompts are invalid"
            )
        if first == second:
            raise PermanentError(
                "provider_response_invalid", "provider storyboard scenes must be distinct"
            )
        return StoryboardArtifact(first, second)

    def speech_identity(self) -> SpeechIdentity:
        raise PermanentError(
            "provider_capability_unavailable",
            "OpenAI speech generation is not implemented",
        )

    def generate_speech(self, request: SpeechRequest, abort: Abort) -> SpeechArtifact:
        raise PermanentError(
            "provider_capability_unavailable",
            "OpenAI speech generation is not implemented",
        )

    def video_identity(self) -> VideoIdentity:
        raise PermanentError(
            "provider_capability_unavailable", "OpenAI video generation is not implemented"
        )

    def submit_video(self, request: VideoRequest) -> str:
        raise PermanentError(
            "provider_capability_unavailable", "OpenAI video generation is not implemented"
        )

    def await_video(self, task_id: str, abort: VideoAbort) -> VideoArtifact:
        raise PermanentError(
            "provider_capability_unavailable", "OpenAI video generation is not implemented"
        )

    def _request_script(self, payload: ProductAdPayload) -> str:
        script_obj = self._request_json(
            _SYSTEM_PROMPT,
            self._user_message(payload),
            _SCRIPT_RESPONSE_FORMAT,
            temperature=0.4,
        )
        if "script" not in script_obj:
            raise PermanentError(
                "provider_response_invalid", "provider response has no script field"
            )
        script_value = script_obj["script"]
        if not isinstance(script_value, str):
            raise PermanentError(
                "provider_response_invalid", "provider script field is not a string"
            )
        return script_value

    def _request_json(
        self,
        system_prompt: str,
        user_message: str,
        response_format: dict[str, Any],
        *,
        temperature: float,
    ) -> dict[str, Any]:
        body = json.dumps({
            "model": self.config.model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_message},
            ],
            "response_format": response_format,
            "temperature": temperature,
        }).encode()
        api_key = self.config.api_key
        base_url = self.config.base_url
        url = f"{base_url.rstrip('/')}/chat/completions"
        request = urllib.request.Request(
            url,
            data=body,
            method="POST",
            headers={
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=_REQUEST_TIMEOUT_SECONDS) as response:
                payload_bytes = response.read()
        except urllib.error.HTTPError as exc:
            error_code = _provider_error_code(exc)
            log.warning(
                "provider HTTP request failed",
                extra={"status": exc.code, "providerErrorCode": error_code},
            )
            if exc.code in (408, 429) or 500 <= exc.code < 600:
                raise TransientError(
                    f"provider HTTP {exc.code}",
                    retry_after_seconds=_retry_after_seconds(exc),
                ) from exc
            raise PermanentError(
                "provider_request_failed", f"provider rejected the request with HTTP {exc.code}"
            ) from exc
        except (urllib.error.URLError, TimeoutError) as exc:
            log.warning("provider connection failed", extra={"errorType": type(exc).__name__})
            raise TransientError(f"provider connection failed: {type(exc).__name__}") from exc

        try:
            parsed = json.loads(payload_bytes)
        except json.JSONDecodeError as exc:
            raise PermanentError(
                "provider_response_invalid", "provider response was not valid JSON"
            ) from exc
        try:
            raw = parsed["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError) as exc:
            raise PermanentError(
                "provider_response_invalid", "provider response has no message content"
            ) from exc
        try:
            result = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise PermanentError(
                "provider_response_invalid", "provider did not honour the response format"
            ) from exc
        if not isinstance(result, dict):
            raise PermanentError(
                "provider_response_invalid", "provider structured response is not an object"
            )
        return result

    @staticmethod
    def _user_message(payload: ProductAdPayload) -> str:
        # Stripped to the wire contract fields the model is allowed to quote. Anything not in here
        # is invented content by definition, and the JSON-Schema `additionalProperties=false` keeps
        # the model honest on field names; the `script` field itself is free text but bounded.
        parts = [
            f"productName: {payload.productName}",
            f"description: {payload.description}",
        ]
        if payload.cta is not None:
            parts.append(f"cta: {payload.cta}")
        if payload.price is not None:
            parts.append(f"price: {payload.price}")
        if payload.promotion is not None:
            parts.append(f"promotion: {payload.promotion}")
        return "\n".join(parts)


def _provider_error_code(exc: urllib.error.HTTPError) -> str:
    """Extract only the provider's machine-readable code; never retain its message/body."""
    try:
        body = json.loads(exc.read())
        code = body.get("error", {}).get("code")
        return str(code)[:100] if code else "unknown"
    except (json.JSONDecodeError, AttributeError, TypeError, UnicodeDecodeError):
        return "unknown"


def _retry_after_seconds(exc: urllib.error.HTTPError) -> float | None:
    value = exc.headers.get("Retry-After") if exc.headers else None
    if not value:
        return None
    try:
        return max(0.0, float(value))
    except ValueError:
        try:
            retry_at = parsedate_to_datetime(value)
            if retry_at.tzinfo is None:
                retry_at = retry_at.replace(tzinfo=UTC)
            return max(0.0, (retry_at - datetime.now(UTC)).total_seconds())
        except (TypeError, ValueError, OverflowError):
            return None


def select_adapter(
    provider: str,
    openai_key: str,
    *,
    base_url: str = _OPENAI_BASE_URL,
    model: str = _OPENAI_SCRIPT_MODEL,
) -> OpenAIProviderAdapter:
    """Builds the adapter for `provider`; `openai` requires a key, any other name raises."""
    if provider == PROVIDER_OPENAI:
        if not openai_key:
            raise RuntimeError("OpenAI provider requires OPENAI_API_KEY")
        return OpenAIProviderAdapter(
            OpenAIConfig(api_key=openai_key, base_url=base_url, model=model)
        )
    raise ValueError(f"Unknown provider adapter: {provider}")
