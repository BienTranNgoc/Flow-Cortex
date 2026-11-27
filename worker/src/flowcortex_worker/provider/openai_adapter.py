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
import threading
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
from .edge_adapter import split_mc_script_segments
from .prompts.templates import MC_STORYBOARD_SYSTEM_PROMPT, scene_setting_instruction
from .vendor_jobs import VendorJobTimeout, read_bounded_response

log = logging.getLogger("flowcortex_provider_worker.openai")


class _NoopAbort:
    def check(self) -> None:
        return None


_NOOP_ABORT = _NoopAbort()

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
_VIDEO_NOT_IMPLEMENTED = "OpenAI video generation is not implemented"
_REQUEST_TIMED_OUT_MSG = "provider request timed out"

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
    "You are an expert commercial visual director for luxury vertical (9:16) showroom product "
    "videos. Convert the approved Vietnamese script and ALL attached product reference images "
    "(1 to 3 photos) into a seamless, high-energy continuous 10-second showroom master shot "
    "(`scene1Prompt`) plus a matching hero close-up fallback prompt (`scene2Prompt`).\n\n"
    "VISUAL CONTINUITY & ORIENTATION (MANDATORY):\n"
    "- Treat ALL attached product photos as absolute visual ground truth for silhouette, colors, "
    "dials, logos, materials, and hardware. Never invent unrelated accessories.\n"
    "- Honor any TARGET ENVIRONMENT PRESET or user note. By default use a pitch-dark luxury "
    "gallery with overhead circular ceiling spotlight beams, matte black cylindrical pedestals "
    "with thin glowing white LED rings, and deep dark architectural shadows.\n"
    "- Always keep every product oriented UPRIGHT and RIGHT-SIDE UP facing the camera (never "
    "upside down, never inverted on a wrist, no hands obscuring or twisting the face/logo).\n"
    "- HERO FRONT-FACE PRIORITY: Keep the product's primary front face (watch dial/screen, "
    "bottle label, logo, front contour) prominently visible throughout the shot. NEVER linger "
    "on the rear, clasp, strap back, or empty gap between pedestals during transitions.\n"
    "- Rest every product firmly on a solid display stand or cylindrical pedestal with crisp "
    "grounded contact shadows directly beneath its base — NEVER floating in mid-air.\n\n"
    "CONTINUOUS 10-SECOND MASTER SHOT CHOREOGRAPHY FOR `scene1Prompt` (INSPECT ALL IMAGES):\n"
    "Write `scene1Prompt` (75-120 English words) as ONE fluid, uninterrupted 10-second camera "
    "journey with explicit temporal pacing cues ('First 2.5 seconds...', 'From 2.5 to 5 "
    "seconds...', 'From 5 to 10 seconds...'):\n"
    "  * SINGLE PRODUCT IMAGE (1 photo provided):\n"
    "    - First 2.5 seconds: Focused key light illuminates the upright product on a display "
    "pedestal matching the target setting; brisk smooth dolly-in to a crisp front close-up.\n"
    "    - From 2.5 to 5.5 seconds: Glide around a sharp 45-degree front-quarter macro angle "
    "tracing polished metal edges, dial/front details, and surface texture as rim light sweeps.\n"
    "    - From 5.5 to 10 seconds: Pull back smoothly as the product rotates slightly on its "
    "pedestal to a centered front hero pose, punctuated by a crisp optical lens flare glint.\n"
    "  * MULTIPLE COLORWAYS OR FINISH VARIANTS (2-3 photos in different colors):\n"
    "    - CRITICAL ANTI-MORPHING RULE: Stage each variant on its OWN physically separate "
    "display pedestal or spot matching the target environment preset (defaulting to matte black "
    "cylindrical pedestals with thin LED rings when no preset overrides it), with clear empty "
    "space between them — NEVER morph, transform, or merge one variant into another.\n"
    "    - If 2 colorways:\n"
    "      · First 2.5 seconds: Focused key light illuminates the FIRST variant facing forward "
    "on the front-left pedestal/spot (with the SECOND variant staggered slightly behind-right "
    "on its own separate pedestal, also facing forward); brisk dolly-in to Variant 1's front "
    "face and hero details.\n"
    "      · From 2.5 to 5 seconds: Brisk diagonal camera glide and rack-focus (< 0.5s pass "
    "past Variant 1's softly blurred foreground edge) landing directly on the FRONT FACE and "
    "sharp 3/4 front macro angle of the SECOND variant on its separate right pedestal — never "
    "lingering on rear straps, clasps, or empty gaps.\n"
    "      · From 5 to 10 seconds: Smooth pull-back revealing BOTH separate pedestals/spots side "
    "by side in perfect symmetry as BOTH upright front-facing variants rotate gently in unison "
    "into centered front hero poses, catching a crisp optical lens flare glint.\n"
    "    - If 3 colorways:\n"
    "      · First 2 seconds: Brisk dolly-in onto the FRONT FACE of the FIRST variant on the "
    "front-left pedestal/spot.\n"
    "      · From 2 to 6 seconds: Brisk diagonal glides and rack-focus passes (< 0.5s each) "
    "directly from Variant 1's front face to Variant 2's front face and Variant 3's front face "
    "on separate staggered pedestals — never lingering on backs, clasps, or empty gaps.\n"
    "      · From 6 to 10 seconds: Smooth pull-back revealing ALL THREE separate pedestals/spots "
    "in a balanced front-facing arc rotating gently in unison with a crisp optical lens flare.\n"
    "  * MULTIPLE ANGLES OF THE SAME PRODUCT (2-3 photos showing front, side, back, or details):\n"
    "    - First 2.5 seconds: Overhead spotlight brisk dolly-in onto the upright product.\n"
    "    - From 2.5 to 6 seconds: Continuous 120-degree motorized turntable rotation and macro "
    "glide revealing side hardware and alternate-angle craftsmanship.\n"
    "    - From 6 to 10 seconds: Pull back gently into a balanced centered front hero packshot "
    "with a subtle optical lens flare glint.\n\n"
    "SECONDARY PROMPT (`scene2Prompt`, 45-65 English words):\n"
    "- Provide a distinct cohesive showroom macro hero packshot in the exact same lighting and "
    "pedestal setting for backward-compatible two-clip callers.\n\n"
    "STRICT PROHIBITIONS:\n"
    "- NEVER mention text, graphics, or UI elements ('text', 'caption', 'subtitle', 'CTA', "
    "'overlay', 'watermark', 'font', 'price tag', 'button', 'no text') anywhere in prompts.\n"
    "- Do not mention voice, speech, sound effects, or music.\n"
    "- Return only one valid JSON object with exactly two distinct string fields: "
    "`scene1Prompt` and `scene2Prompt`."
)

