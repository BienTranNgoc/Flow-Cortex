"""Provider adapter for OpenAI's Chat Completions API and any OpenAI-compatible endpoint
(Azure OpenAI, an internal proxy). Picks structured-output JSON Schema so the model returns the
product script fields directly — no second parse pass.

The adapter is conversation-blind: every call rebuilds the message list from the immutable
product payload. Prompt content, response body and the API key never leave this file's logging
boundary, and the worker redaction rule is enforced by the runtime logger (see
:mod:`flowcortex_worker.telemetry`).
"""

from __future__ import annotations

import base64
import contextlib
import http.client
import json
import logging
import os
import re
import time
import urllib.error
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from email.utils import parsedate_to_datetime
from typing import Any, Literal, NoReturn, TypeVar

from ..errors import PermanentError, TransientError
from ..runtime import ProcessKilled
from .adapter import (
    Abort,
    ScriptAbort,
    SpeechArtifact,
    SpeechIdentity,
    SpeechRequest,
    StoryboardArtifact,
    StoryboardRequest,
    VendorProbe,
    VideoAbort,
    VideoArtifact,
    VideoIdentity,
    VideoRequest,
)
from .contracts.provider_job import ProductAdPayload
from .prompts.templates import MC_STORYBOARD_SYSTEM_PROMPT, scene_setting_instruction

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
_FALLBACK_ATTEMPT_CAP_SECONDS = 45.0
_FALLBACK_DEADLINE_RESERVE_SECONDS = 10.0
_FALLBACK_MIN_BUDGET_SECONDS = 1.0
_MAX_ERROR_BODY_BYTES = 65_536
_JSON_OBJECT_RESPONSE_FORMAT: dict[str, Any] = {"type": "json_object"}

