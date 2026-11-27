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
        "Pitch-dark minimalist luxury gallery with overhead circular ceiling spotlight beams, "
        "sleek matte black cylindrical pedestals with thin glowing white LED rings, subtle floor "
        "reflections, and deep dark architectural shadows."
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
    "1. `scene1Prompt` (55-85 English words — 5s 480P MC base clip animated from the exact "
    "starting keyframe before lip-sync):\n"
    "   - Photorealistic 9:16 vertical medium close-up (chest-up) animating directly from the "
    "starting keyframe: presenter holds the exact hero product steady at chest height slightly to "
    "the side, preserving 100% of product shape, surface details, and colorway for ALL 5s.\n"
    "   - STRICT PROP & HAND LOCK: Keep the held product visibly intact in the presenter's hand "
    "for the entire 5 seconds — NEVER lower, hide, swap, or morph the product into paper, a card, "
    "a letter, a note, a phone, or any other object.\n"
    "   - STRICT EYE-CONTACT & CRITICAL LIP-SYNC SAFETY: Presenter maintains continuous, unbroken "
    "direct eye contact straight into the camera lens for ALL 5 seconds — NEVER look down, never "
    "tilt the head downward, never read anything, and keep head angle within 10 degrees. Keep "
    "mouth, chin, and lower jaw completely unobstructed (hands and product stay below chin level) "
    "with a warm closed-mouth smile, subtle natural blinks, and NO speaking or mouth movement so "
    "post-process lip-sync applies cleanly.\n"
    "   - STRICT STATIC CAMERA & BACKGROUND LOCK: 100% static locked-off eye-level tripod camera. "
    "Preserve the exact background, lighting, and color tone from frame 1 unchanged for all 5 "
    "seconds — NEVER pan, cut, darken, or drift into an unrelated scene, rain, or vehicle.\n"
    "2. `scene2Prompt` (40-65 English words — 5s 480P product close-up "
    "matching Scene 1 lighting):\n"
    "   - Product resting firmly on the tabletop surface with realistic grounded contact shadow, "
    "never floating or levitating.\n"
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