_SYSTEM_PROMPT = (
    "You are an expert Vietnamese commercial copywriter for luxury 10-second vertical showroom ads "
    "(TikTok, Reels, Shorts).\n"
    "Write a crisp, high-impact Vietnamese voice-over script structured in TWO balanced beats "
    "(Beat 1 for Scene 1: 0-5s; Beat 2 for Scene 2: 5-10s).\n\n"

    "OUTPUT FORMAT:\n"
    "- Return ONLY a valid JSON object with exactly one string field: `script`.\n"
    "- Do not include markdown, explanations, or extra fields.\n\n"

    "TWO-BEAT SCRIPT STRUCTURE (MAXIMUM 2 SENTENCES TOTAL):\n"
    "1. BEAT 1 (Scene 1, 0-5s — 9 to 13 words): Open with a compelling hook or the SINGLE "
    "strongest standout benefit/desire (do NOT open with a dry list of raw specs or long model "
    "codes).\n"
    "2. BEAT 2 (Scene 2, 5-10s — 9 to 13 words): Product name + ONE decisive benefit (plus every "
    "explicitly provided price and promotion — include both when both are given) + the exact CTA "
    "(or a natural short Vietnamese CTA like 'Khám phá ngay!' if none provided).\n\n"

    "TTS-FRIENDLY VIETNAMESE & DURATION RULES:\n"
    "- Target 18-26 Vietnamese whitespace-separated words in total (~7.5 to 9.2 seconds spoken).\n"
    "- Select at most TWO strongest selling points — never cram 4-5 technical specs into 10s.\n"
    "- Avoid stacking multiple English abbreviations or decimal units back-to-back so Vietnamese "
    "TTS sounds effortless and premium.\n"
    "- Use at most 2 sentences total so TTS does not insert choppy mid-sentence pauses.\n"
    "- No emojis, hashtags, stage directions, or timestamps.\n\n"

    "FACTUAL ACCURACY:\n"
    "- Use ONLY product information explicitly provided in the input and visible details from the "
    "attached product photos. Never invent features, materials, prices, discounts, or guarantees.\n"
    "- If a CTA is provided, preserve it verbatim."
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
        script = self._run_with_fallback(
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
        return self._shorten_script_if_needed(
            script,
            payload,
            abort=abort,
            deadline_monotonic=deadline_monotonic,
        )

    def _shorten_script_if_needed(
        self,
        script: str,
        payload: ProductAdPayload,
        *,
        abort: ScriptAbort | None = None,
        deadline_monotonic: float | None = None,
    ) -> str:
        is_mc = payload.templateId == "mc-v1"
        if _script_within_limit(script, is_mc):
            return script
        budget = _shorten_budget(deadline_monotonic)
        if budget is None:
            return script
        shorten_timeout, shorten_deadline = budget
        prompt = _build_shorten_prompt(script, payload, is_mc)
        try:
            shortened = self._run_with_fallback(
                operation="generate_script_shorten",
                system_prompt=_SYSTEM_PROMPT,
                fallback_schema_instructions=_SCRIPT_FALLBACK_SCHEMA_INSTRUCTIONS,
                user_content=prompt,
                response_format=_SCRIPT_RESPONSE_FORMAT,
                validate=_validate_script_response,
                temperature=0.2,
                abort=abort,
                deadline_monotonic=shorten_deadline,
                max_timeout_seconds=shorten_timeout,
            )
            return _accept_shortened_script(script, shortened, payload.cta, is_mc)
        except (PermanentError, TransientError):
            log.warning("Script auto-shorten failed; returning original draft")
            return script

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
        if len(images) >= 2:
            script_text = (
                f"{script_text}\n\n"
                "If a background/environment photo is attached alongside the product photos, "
                "incorporate its setting, lighting, and surface textures into both scenes."
            )
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
        raise PermanentError("provider_capability_unavailable", _VIDEO_NOT_IMPLEMENTED)

    def submit_video(self, request: VideoRequest) -> str:
        raise PermanentError("provider_capability_unavailable", _VIDEO_NOT_IMPLEMENTED)

    def await_video(self, task_id: str, abort: VideoAbort) -> VideoArtifact:
        raise PermanentError("provider_capability_unavailable", _VIDEO_NOT_IMPLEMENTED)

    def probe_video(self, task_id: str) -> VendorProbe:
        raise PermanentError("provider_capability_unavailable", _VIDEO_NOT_IMPLEMENTED)

    def vendor_scope(self) -> dict[str, str]:
        raise PermanentError("provider_capability_unavailable", _VIDEO_NOT_IMPLEMENTED)

    def _dispatch_request(
        self,
        *,
        model: str,
        system_prompt: str,
        user_content: str | list[dict[str, Any]],
        response_format: dict[str, Any],
        temperature: float,
        attempt_timeout: float,
        abort: ScriptAbort | None,
        deadline_monotonic: float | None,
        bounded: bool,
    ) -> dict[str, Any]:
        if not bounded:
            return self._request_json(
                model=model,
                system_prompt=system_prompt,
                user_message=user_content,
                response_format=response_format,
                temperature=temperature,
                timeout=attempt_timeout,
            )
        attempt_deadline = time.monotonic() + attempt_timeout
        if deadline_monotonic is not None:
            attempt_deadline = min(attempt_deadline, deadline_monotonic)
        return self._request_json(
            model=model,
            system_prompt=system_prompt,
            user_message=user_content,
            response_format=response_format,
            temperature=temperature,
            timeout=attempt_timeout,
            abort=abort,
            attempt_deadline=attempt_deadline,
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
        max_timeout_seconds: float | None = None,
    ) -> T:
        if abort is not None:
            abort.check()
        fallback_model = self.config.fallback_model
        bounded = max_timeout_seconds is not None
        primary_timeout = _resolve_primary_timeout(
            fallback_model, deadline_monotonic, max_timeout_seconds
        )
        try:
            result = self._dispatch_request(
                model=self.config.model,
                system_prompt=system_prompt,
                user_content=user_content,
                response_format=response_format,
                temperature=temperature,
                attempt_timeout=primary_timeout,
                abort=abort,
                deadline_monotonic=deadline_monotonic,
                bounded=bounded,
            )
            return validate(result)
        except Exception as exc:
            fallback_timeout, reason = self._prepare_fallback(
                exc=exc,
                fallback_model=fallback_model,
                operation=operation,
                abort=abort,
                deadline_monotonic=deadline_monotonic,
                max_timeout_seconds=max_timeout_seconds,
            )

        try:
            fallback_result = self._dispatch_request(
                model=fallback_model,  # type: ignore[arg-type]
                system_prompt=system_prompt + fallback_schema_instructions,
                user_content=user_content,
                response_format=_JSON_OBJECT_RESPONSE_FORMAT,
                temperature=temperature,
                attempt_timeout=fallback_timeout,
                abort=abort,
                deadline_monotonic=deadline_monotonic,
                bounded=bounded,
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

    def _prepare_fallback(
        self,
        *,
        exc: Exception,
        fallback_model: str | None,
        operation: str,
        abort: ScriptAbort | None,
        deadline_monotonic: float | None,
        max_timeout_seconds: float | None,
    ) -> tuple[float, str]:
        if fallback_model is None:
            _reraise_unwrapped(exc)
        allow, reason = _classify_failure(exc)
        if not allow:
            _reraise_unwrapped(exc)
        if abort is not None:
            abort.check()
        fallback_timeout = _resolve_fallback_timeout(deadline_monotonic, max_timeout_seconds)
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
        return fallback_timeout, reason

    def _request_json(
        self,
        *,
        model: str,
        system_prompt: str,
        user_message: str | list[dict[str, Any]],
        response_format: dict[str, Any],
        temperature: float,
        timeout: float,
        abort: ScriptAbort | None = None,
        attempt_deadline: float | None = None,
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
        url = f"{self.config.base_url.rstrip('/')}/chat/completions"
        request = urllib.request.Request(
            url,
            data=body,
            method="POST",
            headers={
                "Authorization": f"Bearer {self.config.api_key}",
                "Content-Type": "application/json",
            },
        )
        payload_bytes = _fetch_http_payload(
            request,
            timeout=timeout,
            attempt_deadline=attempt_deadline,
            abort=abort,
            model=model,
        )
        parsed = _decode_json_object(payload_bytes)
        return _extract_choice_result(parsed, model)

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
            "of one colorway, or a single product photo, and follow the matching branch in the "
            "system prompt.\n"
            "3. Follow the exact word-count and choreography rules in the system prompt, and "
            "NEVER mention text, captions, CTA, overlays, or audio."
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
_CODE_FENCE_RE = re.compile(r"```(?:json)?([^`]+)```", re.IGNORECASE)
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


def _script_within_limit(candidate: str, is_mc: bool) -> bool:
    stripped = candidate.strip()
    if not stripped:
        return False
    if is_mc:
        parts = split_mc_script_segments(stripped)
        return (
            parts is not None
            and len(parts[0].split()) <= 14
            and len(parts[1].split()) <= 14
            and len(stripped.split()) <= 28
        )
    return len(stripped.split()) <= 26


def _shorten_budget(deadline_monotonic: float | None) -> tuple[float, float] | None:
    shorten_timeout = 15.0
    if deadline_monotonic is not None:
        remaining = deadline_monotonic - time.monotonic()
        if remaining < 8.0:
            return None
        shorten_timeout = min(15.0, remaining - 3.0)
    return shorten_timeout, time.monotonic() + shorten_timeout


def _build_shorten_prompt(script: str, payload: ProductAdPayload, is_mc: bool) -> str:
    keep_parts: list[str] = []
    if payload.price and payload.price.strip():
        keep_parts.append(f"price '{payload.price.strip()}'")
    if payload.promotion and payload.promotion.strip():
        keep_parts.append(f"promotion '{payload.promotion.strip()}'")
    if payload.cta and payload.cta.strip():
        keep_parts.append(f"exact CTA '{payload.cta.strip()}'")
    keep_note = (
        f" Keep {' and '.join(keep_parts)}; trim intro/benefits first if needed."
        if keep_parts
        else ""
    )
    if is_mc:
        return (
            "Shorten this Vietnamese product ad script into EXACTLY TWO short paragraphs "
            "separated by a blank line: Paragraph 1 (9-12 Vietnamese words, MC hook) and "
            "Paragraph 2 (9-12 Vietnamese words, product close + CTA). "
            f"Preserve all facts.{keep_note}\n\nOriginal:\n{script}"
        )
    return (
        "Shorten this Vietnamese product ad script to 18-26 whitespace-separated words "
        "total (max 2 sentences) so spoken audio stays under 9.2 seconds. Preserve key "
        f"facts.{keep_note}\n\nOriginal:\n{script}"
    )


def _accept_shortened_script(
    original: str, shortened: str, cta: str | None, is_mc: bool
) -> str:
    required_cta = cta.strip() if cta and cta.strip() else None
    cta_preserved = required_cta is None or _contains_exact_cta(shortened, required_cta)
    if _script_within_limit(shortened, is_mc) and cta_preserved:
        return shortened.strip()
    return original


def _resolve_primary_timeout(
    fallback_model: str | None,
    deadline_monotonic: float | None,
    max_timeout_seconds: float | None,
) -> float:
    if max_timeout_seconds is not None:
        budget = max(_FALLBACK_MIN_BUDGET_SECONDS, max_timeout_seconds)
        if fallback_model is not None:
            return max(_FALLBACK_MIN_BUDGET_SECONDS, budget / 2.0)
        return budget
    if fallback_model is None:
        return _REQUEST_TIMEOUT_SECONDS
    return _primary_attempt_timeout(deadline_monotonic)


def _resolve_fallback_timeout(
    deadline_monotonic: float | None,
    max_timeout_seconds: float | None,
) -> float | None:
    if max_timeout_seconds is None:
        return _fallback_attempt_timeout(deadline_monotonic)
    remaining_budget = (
        max_timeout_seconds
        if deadline_monotonic is None
        else deadline_monotonic - time.monotonic()
    )
    if remaining_budget < _FALLBACK_MIN_BUDGET_SECONDS:
        return None
    return min(max_timeout_seconds, remaining_budget)


def _open_and_read_http(
    request: urllib.request.Request,
    *,
    socket_timeout: float,
    attempt_deadline: float | None,
    abort: ScriptAbort | None,
    model: str,
) -> bytes:
    with urllib.request.urlopen(request, timeout=socket_timeout) as response:
        if attempt_deadline is None:
            return bytes(response.read())
        if isinstance(response, http.client.HTTPResponse):
            return read_bounded_response(
                response,
                max_bytes=4 * 1024 * 1024,
                provider="openai",
                task_id=model,
                abort=abort if abort is not None else _NOOP_ABORT,
                deadline=attempt_deadline,
            )
        if time.monotonic() >= attempt_deadline:
            raise VendorJobTimeout("openai", model)
        raw = bytes(response.read())
        if time.monotonic() >= attempt_deadline:
            raise VendorJobTimeout("openai", model)
        return raw


def _run_bounded_http_thread(
    request: urllib.request.Request,
    *,
    socket_timeout: float,
    attempt_deadline: float,
    abort: ScriptAbort | None,
    model: str,
) -> bytes:
    result_box: list[bytes] = []
    error_box: list[Exception] = []
    done = threading.Event()

    def _worker() -> None:
        try:
            result_box.append(
                _open_and_read_http(
                    request,
                    socket_timeout=socket_timeout,
                    attempt_deadline=attempt_deadline,
                    abort=abort,
                    model=model,
                )
            )
        except Exception as exc:
            error_box.append(exc)
        finally:
            done.set()

    thread = threading.Thread(target=_worker, daemon=True)
    thread.start()
    if not done.wait(timeout=max(0.0, attempt_deadline - time.monotonic())):
        raise VendorJobTimeout("openai", model)
    if error_box:
        raise error_box[0]
    return result_box[0]


def _fetch_http_payload(
    request: urllib.request.Request,
    *,
    timeout: float,
    attempt_deadline: float | None,
    abort: ScriptAbort | None,
    model: str,
) -> bytes:
    socket_timeout = timeout
    if attempt_deadline is not None:
        remaining = attempt_deadline - time.monotonic()
        if remaining <= 0:
            raise _AttemptFailure(
                TransientError("provider request deadline exceeded"),
                "transport",
            )
        socket_timeout = min(timeout, max(0.001, remaining))
    try:
        if attempt_deadline is None:
            return _open_and_read_http(
                request,
                socket_timeout=socket_timeout,
                attempt_deadline=None,
                abort=abort,
                model=model,
            )
        return _run_bounded_http_thread(
            request,
            socket_timeout=socket_timeout,
            attempt_deadline=attempt_deadline,
            abort=abort,
            model=model,
        )
    except VendorJobTimeout as exc:
        log.warning(_REQUEST_TIMED_OUT_MSG, extra={"model": model})
        raise _AttemptFailure(TransientError(_REQUEST_TIMED_OUT_MSG), "transport") from exc
    except urllib.error.HTTPError as exc:
        _handle_http_error(exc, attempt_deadline=attempt_deadline, abort=abort, model=model)
    except (http.client.HTTPException, OSError) as exc:
        log.warning(
            "provider connection failed",
            extra={"errorType": type(exc).__name__, "model": model},
        )
        raise _AttemptFailure(
            TransientError(f"provider connection failed: {type(exc).__name__}"),
            "transport",
        ) from exc


def _handle_http_error(
    exc: urllib.error.HTTPError,
    *,
    attempt_deadline: float | None,
    abort: ScriptAbort | None,
    model: str,
) -> NoReturn:
    if attempt_deadline is not None and time.monotonic() >= attempt_deadline:
        with contextlib.suppress(Exception):
            exc.close()
        raise _AttemptFailure(TransientError(_REQUEST_TIMED_OUT_MSG), "transport") from exc
    log_code, tokens = _read_http_error_tokens(
        exc, attempt_deadline=attempt_deadline, abort=abort, model=model
    )
    log.warning(
        "provider HTTP request failed",
        extra={"status": exc.code, "providerErrorCode": log_code, "model": model},
    )
    kind = _classify_http_error(exc.code, tokens)
    if kind == "retryable_http":
        error: PermanentError | TransientError = TransientError(
            f"provider HTTP {exc.code}",
            retry_after_seconds=_retry_after_seconds(exc),
        )
    else:
        error = PermanentError(
            "provider_request_failed",
            f"provider rejected the request with HTTP {exc.code}",
        )
    raise _AttemptFailure(error, kind) from exc


def _decode_json_object(payload_bytes: bytes) -> dict[str, Any]:
    try:
        parsed = json.loads(payload_bytes)
    except (json.JSONDecodeError, UnicodeDecodeError) as exc:
        raise _AttemptFailure(
            PermanentError("provider_response_invalid", "provider response was not valid JSON"),
            "bad_output",
        ) from exc
    if not isinstance(parsed, dict):
        raise _AttemptFailure(
            PermanentError("provider_response_invalid", "provider response was not a JSON object"),
            "bad_output",
        )
    return parsed


def _raise_for_body_error(err: dict[str, Any], model: str) -> NoReturn:
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
    if tokens & _AUTH_ERROR_CODES:
        err_kind: _FailureKind = "auth_failure"
    elif tokens & _MODEL_REJECTION_CODES:
        err_kind = "model_rejected"
    else:
        err_kind = "bad_request"
    raise _AttemptFailure(
        PermanentError(
            "provider_request_failed", "provider returned an error in HTTP 200 response"
        ),
        err_kind,
    )


def _extract_choice_result(parsed: dict[str, Any], model: str) -> dict[str, Any]:
    choices = parsed.get("choices")
    if not (isinstance(choices, list) and choices):
        if isinstance(parsed.get("error"), dict):
            _raise_for_body_error(parsed["error"], model)
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
    raw = _extract_message_content(choice, safe_finish_reason, model)
    return _parse_or_fail_content(raw, parsed, safe_finish_reason, model)


def _extract_message_content(
    choice: dict[str, Any], safe_finish_reason: str | None, model: str
) -> Any:
    message = choice.get("message")
    if not isinstance(message, dict):
        raise _AttemptFailure(
            PermanentError("provider_response_invalid", "provider response has no message object"),
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
            PermanentError("provider_response_invalid", "provider response has no message content"),
            "bad_output",
        )
    return message["content"]


def _parse_or_fail_content(
    raw: Any,
    parsed: dict[str, Any],
    safe_finish_reason: str | None,
    model: str,
) -> dict[str, Any]:
    try:
        result = _parse_structured_content(raw)
    except ValueError as exc:
        usage = parsed.get("usage")
        prompt_tokens = (
            _safe_token_count(usage.get("prompt_tokens")) if isinstance(usage, dict) else None
        )
        raw_text = _extract_text_content(raw)
        transient_hint = _transient_hint_for_text(raw_text) if raw_text is not None else None
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


def _try_json_loads(text: str) -> tuple[bool, Any]:
    try:
        return True, json.loads(text)
    except json.JSONDecodeError:
        return False, None


def _scan_json_object(cleaned: str) -> dict[str, Any] | None:
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
    return None


def _parse_structured_content(raw: Any) -> Any:
    """Parse JSON from `choices[0].message.content`, tolerating proxy fences or preamble text."""
    if isinstance(raw, dict):
        return raw
    text = _extract_text_content(raw)
    if text is None or not text.strip():
        raise ValueError("content is empty or not text")
    stripped = text.strip()
    ok, parsed = _try_json_loads(stripped)
    if ok:
        return parsed

    cleaned = _THINK_BLOCK_RE.sub("", stripped).strip()
    if cleaned and cleaned != stripped:
        ok, parsed = _try_json_loads(cleaned)
        if ok:
            return parsed

    for match in _CODE_FENCE_RE.finditer(cleaned):
        candidate = match.group(1).strip()
        if candidate:
            ok, parsed = _try_json_loads(candidate)
            if ok:
                return parsed

    scanned = _scan_json_object(cleaned)
    if scanned is not None:
        return scanned
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


def _contains_exact_cta(text: str, cta: str) -> bool:
    prefix = r"(?<!\w)" if cta[0].isalnum() or cta[0] == "_" else ""
    suffix = r"(?!\w)" if cta[-1].isalnum() or cta[-1] == "_" else ""
    return re.search(f"{prefix}{re.escape(cta)}{suffix}", text) is not None


def _read_http_error_tokens(
    exc: urllib.error.HTTPError,
    *,
    attempt_deadline: float | None = None,
    abort: ScriptAbort | None = None,
    model: str = "openai",
) -> tuple[str, frozenset[str]]:
    """Read at most 64 KiB from HTTPError, close it, and extract only sanitized code/type."""
    raw = b""
    try:
        if attempt_deadline is not None and isinstance(exc.fp, http.client.HTTPResponse):
            raw = read_bounded_response(
                exc.fp,
                max_bytes=_MAX_ERROR_BODY_BYTES,
                provider="openai",
                task_id=model,
                abort=abort if abort is not None else _NOOP_ABORT,
                deadline=attempt_deadline,
            )
        else:
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