T = TypeVar("T")

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
    "You are an expert visual director for vertical (9:16) commercial product video "
    "advertisements. Convert the approved Vietnamese voice-over and ALL attached product "
    "reference images (1 to 3 photos) into exactly two distinct, compelling English video "
    "generation prompts, each designed for a five-second 9:16 clip.\n\n"
    "MULTI-IMAGE VISUAL GROUND TRUTH (INSPECT ALL ATTACHED IMAGES):\n"
    "- Treat ALL attached product photos as the absolute visual ground truth for product "
    "identity, silhouette, proportions, materials, logos, and hardware. Never invent unrelated "
    "accessories or alter the product's core design.\n"
    "- Inspect how the attached images relate to each other and apply the matching branch:\n"
    "  * MULTIPLE COLORWAYS OR FINISH VARIANTS (2-3 photos showing the same product in "
    "different colors or finishes, e.g. matte black, matte grey, glossy black):\n"
    "    - Scene 1 (`scene1Prompt`, 0-5s — Hero Color/Variant Showcase): Present the product "
    "centered against a clean, minimalist neutral studio backdrop with soft, even commercial "
    "lighting. Keep the framing steady with a gentle slow push-in or subtle angle shift as "
    "the product smoothly transitions across the exact colorways and finishes shown in the "
    "reference images. Do NOT lock Scene 1 to only the first photo's color.\n"
    "    - Scene 2 (`scene2Prompt`, 5-10s — Lifestyle In-Use & Benefit): Feature the hero "
    "product from the reference images in an authentic, dynamic real-world usage moment "
    "matching the voice-over, concluding on a clean, balanced close-up hero frame.\n"
    "  * MULTIPLE ANGLES OF THE SAME PRODUCT (2-3 photos showing front, side, back, or detail "
    "views of one single colorway):\n"
    "    - Scene 1 (`scene1Prompt`, 0-5s — Hero 3D Angle Showcase): Combine the geometry and "
    "details from all reference angles in a smooth, continuous parallax glide or gentle "
    "rotation under soft, even studio lighting.\n"
    "    - Scene 2 (`scene2Prompt`, 5-10s — Lifestyle In-Use & Benefit): Show the exact same "
    "product in natural active use matching the voice-over, settling on a crisp hero frame.\n"
    "  * SINGLE PRODUCT IMAGE (1 photo provided):\n"
    "    - Scene 1 (`scene1Prompt`, 0-5s — Opening Hero Reveal): Clean, scroll-stopping "
    "commercial reveal preserving 100% of the product's exact color, finish, and details "
    "with smooth directional camera movement.\n"
    "    - Scene 2 (`scene2Prompt`, 5-10s — Lifestyle In-Use & Benefit): Authentic in-use "
    "benefit shot of the exact same product, finishing on a clean centered hero frame.\n\n"
    "PHYSICAL GROUNDING & CAMERA STABILITY:\n"
    "- If the reference images are isolated cutouts on a white background, keep Scene 1 on a "
    "clean minimalist studio backdrop or resting firmly on a solid surface — NEVER place a "
    "cutout product hovering above a turntable or floating unnaturally in mid-air.\n"
    "- Keep each prompt concise and vivid (40-75 English words) with ONE smooth continuous "
    "camera or subject motion per scene. Avoid chaotic cuts or extreme 360-degree spins.\n\n"
    "PURE VISUAL CINEMATOGRAPHY — STRICT PROHIBITIONS:\n"
    "- NEVER mention text, graphics, or UI elements ('text', 'caption', 'subtitle', 'CTA', "
    "'overlay', 'watermark', 'font', 'price tag', 'button', 'no text') anywhere in "
    "`scene1Prompt` or `scene2Prompt`.\n"
    "- Do not mention voice, speech, sound effects, or music.\n"
    "- Return only one valid JSON object with exactly two distinct string fields: "
    "`scene1Prompt` and `scene2Prompt`; do not rename, nest, or wrap these fields."
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
    "- Target 22-28 Vietnamese whitespace-separated words in total.\n"
    "- Optimize for approximately 8.5 to 9.5 seconds of natural speech "
    "so punctuation pauses and numbers stay under 10 seconds.\n"
    "- Prioritize the hook, strongest benefit, and CTA.\n"
    "- If the supplied CTA, price, or promotion makes the target "
    "impossible, preserve required information and minimize other text.\n\n"

    "FACTUAL ACCURACY:\n"
    "- Use ONLY product information explicitly provided in the input.\n"
    "- Never invent features, materials, discounts, guarantees, "
    "scarcity, customer reviews, or product performance claims.\n"
    "- Never invent a price or promotion.\n"
    "- Treat the attached product image as visual ground truth for appearance, colour, shape, "
    "packaging and visible details. Do not infer materials, performance or claims that are not "
    "confirmed by the text input.\n"
    "- If a CTA is provided, never modify it. If omitted, create an appropriate call-to-action.\n"
    "- Ensure the final script is coherent, persuasive, and ready "
    "for direct text-to-speech generation."
)


_SCRIPT_FALLBACK_SCHEMA_INSTRUCTIONS = (
    "\n\nSTRICT JSON SCHEMA CONTRACT:\n"
    '- Return ONLY a single valid JSON object matching: {"script": "<non-empty Vietnamese '
    'string, max 8000 chars>"}.\n'
    "- Required keys: `script` only. Do not include additional properties, markdown fences, "
    "or commentary."
)

