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
from .adapter import SpeechArtifact
from .contracts.provider_job import ProductAdPayload

log = logging.getLogger("flowcortex_provider_worker.openai")

PROVIDER_OPENAI = "openai"

# Script-only today; TTS uses OpenAI's audio/speech endpoint and lives behind a different
# capability.
_OPENAI_SCRIPT_MODEL = os.environ.get("FLOWCORTEX_PROVIDER_SCRIPT_MODEL", "gpt-4o-mini")
# Default keeps the OpenAI endpoint. Set to a different `https://.../v1` URL to route through an
# OpenAI-compatible proxy (e.g. https://api.vilao.ai/v1); the worker still sends the standard
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

_SYSTEM_PROMPT = (
    "You are an expert Vietnamese short-form video advertising copywriter "
    "specializing in TikTok, Facebook Reels, and YouTube Shorts.\n"
    "Write a compelling, natural Vietnamese voice-over script for a "
    "15-second product advertisement.\n\n"

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
    "5. CTA: End with the exact call-to-action sentence provided "
    "by the user, without modifying it.\n\n"

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
    "- Target 35-45 Vietnamese whitespace-separated words in total.\n"
    "- Optimize for approximately 15 seconds of natural speech.\n"
    "- Prioritize the hook, strongest benefit, and CTA.\n"
    "- If the supplied CTA, price, or promotion makes the target "
    "impossible, preserve required information and minimize other text.\n\n"

    "FACTUAL ACCURACY:\n"
    "- Use ONLY product information explicitly provided in the input.\n"
    "- Never invent features, materials, discounts, guarantees, "
    "scarcity, customer reviews, or product performance claims.\n"
    "- Never invent a price or promotion.\n"
    "- Never modify the provided CTA.\n"
    "- Ensure the final script is coherent, persuasive, and ready "
    "for direct text-to-speech generation."
)


@dataclass(frozen=True)
class OpenAIConfig:
    """The two values needed by the urllib adapter; no provider SDK is required."""

    api_key: str
    base_url: str = _OPENAI_BASE_URL


@dataclass(frozen=True)
class OpenAIProviderAdapter:
    """Sends a Chat Completions request with structured-output JSON Schema.

    The HTTP call is hand-rolled with `urllib.request` instead of the OpenAI SDK so the adapter
    tolerates proxy responses that include fields the SDK model does not know about (e.g. a
    Google-style `extra_content.thought_signature` on a Gemini-via-OpenAI proxy): the SDK raises
    a `TypeError` on parse before the adapter sees the body, which surfaces as an opaque
    `BadRequest`/timeout to the broker. Pulling JSON by hand and reading only the documented
    `choices[0].message.content` path keeps the adapter portable across OpenAI, Azure OpenAI,
    and any OpenAI-compatible proxy without pinning the wire contract to a particular SDK
    version.
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

    def generate_speech(self, script: str) -> SpeechArtifact:
        raise PermanentError(
            "provider_capability_unavailable",
            "OpenAI speech generation is not implemented",
        )

    def _request_script(self, payload: ProductAdPayload) -> str:
        body = json.dumps({
            "model": _OPENAI_SCRIPT_MODEL,
            "messages": [
                {"role": "system", "content": _SYSTEM_PROMPT},
                {"role": "user", "content": self._user_message(payload)},
            ],
            "response_format": _SCRIPT_RESPONSE_FORMAT,
            "temperature": 0.4,
        }).encode("utf-8")
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
            script_obj = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise PermanentError(
                "provider_response_invalid", "provider did not honour the response format"
            ) from exc
        if not isinstance(script_obj, dict) or "script" not in script_obj:
            raise PermanentError(
                "provider_response_invalid", "provider response has no script field"
            )
        script_value = script_obj["script"]
        if not isinstance(script_value, str):
            raise PermanentError(
                "provider_response_invalid", "provider script field is not a string"
            )
        return script_value

    @staticmethod
    def _user_message(payload: ProductAdPayload) -> str:
        # Stripped to the wire contract fields the model is allowed to quote. Anything not in here
        # is invented content by definition, and the JSON-Schema `additionalProperties=false` keeps
        # the model honest on field names; the `script` field itself is free text but bounded.
        parts = [
            f"productName: {payload.productName}",
            f"description: {payload.description}",
            f"cta: {payload.cta}",
        ]
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


def select_adapter(provider: str, openai_key: str) -> OpenAIProviderAdapter:
    """Switch on the runtime env. The fixture adapter remains the default when no key is set so a
    developer without secrets can still demo the slice end-to-end."""
    if provider == PROVIDER_OPENAI:
        if not openai_key:
            raise RuntimeError("PROVIDER_ADAPTER=openai requires OPENAI_API_KEY")
        return OpenAIProviderAdapter(OpenAIConfig(api_key=openai_key, base_url=_OPENAI_BASE_URL))
    raise ValueError(f"Unknown provider adapter: {provider}")
