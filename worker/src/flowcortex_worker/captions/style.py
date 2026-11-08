"""Resolves a caption style spec (readable values on the wire) into the fields of an ASS Style."""

from __future__ import annotations

import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any

from .fonts import ALLOWED_FONTS, ASS_FONT_NAME

_HEX_RE = re.compile(r"^#([0-9A-Fa-f]{6})$")

_ALIGN = {"top": 8, "middle": 5, "bottom": 2}
_HIGHLIGHT_MODES = ("sweep", "jump", "off")
# BorderStyle 3 draws its box with OutlineColour (BackColour draws nothing) and a zero pad draws
# no box, so each preset is a colour + transparency + pad bundle.
_BACKDROPS: dict[str, tuple[str, int, int] | None] = {
    "none": None,
    "dark_soft": ("#000000", 60, 6),
    "dark_solid": ("#000000", 0, 6),
    "light_solid": ("#FFFFFF", 0, 6),
}
_OUTLINE_W = {"none": 0, "thin": 2, "medium": 5, "thick": 9}
_CUSTOM_BOX_PAD = 6
_SHADOW_PCT = 4
_NO_SHADOW_COLOUR = "&H00000000"
_DEFAULT_SHADOW = (1, _NO_SHADOW_COLOUR)
_DEFAULT_SIZE_PCT = 84 * 100.0 / 1920
_DEFAULT_MARGIN_PCT = 300 * 100.0 / 1920


@dataclass(frozen=True)
class CaptionStyle:
    font: str
    size: int
    primary: str
    secondary: str
    outline_colour: str
    outline: int
    shadow: int
    back_colour: str
    border_style: int
    bold: int
    align: int
    margin_v: int
    mode: str
    max_syllables: int


def _is_number(value: object) -> bool:
    return isinstance(value, int | float) and not isinstance(value, bool)


def ass_color(hex_rgb: Any, transparency_pct: Any = 0) -> str:
    """`#RRGGBB` to `&HAABBGGRR`. ASS alpha is transparency (00 opaque, FF invisible), and the
    percent is truncated, not rounded."""
    match = _HEX_RE.match(hex_rgb) if isinstance(hex_rgb, str) else None
    if match is None:
        raise ValueError(f"colour must be #RRGGBB (6 hex digits), got: {hex_rgb!r}")
    if not _is_number(transparency_pct) or not 0 <= transparency_pct <= 100:
        raise ValueError(f"transparency must be within 0-100, got: {transparency_pct!r}")
    digits = match.group(1)
    alpha = int(transparency_pct * 255 / 100)
    return f"&H{alpha:02X}{digits[4:6]}{digits[2:4]}{digits[0:2]}".upper()


def _pick(
    spec: Mapping[str, Any],
    key: str,
    default: Any,
    allowed: Sequence[str] | None = None,
) -> Any:
    value = spec.get(key)
    if value is None or value == "":
        return default
    if allowed is not None and value not in allowed:
        raise ValueError(f"{key} is invalid: {value!r}. Allowed: {', '.join(allowed)}")
    return value


def _resolve_shadow(spec: Mapping[str, Any], size: int) -> tuple[int, str]:
    """Absent `shadow`: 1 px black. `null`: none. `{color}`: 4 % of the font size."""
    if "shadow" not in spec:
        return _DEFAULT_SHADOW
    shadow = spec["shadow"]
    if shadow is None:
        return 0, _NO_SHADOW_COLOUR
    if not isinstance(shadow, Mapping) or set(shadow) != {"color"}:
        raise ValueError(f"shadow must be {{color: #RRGGBB}} or null, got: {shadow!r}")
    return max(1, round(_SHADOW_PCT * size / 100)), ass_color(shadow["color"])


def resolve_style(spec: Mapping[str, Any] | None, height: int) -> CaptionStyle:
    """Validate every key and resolve it against the video height. Raises `ValueError`."""
    if spec is None:
        spec = {}
    if not isinstance(spec, Mapping):
        raise ValueError(f"style must be an object, got: {type(spec).__name__}")

    font = _pick(spec, "fontFamily", ASS_FONT_NAME, ALLOWED_FONTS)

    size_pct = _pick(spec, "fontSizePct", _DEFAULT_SIZE_PCT)
    if not _is_number(size_pct) or not 1 <= size_pct <= 20:
        raise ValueError(f"fontSizePct must be within 1-20 (% of height), got: {size_pct!r}")

    margin_pct = _pick(spec, "marginPct", _DEFAULT_MARGIN_PCT)
    if not _is_number(margin_pct) or not 0 <= margin_pct <= 45:
        raise ValueError(f"marginPct must be within 0-45 (% of height), got: {margin_pct!r}")

    mode = _pick(spec, "highlightMode", "sweep", _HIGHLIGHT_MODES)
    text_colour = _pick(spec, "textColor", "#FFFFFF")
    highlight_colour = _pick(spec, "highlightColor", "#FFE000")
    # \kf and \k sweep SecondaryColour to PrimaryColour, so Primary is the already-spoken part.
    # A line with no karaoke tag is drawn in Primary, which must then equal the text colour.
    highlight = ass_color(highlight_colour)
    text = ass_color(text_colour)
    primary, secondary = (text, text) if mode == "off" else (highlight, text)

    size = max(1, round(size_pct * height / 100))
    backdrop = _pick(spec, "backdrop", "none", (*_BACKDROPS, "custom"))
    backdrop_hex = spec.get("backdropColor")
    if (backdrop == "custom") != (backdrop_hex is not None):
        raise ValueError(
            "backdropColor must be present exactly when backdrop is custom, got: "
            f"backdrop={backdrop!r}, backdropColor={backdrop_hex!r}"
        )
    # Outline and shadow colours are validated even under a backdrop (a bad value is bad) but
    # only drawn without one: an ASS style cannot draw both a box and an outline.
    text_outline_colour = ass_color(_pick(spec, "outlineColor", "#000000"))
    text_shadow = _resolve_shadow(spec, size)
    text_outline = _OUTLINE_W[_pick(spec, "outlineWidth", "medium", tuple(_OUTLINE_W))]
    preset = _BACKDROPS.get(backdrop)
    if backdrop == "custom":
        outline_colour, outline, border_style = ass_color(backdrop_hex), _CUSTOM_BOX_PAD, 3
        shadow, back_colour = _DEFAULT_SHADOW
    elif preset is not None:
        box_hex, box_transparency, pad = preset
        outline_colour, outline, border_style = ass_color(box_hex, box_transparency), pad, 3
        shadow, back_colour = _DEFAULT_SHADOW
    else:
        outline_colour, border_style = text_outline_colour, 1
        outline = text_outline
        shadow, back_colour = text_shadow

    syllables = _pick(spec, "maxSyllablesPerLine", 4)
    if not isinstance(syllables, int) or isinstance(syllables, bool) or not 2 <= syllables <= 8:
        raise ValueError(f"maxSyllablesPerLine must be an integer within 2-8, got: {syllables!r}")

    return CaptionStyle(
        font=font,
        size=size,
        primary=primary,
        secondary=secondary,
        outline_colour=outline_colour,
        outline=outline,
        shadow=shadow,
        back_colour=back_colour,
        border_style=border_style,
        bold=-1 if _pick(spec, "bold", True) else 0,
        align=_ALIGN[_pick(spec, "position", "bottom", tuple(_ALIGN))],
        margin_v=max(0, round(margin_pct * height / 100)),
        mode=mode,
        max_syllables=syllables,
    )