_STORYBOARD_FALLBACK_SCHEMA_INSTRUCTIONS = (
    "\n\nSTRICT JSON SCHEMA CONTRACT:\n"
    '- Return ONLY a single valid JSON object matching: {"scene1Prompt": "<non-empty English '
    'string, max 2500 chars>", "scene2Prompt": "<non-empty English string, max 2500 chars>"}.\n'
    "- Required keys: `scene1Prompt` and `scene2Prompt` only. Both prompts must be distinct. "
    "Do not include additional properties, markdown fences, or commentary."
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
    fallback_model: str | None = None

    def __post_init__(self) -> None:
        model = self.model.strip()
        object.__setattr__(self, "model", model)
        raw_fallback = self.fallback_model.strip() if self.fallback_model is not None else ""
        normalized = raw_fallback if raw_fallback and raw_fallback != model else None
        object.__setattr__(self, "fallback_model", normalized)


def _validate_script_response(script_obj: dict[str, Any]) -> str:
    if "script" not in script_obj:
        raise PermanentError("provider_response_invalid", "provider response has no script field")
    script = script_obj["script"]
    if not isinstance(script, str):
        raise PermanentError("provider_response_invalid", "provider script field is not a string")
    # One last defensive cap: an OpenAI-compatible server may ignore the requested schema, so
    # a future schema edit must keep this boundary check in lockstep.
    if not script:
        raise PermanentError("provider_response_invalid", "provider returned an empty script")
    if len(script) > 8000:
        raise PermanentError(
            "provider_response_invalid", "provider script exceeds 8000 characters"
        )
    return script


def _validate_storyboard_response(result: dict[str, Any]) -> StoryboardArtifact:
    first, second = _storyboard_prompts(result)
    if not isinstance(first, str) or not isinstance(second, str):
        raise PermanentError(
            "provider_response_invalid", "provider storyboard fields must be strings"
        )
    first = first.strip()
    second = second.strip()
    if not first or not second or len(first) > 2500 or len(second) > 2500:
        raise PermanentError("provider_response_invalid", "provider storyboard prompts are invalid")
    if first == second:
        raise PermanentError(
            "provider_response_invalid", "provider storyboard scenes must be distinct"
        )
    return StoryboardArtifact(first, second)


@dataclass(frozen=True)
class OpenAIProviderAdapter:
    """Sends a Chat Completions request with structured-output JSON Schema.

    Hand-rolled over `urllib`: only `choices[0].message.content` is read, so extra fields from
    OpenAI-compatible proxies cannot break parsing.
    """

    config: OpenAIConfig

    def generate_script(
        self,
        payload: ProductAdPayload,
        reference_image: bytes | None = None,
        *,
        abort: ScriptAbort | None = None,
        deadline_monotonic: float | None = None,
    ) -> str:
        user_content = self._user_content(payload, reference_image)
        return self._run_with_fallback(
            operation="generate_script",
            system_prompt=_SYSTEM_PROMPT,
            fallback_schema_instructions=_SCRIPT_FALLBACK_SCHEMA_INSTRUCTIONS,
            user_content=user_content,
            response_format=_SCRIPT_RESPONSE_FORMAT,
            validate=_validate_script_response,
            temperature=0.4,
            abort=abort,
            deadline_monotonic=deadline_monotonic,
        )

    def generate_storyboard(
        self,
        request: StoryboardRequest,
        reference_image: bytes | None = None,
        *,
        reference_images: tuple[bytes, ...] = (),
        abort: ScriptAbort | None = None,
        deadline_monotonic: float | None = None,
    ) -> StoryboardArtifact:
        images = reference_images or ((reference_image,) if reference_image is not None else ())
        system_prompt = (
            MC_STORYBOARD_SYSTEM_PROMPT
            if request.template_id == "mc-v1"
            else _STORYBOARD_SYSTEM_PROMPT
        )
        script_text = request.script
        if request.mc_hook_script and request.product_close_script:
            script_text = (
                f"Scene 1 (MC Hook): {request.mc_hook_script}\n"
                f"Scene 2 (Product Close): {request.product_close_script}"
            )
        directive = scene_setting_instruction(request.scene_preset, request.scene_note)
        if directive:
            script_text = f"{script_text}\n\n{directive}"
        user_content = self._storyboard_user_content(script_text, images)
        return self._run_with_fallback(
            operation="generate_storyboard",
            system_prompt=system_prompt,
            fallback_schema_instructions=_STORYBOARD_FALLBACK_SCHEMA_INSTRUCTIONS,
            user_content=user_content,
            response_format=_STORYBOARD_RESPONSE_FORMAT,
            validate=_validate_storyboard_response,
            temperature=0.35,
            abort=abort,
            deadline_monotonic=deadline_monotonic,
        )

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

    def probe_video(self, task_id: str) -> VendorProbe:
        raise PermanentError(
            "provider_capability_unavailable", "OpenAI video generation is not implemented"
        )

    def vendor_scope(self) -> dict[str, str]:
        raise PermanentError(
            "provider_capability_unavailable", "OpenAI video generation is not implemented"
        )

    def _run_with_fallback(
        self,
        *,
        operation: str,
        system_prompt: str,
        fallback_schema_instructions: str,
        user_content: str | list[dict[str, Any]],
        response_format: dict[str, Any],
        validate: Callable[[dict[str, Any]], T],
        temperature: float,
        abort: ScriptAbort | None,
        deadline_monotonic: float | None,
    ) -> T:
        if abort is not None:
            abort.check()
        fallback_model = self.config.fallback_model
        primary_timeout = (
            _REQUEST_TIMEOUT_SECONDS
            if fallback_model is None
            else _primary_attempt_timeout(deadline_monotonic)
        )
        try:
            result = self._request_json(
                model=self.config.model,
                system_prompt=system_prompt,
                user_message=user_content,
                response_format=response_format,
                temperature=temperature,
                timeout=primary_timeout,
            )
            return validate(result)
        except Exception as exc:
            if fallback_model is None:
                _reraise_unwrapped(exc)
            allow, reason = _classify_failure(exc)
            if not allow:
                _reraise_unwrapped(exc)
            if abort is not None:
                abort.check()
            fallback_timeout = _fallback_attempt_timeout(deadline_monotonic)
            if fallback_timeout is None:
                log.warning(
                    "provider fallback skipped due to insufficient remaining budget",
                    extra={
                        "primaryModel": self.config.model,
                        "fallbackModel": fallback_model,
                        "fallbackReason": reason,
                        "providerOperation": operation,
                    },
                )
                _reraise_unwrapped(exc)
            log.warning(
                "provider falling back to secondary model",
                extra={
                    "primaryModel": self.config.model,
                    "fallbackModel": fallback_model,
                    "fallbackReason": reason,
                    "providerOperation": operation,
                },
            )

        try:
            fallback_result = self._request_json(
                model=fallback_model,
                system_prompt=system_prompt + fallback_schema_instructions,
                user_message=user_content,
                response_format=_JSON_OBJECT_RESPONSE_FORMAT,
                temperature=temperature,
                timeout=fallback_timeout,
            )
            validated = validate(fallback_result)
        except _AttemptFailure as failure:
            raise failure.error from failure.__cause__
        log.info(
            "provider fallback succeeded",
            extra={
                "primaryModel": self.config.model,
                "fallbackModel": fallback_model,
                "fallbackReason": reason,
                "providerOperation": operation,
            },
        )
        return validated

    def _request_json(
        self,
        *,
        model: str,
        system_prompt: str,
        user_message: str | list[dict[str, Any]],
        response_format: dict[str, Any],
        temperature: float,
        timeout: float,
    ) -> dict[str, Any]:
        body = json.dumps({
            "model": model,
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
            with urllib.request.urlopen(request, timeout=timeout) as response:
                payload_bytes = response.read()
        except urllib.error.HTTPError as exc:
            log_code, tokens = _read_http_error_tokens(exc)
            log.warning(
                "provider HTTP request failed",
                extra={"status": exc.code, "providerErrorCode": log_code, "model": model},
            )
            kind = _classify_http_error(exc.code, tokens)
            error: PermanentError | TransientError = (
                TransientError(
                    f"provider HTTP {exc.code}",
                    retry_after_seconds=_retry_after_seconds(exc),
                )
                if kind == "retryable_http"
                else PermanentError(
                    "provider_request_failed",
                    f"provider rejected the request with HTTP {exc.code}",
                )
            )
            raise _AttemptFailure(error, kind) from exc
        except (urllib.error.URLError, TimeoutError, http.client.HTTPException, OSError) as exc:
            log.warning(
                "provider connection failed",
                extra={"errorType": type(exc).__name__, "model": model},
            )
            raise _AttemptFailure(
                TransientError(f"provider connection failed: {type(exc).__name__}"),
                "transport",
            ) from exc

        try:
            parsed = json.loads(payload_bytes)
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            raise _AttemptFailure(
                PermanentError("provider_response_invalid", "provider response was not valid JSON"),
                "bad_output",
            ) from exc
        if not isinstance(parsed, dict):
            raise _AttemptFailure(
                PermanentError(
                    "provider_response_invalid", "provider response was not a JSON object"
                ),
                "bad_output",
            )
        choices = parsed.get("choices")
        if not (isinstance(choices, list) and choices) and isinstance(parsed.get("error"), dict):
            err = parsed["error"]
            log_code, tokens = _extract_error_tokens(err)
            if tokens & _MODERATION_ERROR_CODES:
                log.warning(
                    "provider returned moderation error in HTTP 200 body",
                    extra={"providerErrorCode": log_code, "model": model},
                )
                raise _AttemptFailure(
                    PermanentError(
                        "provider_request_failed",
                        "provider rejected the request due to content moderation",
                    ),
                    "moderation",
                )
            hint = _transient_hint_for_text(
                " ".join(str(err.get(k) or "") for k in ("code", "type", "message"))
            )
            log.warning(
                "provider returned error object in HTTP 200 body",
                extra={"providerErrorCode": log_code, "matchedHint": hint, "model": model},
            )
            if hint is not None:
                raise _AttemptFailure(
                    TransientError(f"provider masked transient failure: {hint}"),
                    "masked_transient",
                )
            err_kind: _FailureKind = (
                "auth_failure"
                if tokens & _AUTH_ERROR_CODES
                else "model_rejected"
                if tokens & _MODEL_REJECTION_CODES
                else "bad_request"
            )
            raise _AttemptFailure(
                PermanentError(
                    "provider_request_failed", "provider returned an error in HTTP 200 response"
                ),
                err_kind,
            )
        if not isinstance(choices, list) or not choices:
            raise _AttemptFailure(
                PermanentError("provider_response_invalid", "provider response has no choices"),
                "bad_output",
            )
        choice = choices[0]
        if not isinstance(choice, dict):
            raise _AttemptFailure(
                PermanentError("provider_response_invalid", "provider choice is not an object"),
                "bad_output",
            )
        safe_finish_reason = _safe_finish_reason(choice.get("finish_reason"))
        if safe_finish_reason == "content_filter":
            log.warning(
                "provider filtered response content",
                extra={"finishReason": "content_filter", "model": model},
            )
            raise _AttemptFailure(
                PermanentError("provider_request_failed", "provider filtered the response"),
                "content_filter",
            )
        message = choice.get("message")
        if not isinstance(message, dict):
            raise _AttemptFailure(
                PermanentError(
                    "provider_response_invalid", "provider response has no message object"
                ),
                "bad_output",
            )
        refusal = message.get("refusal")
        has_refusal = bool(refusal.strip()) if isinstance(refusal, str) else bool(refusal)
        if has_refusal:
            log.warning(
                "provider refused response",
                extra={"finishReason": safe_finish_reason, "model": model},
            )
            raise _AttemptFailure(
                PermanentError("provider_request_failed", "provider refused the request"),
                "refusal",
            )
        if "content" not in message:
            raise _AttemptFailure(
                PermanentError(
                    "provider_response_invalid", "provider response has no message content"
                ),
                "bad_output",
            )
        raw = message["content"]
        try:
            result = _parse_structured_content(raw)
        except ValueError as exc:
            usage = parsed.get("usage")
            prompt_tokens = (
                _safe_token_count(usage.get("prompt_tokens")) if isinstance(usage, dict) else None
            )
            raw_text = _extract_text_content(raw)
            transient_hint = (
                _transient_hint_for_text(raw_text) if raw_text is not None else None
            )
            if transient_hint is not None:
                log.warning(
                    "provider returned masked transient error",
                    extra={
                        "finishReason": safe_finish_reason,
                        "contentLength": len(raw_text) if raw_text is not None else None,
                        "promptTokens": prompt_tokens,
                        "matchedHint": transient_hint,
                        "model": model,
                    },
                )
                raise _AttemptFailure(
                    TransientError(f"provider masked transient failure: {transient_hint}"),
                    "masked_transient",
                ) from exc
            log.warning(
                "provider returned invalid structured content",
                extra={
                    "finishReason": safe_finish_reason,
                    "contentType": type(raw).__name__,
                    "contentLength": len(raw) if isinstance(raw, str) else None,
                    "startsWithFence": isinstance(raw, str) and raw.lstrip().startswith("```"),
                    "hasJsonBrace": isinstance(raw, str) and "{" in raw,
                    "promptTokens": prompt_tokens,
                    "model": model,
                },
            )
            raise _AttemptFailure(
                PermanentError(
                    "provider_response_invalid", "provider did not honour the response format"
                ),
                "bad_output",
            ) from exc
        if not isinstance(result, dict):
            raise _AttemptFailure(
                PermanentError(
                    "provider_response_invalid", "provider structured response is not an object"
                ),
                "bad_output",
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

    @classmethod
    def _user_content(
        cls, payload: ProductAdPayload, reference_image: bytes | None
    ) -> str | list[dict[str, Any]]:
        text = cls._user_message(payload)
        if reference_image is None:
            return text
        mime = _image_mime(reference_image)
        encoded = base64.b64encode(reference_image).decode("ascii")
        return [
            {
                "type": "text",
                "text": text + "\nUse the attached product image as visual ground truth.",
            },
            {
                "type": "image_url",
                "image_url": {"url": f"data:{mime};base64,{encoded}", "detail": "high"},
            },
        ]

    @classmethod
    def _storyboard_user_content(
        cls, script: str, reference_images: tuple[bytes, ...]
    ) -> str | list[dict[str, Any]]:
        text = f"Approved Vietnamese voice-over:\n{script}"
        if not reference_images:
            return text
        count = len(reference_images)
        instruction = (
            f"{text}\n\n"
            f"MULTI-IMAGE VISUAL GROUND TRUTH (Reference images provided: {count}):\n"
            "1. Inspect ALL attached product photos as visual ground truth.\n"
            "2. Decide whether the photos show multiple color/finish variants, multiple angles "
            "of one colorway, or a single product photo, and follow the matching branch.\n"
            "3. When multiple color variants are provided, make Scene 1 smoothly showcase the "
            "color/finish transitions on a clean minimalist studio backdrop (no floating above "
            "a turntable), and make Scene 2 an authentic lifestyle in-use shot.\n"
            "4. Keep each prompt 40-75 English words and NEVER mention text, captions, CTA, "
            "overlays, or audio."
        )
        content: list[dict[str, Any]] = [{"type": "text", "text": instruction}]
        for image_bytes in reference_images:
            mime = _image_mime(image_bytes)
            encoded = base64.b64encode(image_bytes).decode("ascii")
            content.append(
                {
                    "type": "image_url",
                    "image_url": {"url": f"data:{mime};base64,{encoded}", "detail": "high"},
                }
            )
        return content


_THINK_BLOCK_RE = re.compile(
    r"<(?:think|thought)>.*?</(?:think|thought)>", re.DOTALL | re.IGNORECASE
)
_CODE_FENCE_RE = re.compile(r"```(?:json)?\s*(.*?)\s*```", re.DOTALL | re.IGNORECASE)
_MAX_TRANSIENT_MESSAGE_CHARS = 300
_TRANSIENT_CONTENT_HINTS: tuple[tuple[str, str], ...] = (
    ("upstream_temporarily_unavailable", "upstream temporarily unavailable"),
    ("temporarily_unavailable", "temporarily unavailable"),
    ("service_unavailable", "service unavailable"),
    ("bad_gateway", "bad gateway"),
    ("gateway_timeout", "gateway timeout"),
    ("upstream_error", "upstream error"),
    ("please_retry", "please retry"),
    ("try_again_later", "try again later"),
    ("model_overloaded", "overloaded"),
    ("rate_limited", "rate limit"),
)


def _transient_hint_for_text(text: str) -> str | None:
    normalized = text.strip().lower()
    if not normalized or len(normalized) > _MAX_TRANSIENT_MESSAGE_CHARS:
        return None
    for label, pattern in _TRANSIENT_CONTENT_HINTS:
        if pattern in normalized:
            return label
    return None


def _extract_text_content(raw: Any) -> str | None:
    if isinstance(raw, str):
        return raw
    if isinstance(raw, list):
        parts = [
            part["text"]
            for part in raw
            if isinstance(part, dict) and isinstance(part.get("text"), str)
        ]
        return "".join(parts) if parts else None
    return None


def _parse_structured_content(raw: Any) -> Any:
    """Parse JSON from `choices[0].message.content`, tolerating proxy fences or preamble text."""
    if isinstance(raw, dict):
        return raw
    text = _extract_text_content(raw)
    if text is None:
        raise ValueError("content is not text")
    stripped = text.strip()
    if not stripped:
        raise ValueError("content is empty")
    try:
        return json.loads(stripped)
    except json.JSONDecodeError:
        pass

    cleaned = _THINK_BLOCK_RE.sub("", stripped).strip()
    if cleaned and cleaned != stripped:
        try:
            return json.loads(cleaned)
        except json.JSONDecodeError:
            pass

    for match in _CODE_FENCE_RE.finditer(cleaned):
        candidate = match.group(1).strip()
        if not candidate:
            continue
        try:
            return json.loads(candidate)
        except json.JSONDecodeError:
            pass

    decoder = json.JSONDecoder()
    index = cleaned.find("{")
    while index != -1:
        try:
            obj, _ = decoder.raw_decode(cleaned, index)
            if isinstance(obj, dict):
                return obj
        except json.JSONDecodeError:
            pass
        index = cleaned.find("{", index + 1)

    raise ValueError("no valid JSON object in content")


def _image_mime(body: bytes) -> str:
    if body[:8] == b"\x89PNG\r\n\x1a\n":
        return "image/png"
    if body[:3] == b"\xff\xd8\xff":
        return "image/jpeg"
    if body[:6] in (b"GIF87a", b"GIF89a"):
        return "image/gif"
    if body[:4] == b"RIFF" and body[8:12] == b"WEBP":
        return "image/webp"
    raise PermanentError("input_rejected", "product image format is not supported")


_FailureKind = Literal[
    "moderation",
    "content_filter",
    "refusal",
    "transport",
    "retryable_http",
    "masked_transient",
    "auth_failure",
    "model_rejected",
    "bad_output",
    "bad_request",
]

_SAFE_ERROR_TOKEN_RE = re.compile(r"^[A-Za-z0-9_.:/-]{1,100}$")
_KNOWN_FINISH_REASONS = frozenset({
    "stop",
    "length",
    "content_filter",
    "tool_calls",
    "function_call",
})
_TERMINAL_PERMANENT_CODES = frozenset({
    "input_rejected",
    "contract_violation",
    "run_timeout",
    "provider_capability_unavailable",
})

_MODERATION_ERROR_CODES = frozenset({
    "content_policy_violation",
    "content_filter",
    "moderation_blocked",
    "policy_violation",
    "safety_violation",
    "safety_blocked",
    "input_moderation",
    "output_moderation",
    "prohibited_content",
    "unsafe_content",
    "sensitive_content",
    "responsible_ai_policy_violation",
})

_AUTH_ERROR_CODES = frozenset({
    "invalid_api_key",
    "authentication_error",
    "unauthorized",
    "invalid_auth",
    "permission_denied",
    "access_denied",
    "forbidden",
    "insufficient_permissions",
    "account_deactivated",
    "billing_not_active",
    "insufficient_quota",
})

_MODEL_REJECTION_CODES = frozenset({
    "model_not_found",
    "model_not_available",
    "model_unavailable",
    "model_not_supported",
    "unsupported_model",
    "invalid_model",
    "model_access_denied",
    "model_not_allowed",
    "model_permission_denied",
    "no_such_model",
    "deployment_not_found",
    "engine_not_found",
})

_FAILURE_POLICY: dict[_FailureKind, tuple[bool, str]] = {
    "moderation": (False, "moderation"),
    "content_filter": (False, "content_filter"),
    "refusal": (False, "refusal"),
    "transport": (True, "transport_failure"),
    "retryable_http": (True, "upstream_transient"),
    "masked_transient": (True, "upstream_transient"),
    "auth_failure": (True, "auth_failure"),
    "model_rejected": (True, "model_rejected"),
    "bad_output": (True, "bad_output"),
    "bad_request": (False, "bad_request"),
}


class _AttemptFailure(Exception):
    def __init__(self, error: PermanentError | TransientError, kind: _FailureKind) -> None:
        super().__init__(str(error))
        self.error = error
        self.kind: _FailureKind = kind


def _reraise_unwrapped(exc: Exception) -> NoReturn:
    if isinstance(exc, _AttemptFailure):
        raise exc.error from exc.__cause__
    raise exc


def _classify_http_error(status: int, tokens: frozenset[str]) -> _FailureKind:
    if tokens & _MODERATION_ERROR_CODES:
        return "moderation"
    if status in (408, 429) or 500 <= status < 600:
        return "retryable_http"
    if status in (401, 403):
        return "auth_failure"
    if tokens & _MODEL_REJECTION_CODES:
        return "model_rejected"
    return "bad_request"


def _classify_failure(exc: BaseException) -> tuple[bool, str]:
    if isinstance(exc, ProcessKilled):
        return False, "aborted"
    if isinstance(exc, PermanentError) and exc.code in _TERMINAL_PERMANENT_CODES:
        return False, exc.code
    if isinstance(exc, _AttemptFailure):
        return _FAILURE_POLICY[exc.kind]
    if isinstance(exc, PermanentError) and exc.code == "provider_response_invalid":
        return True, "bad_output"
    return False, "terminal_error"


def _primary_attempt_timeout(deadline_monotonic: float | None) -> float:
    cap = min(_FALLBACK_ATTEMPT_CAP_SECONDS, _REQUEST_TIMEOUT_SECONDS)
    if deadline_monotonic is None:
        return cap
    usable = deadline_monotonic - time.monotonic() - _FALLBACK_DEADLINE_RESERVE_SECONDS
    if usable < _FALLBACK_MIN_BUDGET_SECONDS:
        raise PermanentError(
            "run_timeout", "insufficient remaining job runtime before provider request"
        )
    if usable < 2.0:
        return min(cap, usable)
    return min(cap, usable / 2.0)


def _fallback_attempt_timeout(deadline_monotonic: float | None) -> float | None:
    cap = min(_FALLBACK_ATTEMPT_CAP_SECONDS, _REQUEST_TIMEOUT_SECONDS)
    if deadline_monotonic is None:
        return cap
    usable = deadline_monotonic - time.monotonic() - _FALLBACK_DEADLINE_RESERVE_SECONDS
    if usable < _FALLBACK_MIN_BUDGET_SECONDS:
        return None
    return min(cap, usable)


def _safe_finish_reason(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    normalized = value.strip().lower()
    return normalized if normalized in _KNOWN_FINISH_REASONS else "unknown"


def _safe_token_count(value: Any) -> int | None:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        return None
    return value


def _sanitize_error_token(value: Any) -> str | None:
    if isinstance(value, bool) or not isinstance(value, str | int):
        return None
    text = str(value).strip()
    if not text or not _SAFE_ERROR_TOKEN_RE.match(text):
        return None
    return text


def _extract_error_tokens(err: dict[str, Any]) -> tuple[str, frozenset[str]]:
    code = _sanitize_error_token(err.get("code"))
    error_type = _sanitize_error_token(err.get("type"))
    tokens = frozenset(t.lower() for t in (code, error_type) if t is not None)
    return code or error_type or "unknown", tokens


def _read_http_error_tokens(exc: urllib.error.HTTPError) -> tuple[str, frozenset[str]]:
    """Read at most 64 KiB from HTTPError, close it, and extract only sanitized code/type."""
    raw = b""
    try:
        raw = exc.read(_MAX_ERROR_BODY_BYTES)
    except Exception:
        raw = b""
    finally:
        with contextlib.suppress(Exception):
            exc.close()
    try:
        body = json.loads(raw)
    except (json.JSONDecodeError, UnicodeDecodeError, TypeError):
        return "unknown", frozenset()
    if isinstance(body, dict) and isinstance(body.get("error"), dict):
        return _extract_error_tokens(body["error"])
    return "unknown", frozenset()


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
    fallback_model: str | None = None,
) -> OpenAIProviderAdapter:
    """Builds the adapter for `provider`; `openai` requires a key, any other name raises."""
    if provider == PROVIDER_OPENAI:
        if not openai_key:
            raise RuntimeError("OpenAI provider requires OPENAI_API_KEY")
        return OpenAIProviderAdapter(
            OpenAIConfig(
                api_key=openai_key,
                base_url=base_url,
                model=model,
                fallback_model=fallback_model,
            )
        )
    raise ValueError(f"Unknown provider adapter: {provider}")
