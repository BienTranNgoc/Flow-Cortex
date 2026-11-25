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
    "You are an expert commercial visual director for dynamic vertical (9:16) showroom product "
    "videos. Convert the approved Vietnamese script and ALL attached product reference images "
    "(1 to 3 photos) into exactly two distinct, high-contrast English video generation prompts, "
    "each for a 5-second 9:16 clip.\n\n"
    "VISUAL CONTINUITY & ORIENTATION (MANDATORY FOR BOTH SCENES):\n"
    "- Treat ALL attached product photos as absolute visual ground truth for silhouette, colors, "
    "dials, logos, materials, and hardware. Never invent unrelated accessories.\n"
    "- Honor any TARGET ENVIRONMENT PRESET or user note. Keep lighting, color grading, and mood "
    "cohesive between Scene 1 and Scene 2 while giving Scene 2 richer architectural depth.\n"
    "- Always keep the product oriented UPRIGHT and RIGHT-SIDE UP facing the camera (never upside "
    "down, never inverted on a wrist, no hands obscuring or twisting the face/logo).\n"
    "- Rest every product firmly on a solid display stand, surface, or stone pedestal with crisp "
    "grounded contact shadows directly beneath its base — NEVER floating in mid-air.\n\n"
    "DYNAMIC MULTI-IMAGE CHOREOGRAPHY (INSPECT ALL ATTACHED IMAGES — AVOID STATIC SHOTS):\n"
    "  * SINGLE PRODUCT IMAGE (1 photo provided):\n"
    "    - Scene 1 (`scene1Prompt`, 0-5s — Hero Studio Orbit Reveal): Product upright and centered "
    "on a clean studio pedestal/stand; camera performs a smooth, clearly visible 35-to-45-degree "
    "orbital arc around the product as a crisp rim light sweeps across edges, bezels, and glossy "
    "contours to showcase 3D volume.\n"
    "    - Scene 2 (`scene2Prompt`, 5-10s — Showroom Context Macro Push-In): Contrasting closer "
    "angle in the rich target showroom environment with softly blurred display shelves and "
    "architectural bokeh behind; smooth macro dolly push-in highlighting surface materials and "
    "front details, settling on a crisp centered hero packshot.\n"
    "  * MULTIPLE COLORWAYS OR FINISH VARIANTS (2-3 photos in different colors):\n"
    "    - Scene 1 (`scene1Prompt`, 0-5s — Solo Hero Variant 1 Orbit): Showcase the PRIMARY "
    "variant (first photo) solo and upright on a clean studio display stand/pedestal so every "
    "detail stays razor-sharp; camera performs a smooth 35-to-45-degree orbital arc with sweeping "
    "studio rim light to reveal full 3D form.\n"
    "    - Scene 2 (`scene2Prompt`, 5-10s — Variant 2 Showroom Reveal): Designed to cross-dissolve "
    "seamlessly from Scene 1 — showcase the SECOND variant (second photo) upright in front on a "
    "matching pedestal inside the full target showroom setting (if a third colorway photo is "
    "attached, stage it softly staggered beside or behind so all attached colorways appear) with "
    "soft display bokeh behind; contrasting macro 45-degree push-in highlighting its finish.\n"
    "  * MULTIPLE ANGLES OF THE SAME PRODUCT (2-3 photos showing front, side, back, or details):\n"
    "    - Scene 1 (`scene1Prompt`, 0-5s — 3D Turntable/Orbit Reveal): Combine geometry from all "
    "angles on a clean studio pedestal; smooth 35-to-45-degree camera orbit from a 3/4 view toward "
    "front hero with sweeping rim light.\n"
    "    - Scene 2 (`scene2Prompt`, 5-10s — Showroom Macro Angle Shift): Contrasting macro "
    "close-up inside the full target showroom setting gliding across tactile details and "
    "alternate-angle hardware, settling onto a balanced upright hero frame.\n\n"
    "CAMERA & PROHIBITIONS:\n"
    "- Keep each prompt concise and vivid (45-75 English words) with ONE clear, continuous motion "
    "per scene. Ensure Scene 1 and Scene 2 use visibly different camera angles and motion "
    "directions so the transition feels cinematic rather than repetitive. Avoid chaotic 360 spins."
    "\n"
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

        def _within_limit(candidate: str) -> bool:
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

        if _within_limit(script):
            return script
        shorten_timeout = 15.0
        if deadline_monotonic is not None:
            remaining = deadline_monotonic - time.monotonic()
            if remaining < 8.0:
                return script
            shorten_timeout = min(15.0, remaining - 3.0)
        shorten_deadline = time.monotonic() + shorten_timeout
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
            prompt = (
                "Shorten this Vietnamese product ad script into EXACTLY TWO short paragraphs "
                "separated by a blank line: Paragraph 1 (9-12 Vietnamese words, MC hook) and "
                "Paragraph 2 (9-12 Vietnamese words, product close + CTA). "
                f"Preserve all facts.{keep_note}\n\nOriginal:\n{script}"
            )
        else:
            prompt = (
                "Shorten this Vietnamese product ad script to 18-26 whitespace-separated words "
                "total (max 2 sentences) so spoken audio stays under 9.2 seconds. Preserve key "
                f"facts.{keep_note}\n\nOriginal:\n{script}"
            )
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
            required_cta = payload.cta.strip() if payload.cta and payload.cta.strip() else None
            cta_preserved = required_cta is None or _contains_exact_cta(shortened, required_cta)
            return shortened.strip() if (_within_limit(shortened) and cta_preserved) else script
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
        max_timeout_seconds: float | None = None,
    ) -> T:
        if abort is not None:
            abort.check()
        fallback_model = self.config.fallback_model
        if max_timeout_seconds is not None:
            budget = max(_FALLBACK_MIN_BUDGET_SECONDS, max_timeout_seconds)
            primary_timeout = (
                max(_FALLBACK_MIN_BUDGET_SECONDS, budget / 2.0)
                if fallback_model is not None
                else budget
            )
        else:
            primary_timeout = (
                _REQUEST_TIMEOUT_SECONDS
                if fallback_model is None
                else _primary_attempt_timeout(deadline_monotonic)
            )
        def _call_request(
            *,
            model: str,
            sys_prompt: str,
            resp_fmt: dict[str, Any],
            attempt_timeout: float,
        ) -> dict[str, Any]:
            if max_timeout_seconds is None:
                return self._request_json(
                    model=model,
                    system_prompt=sys_prompt,
                    user_message=user_content,
                    response_format=resp_fmt,
                    temperature=temperature,
                    timeout=attempt_timeout,
                )
            attempt_deadline = time.monotonic() + attempt_timeout
            if deadline_monotonic is not None:
                attempt_deadline = min(attempt_deadline, deadline_monotonic)
            return self._request_json(
                model=model,
                system_prompt=sys_prompt,
                user_message=user_content,
                response_format=resp_fmt,
                temperature=temperature,
                timeout=attempt_timeout,
                abort=abort,
                attempt_deadline=attempt_deadline,
            )

        try:
            result = _call_request(
                model=self.config.model,
                sys_prompt=system_prompt,
                resp_fmt=response_format,
                attempt_timeout=primary_timeout,
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
            if max_timeout_seconds is not None:
                remaining_budget = (
                    max_timeout_seconds
                    if deadline_monotonic is None
                    else deadline_monotonic - time.monotonic()
                )
                fallback_timeout = (
                    min(max_timeout_seconds, remaining_budget)
                    if remaining_budget >= _FALLBACK_MIN_BUDGET_SECONDS
                    else None
                )
            else:
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
            fallback_result = _call_request(
                model=fallback_model,
                sys_prompt=system_prompt + fallback_schema_instructions,
                resp_fmt=_JSON_OBJECT_RESPONSE_FORMAT,
                attempt_timeout=fallback_timeout,
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
        socket_timeout = timeout
        if attempt_deadline is not None:
            remaining = attempt_deadline - time.monotonic()
            if remaining <= 0:
                raise _AttemptFailure(
                    TransientError("provider request deadline exceeded"),
                    "transport",
                )
            socket_timeout = min(timeout, max(0.001, remaining))
        def _execute_http() -> bytes:
            with urllib.request.urlopen(request, timeout=socket_timeout) as response:
                if attempt_deadline is None:
                    return response.read()
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
                raw = response.read()
                if time.monotonic() >= attempt_deadline:
                    raise VendorJobTimeout("openai", model)
                return raw

        try:
            if attempt_deadline is None:
                payload_bytes = _execute_http()
            else:
                result_box: list[bytes] = []
                error_box: list[BaseException] = []
                done = threading.Event()

                def _worker() -> None:
                    try:
                        result_box.append(_execute_http())
                    except BaseException as exc:
                        error_box.append(exc)
                    finally:
                        done.set()

                thread = threading.Thread(target=_worker, daemon=True)
                thread.start()
                if not done.wait(timeout=max(0.0, attempt_deadline - time.monotonic())):
                    raise VendorJobTimeout("openai", model)
                if error_box:
                    raise error_box[0]
                payload_bytes = result_box[0]
        except VendorJobTimeout as exc:
            log.warning(
                "provider request timed out",
                extra={"model": model},
            )
            raise _AttemptFailure(
                TransientError("provider request timed out"),
                "transport",
            ) from exc
        except urllib.error.HTTPError as exc:
            if attempt_deadline is not None and time.monotonic() >= attempt_deadline:
                with contextlib.suppress(Exception):
                    exc.close()
                raise _AttemptFailure(
                    TransientError("provider request timed out"),
                    "transport",
                ) from exc
            log_code, tokens = _read_http_error_tokens(
                exc,
                attempt_deadline=attempt_deadline,
                abort=abort,
                model=model,
            )
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
            "of one colorway, or a single product photo, and follow the matching branch in the "
            "system prompt.\n"
            "3. Keep each prompt 45-75 English words and NEVER mention text, captions, CTA, "
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
