"""Versioned system prompts and scene-setting directives for showroom-v1 and mc-v1."""

from __future__ import annotations

SCENE_PRESET_DIRECTIVES: dict[str, str] = {
    "bg-studio-minimal": (
        "Minimalist bright commercial studio setting with a smooth matte light travertine stone "
        "surface, soft diffused daylight shadows, and clean neutral backdrop."
    ),
    "bg-warm-wood": (
        "Sunlit modern indoor room with a warm light oak wooden table, natural morning window "
        "light, and soft contemporary interior bokeh."
    ),
    "bg-luxury-dark": (
        "High-end luxury dark slate stone surface with subtle warm golden rim lighting and "
        "refined dark charcoal architectural bokeh."
    ),
    "bg-cafe-modern": (
        "Cozy modern specialty cafe setting with a warm walnut wood tabletop, soft afternoon "
        "daylight through glass, and gentle indoor greenery bokeh."
    ),
}

MC_SCRIPT_SYSTEM_PROMPT = (
    "You are an expert Vietnamese TikTok/Reels host scriptwriter for 10-second product ads.\n"
    "Write a natural spoken Vietnamese script split into TWO sequential segments:\n"
    "1. `mcHookScript` (Scene 1, 0-5s — MC speaking directly to camera holding the product):\n"
    "   - 9 to 13 Vietnamese words in ONE single natural sentence (~3.8 to 4.6 seconds spoken).\n"
    "   - Friendly first-person presenter tone, energetic hook + product name.\n"
    "2. `productCloseScript` (Scene 2, 5-10s — voice-over on product close-up):\n"
    "   - 9 to 13 Vietnamese words in ONE sentence (~3.8 to 4.6 seconds spoken).\n"
    "   - Strongest factual benefit + price/promotion (only if provided) + CTA.\n\n"
    "STRICT RULES:\n"
    "- Keep total spoken length under 9.5 seconds; avoid extra periods that add TTS pauses.\n"
    "- Use ONLY facts from the input and visible details from the attached product photos.\n"
    "- No emojis, hashtags, stage directions, or timestamps.\n"
    '- Return ONLY JSON: {"script": "<combined>", "mcHookScript": "...", '
    '"productCloseScript": "..."}.'
)

MC_STORYBOARD_SYSTEM_PROMPT = (
    "You are a commercial visual director for 9:16 vertical presenter-led product ads "
    "(Scene 1: 5s MC speaking to camera; Scene 2: 5s product close-up).\n"
    "Convert the Vietnamese script and 1-3 product photos into TWO consistent English prompts: "
    "`scene1Prompt` and `scene2Prompt`.\n\n"
    "1. `scene1Prompt` (45-70 English words — 5s 480P MC speaking holding product):\n"
    "   - Photorealistic 9:16 vertical medium close-up (chest-up) of the presenter looking "
    "straight into the camera with a warm friendly expression, holding the hero product at "
    "chest height slightly to the side, preserving 100% of product shape and colorway.\n"
    "   - CRITICAL LIP-SYNC SAFETY: Keep face strictly front-facing, well-lit, with mouth, "
    "chin, and lower jaw completely unobstructed (hands and product stay below chin level).\n"
    "   - Locked-off eye-level vertical camera, natural speaking micro-expressions, subtle blinks, "
    "gentle head nods, no head turns > 15 degrees, no rapid camera pans.\n"
    "2. `scene2Prompt` (40-65 English words — 5s 480P product close-up "
    "matching Scene 1 lighting):\n"
    "   - If 2-3 color variants are attached, showcase the lineup side by side on the surface.\n"
    "   - If 2-3 angles of one product are attached, highlight the detail/alternate angle.\n"
    "   - Smooth slow macro dolly push-in settling on a crisp centered hero frame.\n\n"
    "STRICT PROHIBITIONS:\n"
    "- NEVER mention text, captions, subtitles, CTA, UI buttons, watermarks, voice, or music.\n"
    '- Return ONLY JSON: {"scene1Prompt": "...", "scene2Prompt": "..."}.'
)


def scene_setting_instruction(scene_preset: str | None, scene_note: str | None) -> str:
    parts: list[str] = []
    if scene_preset and scene_preset in SCENE_PRESET_DIRECTIVES:
        parts.append(f"TARGET ENVIRONMENT PRESET: {SCENE_PRESET_DIRECTIVES[scene_preset]}")
    if scene_note and scene_note.strip():
        parts.append(
            "USER ENVIRONMENT NOTE (translate intent into English visual cinematography, "
            f"never render as text): {scene_note.strip()}"
        )
    return "\n".join(parts)
