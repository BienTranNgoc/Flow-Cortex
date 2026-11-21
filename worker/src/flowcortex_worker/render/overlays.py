"""Overlay printing: text overlays as ASS styles and events, image overlays as filter graph pieces.

Pure: every function maps planned overlays to text. Colours, fonts and numbers reaching a style
line were checked by `document` (`#RRGGBB`, the font allow-list, ranges), and style names are built
from the overlay's plan position, never from a document id.
"""

from __future__ import annotations

import math
import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any

from ..captions.style import ass_color
from .timeline import Chunk, PlannedImageOverlay, PlannedTextOverlay, TimelinePlan

ACCENT_HEX = "#FF6B35"
OVERLAY_ENTER_MS = 400
OVERLAY_SLIDE_PCT = 8
OVERLAY_ZOOM_FROM_PCT = 60
OVERLAY_SHADOW_PCT = 4
OVERLAY_BOX_PAD = 6
OVERLAY_FADE_MS = 200
# libass 0.17.1 blurs with sigma = 0.8493 x \blur, so \blur = 1.1774 x sigma.
BLUR_SIGMA_TO_ASS = 1.1774
AN_ROW = {"top": 7, "middle": 4, "bottom": 1}
AN_COL = {"left": 0, "center": 1, "right": 2}

_ACCENT_RE = re.compile(r"\[\[(.*?)\]\]")
# MarginL, MarginR, MarginV, then BorderStyle: 1 outline, 3 box.
_MARGINS = ",0,0,0,"


def ass_escape(text: str) -> str:
    escaped = text.replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}")
    # libass ends a script line at a bare CR, so none may reach the file.
    return escaped.replace("\r\n", "\\N").replace("\r", "\\N").replace("\n", "\\N")


def inline_colour(hex_rgb: str) -> str:
    """`#RRGGBB` as the `&HBBGGRR&` of a `\\1c` tag (which carries no alpha)."""
    return f"&H{hex_rgb[5:7]}{hex_rgb[3:5]}{hex_rgb[1:3]}&".upper()


def _size(overlay: PlannedTextOverlay, height: int) -> int:
    return max(1, round(overlay.size_pct * height / 100))


def _bold(overlay: PlannedTextOverlay) -> int:
    return -1 if overlay.style.get("bold", True) else 0


def _box_colour(overlay: PlannedTextOverlay) -> str | None:
    if overlay.bg == "dark":
        return ass_color("#000000", 35)
    if overlay.bg == "accent":
        return ass_color(ACCENT_HEX, 0)
    if overlay.bg == "custom":
        return ass_color(overlay.style["bgColor"], 0)
    return None


def _shadow_extended(shadow: Mapping[str, Any] | None) -> bool:
    """Any of distance, angle, blur or opacity makes the shadow its own layer; a shadow with only
    a colour keeps the plain ASS shadow column."""
    return shadow is not None and bool({"distancePct", "angle", "blurPct", "opacity"} & set(shadow))


def _edges(overlay: PlannedTextOverlay, size: int) -> tuple[int, str, int, str]:
    """(outline px, outline colour, shadow px, shadow colour) of the text layer. An absent
    `outline` draws two black pixels without a backdrop and none with one; `null` draws none."""
    style = overlay.style
    if "outline" not in style:
        outline_px, outline_c = (2 if overlay.bg == "none" else 0), ass_color("#000000")
    elif style["outline"] is None:
        outline_px, outline_c = 0, ass_color("#000000")
    else:
        outline = style["outline"]
        outline_px = round(outline["widthPct"] * size / 100)
        outline_c = ass_color(outline["color"])
    shadow = style.get("shadow")
    if shadow is None or _shadow_extended(shadow):
        return outline_px, outline_c, 0, "&H00000000"
    shadow_px = max(1, round(OVERLAY_SHADOW_PCT * size / 100))
    return outline_px, outline_c, shadow_px, ass_color(shadow["color"])


def _layered(overlay: PlannedTextOverlay, size: int) -> bool:
    """One ASS style cannot draw a backdrop and an outline or shadow: the box goes underneath."""
    outline_px, _, shadow_px, _ = _edges(overlay, size)
    return _box_colour(overlay) is not None and (outline_px > 0 or shadow_px > 0)


def _shape_style(
    name: str, layer: str, overlay: PlannedTextOverlay, color: str, height: int, base_font: str
) -> str:
    """A shadow or glow layer: outline and shadow stay 0 here because the real values are inline
    tags (`\\blur` has no style column)."""
    font = overlay.style.get("font", base_font)
    colour = ass_color(color)
    return (
        f"Style: {name}_{layer},{font},{_size(overlay, height)},{colour},{colour},{colour},"
        f"&H00000000,{_bold(overlay)},0,0,5{_MARGINS}1"
    )


def sticker_style_lines(
    overlay: PlannedTextOverlay, height: int, base_font: str, *, name: str
) -> list[str]:
    """The `Style:` lines of one text overlay: the text style, plus a box style when a backdrop
    sits under an outline or shadow, plus a shadow and a glow style when those effects are on.
    The `,0,0,` after Alignment are MarginL and MarginR: the Format line is shared with captions."""
    size = _size(overlay, height)
    font = overlay.style.get("font", base_font)
    colour = ass_color(overlay.color)
    box = _box_colour(overlay)
    extra: list[str] = []
    shadow = overlay.style.get("shadow")
    if shadow is not None and _shadow_extended(shadow):
        extra.append(_shape_style(name, "shadow", overlay, shadow["color"], height, base_font))
    glow = overlay.style.get("glow")
    if glow:
        extra.append(_shape_style(name, "glow", overlay, glow["color"], height, base_font))
    bold = _bold(overlay)
    if box is not None and not _layered(overlay, size):
        return [
            *extra,
            f"Style: {name},{font},{size},{colour},{colour},{box},&H00000000,{bold},"
            f"{OVERLAY_BOX_PAD},0,5{_MARGINS}3",
        ]
    outline_px, outline_c, shadow_px, shadow_c = _edges(overlay, size)
    text = (
        f"Style: {name},{font},{size},{colour},{colour},{outline_c},{shadow_c},{bold},"
        f"{outline_px},{shadow_px},5{_MARGINS}1"
    )
    if box is None:
        return [*extra, text]
    hidden = ass_color(overlay.color, 100)
    return [
        *extra,
        f"Style: {name}_box,{font},{size},{hidden},{hidden},{box},&H00000000,{bold},"
        f"{OVERLAY_BOX_PAD},0,5{_MARGINS}3",
        text,
    ]


def _strip_accent_markers(text: str) -> str:
    return text.replace("[[", "").replace("]]", "")


def _alpha(opacity: float) -> str:
    """Opacity 0..1 as an `\\alpha` value (ASS is inverted: 00 solid, FF invisible)."""
    return f"&H{round((1 - opacity) * 255):02X}&"


def _blur(sigma_px: float) -> str:
    return f"\\blur{sigma_px * BLUR_SIGMA_TO_ASS:g}"


def _run_text(
    text: str, base_colour: str, accent: Mapping[str, Any] | None, base_outline: str
) -> str:
    """One line of text with its `[[ ]]` runs coloured, then colour and outline set back
    explicitly: `\\r` would also clear `\\frz`."""
    if accent is None:
        return ass_escape(_strip_accent_markers(text))
    base_c, base_o = inline_colour(base_colour), inline_colour(base_outline)
    accent_c = inline_colour(accent["color"])
    accent_o = inline_colour(accent.get("outlineColor", base_outline))
    out: list[str] = []
    pos = 0
    for match in _ACCENT_RE.finditer(text):
        out.append(ass_escape(text[pos : match.start()]))
        out.append(
            f"{{\\1c{accent_c}\\3c{accent_o}}}{ass_escape(match.group(1))}"
            f"{{\\1c{base_c}\\3c{base_o}}}"
        )
        pos = match.end()
    out.append(ass_escape(text[pos:]))
    return "".join(out)


def _line_font_size(
    overlay: PlannedTextOverlay, index: int, size: int, font: str
) -> tuple[str, int]:
    entries = overlay.style.get("lines")
    entry = entries[index] if entries and index < len(entries) else None
    if entry is None:
        return font, size
    return entry.get("font", font), max(1, round(size * entry["sizeScale"]))


def _shape_text(overlay: PlannedTextOverlay, size: int, font: str) -> str:
    """Text of a shadow, glow or box layer: same line breaks and per-line font and size as the
    text layer (so the halo sits under the letters), no colour of its own, no `[[ ]]`."""
    raw_lines = overlay.text.split("\n")
    if not overlay.style.get("lines"):
        return "\\N".join(ass_escape(_strip_accent_markers(line)) for line in raw_lines)
    parts: list[str] = []
    for i, line in enumerate(raw_lines):
        line_font, line_size = _line_font_size(overlay, i, size, font)
        parts.append(
            f"{{\\fn{line_font}\\fs{line_size}}}" + ass_escape(_strip_accent_markers(line))
        )
    return "\\N".join(parts)


def _display_text(overlay: PlannedTextOverlay, size: int, font: str, base_outline: str) -> str:
    accent = overlay.style.get("accent")
    lines_cfg = overlay.style.get("lines")
    if not lines_cfg and accent is None:
        return ass_escape(_strip_accent_markers(overlay.text))
    parts: list[str] = []
    for i, line in enumerate(overlay.text.split("\n")):
        colour = overlay.color
        tag = ""
        if lines_cfg:
            line_font, line_size = _line_font_size(overlay, i, size, font)
            entry = lines_cfg[i] if i < len(lines_cfg) else None
            if entry is not None:
                colour = entry.get("color", colour)
            tag = f"{{\\fn{line_font}\\fs{line_size}\\1c{inline_colour(colour)}}}"
        parts.append(tag + _run_text(line, colour, accent, base_outline))
    return "\\N".join(parts)


def _motion_tags(
    x: int, y: int, an: int, anim: str, starts_here: bool, ends_here: bool, height: int
) -> str:
    """Anchor, position or slide, zoom and fade tags at one anchor point. The text layer and the
    soft shadow layer share this, so the shadow moves and fades with the text."""
    if anim == "slide_up" and starts_here:
        lower = y + round(OVERLAY_SLIDE_PCT * height / 100)
        tags = f"\\an{an}\\move({x},{lower},{x},{y},0,{OVERLAY_ENTER_MS})"
    else:
        tags = f"\\an{an}\\pos({x},{y})"
    if anim == "zoom_in" and starts_here:
        z = OVERLAY_ZOOM_FROM_PCT
        tags += f"\\fscx{z}\\fscy{z}\\t(0,{OVERLAY_ENTER_MS},\\fscx100\\fscy100)"
    if anim in ("slide_up", "zoom_in"):
        tags += (
            f"\\fad({OVERLAY_ENTER_MS if starts_here else 0},{OVERLAY_FADE_MS if ends_here else 0})"
        )
    return tags


def sticker_events(
    overlay: PlannedTextOverlay,
    width: int,
    height: int,
    base_font: str,
    window: str,
    starts_here: bool,
    ends_here: bool,
    *,
    name: str,
) -> list[str]:
    """The `Dialogue:` lines of one text overlay for a chunk window (`window` is `start,end`).

    The entry motion runs only in the chunk where the overlay starts and the fade-out only where
    it ends. `\\fsp` adds spacing after every glyph including the last, so the anchor is moved
    back by half a spacing times the column (0 left, 1 centre, 2 right: measured on libass
    0.17.1). A soft shadow and a glow are separate layers under the text, because libass blurs
    only an outline, never the glyphs themselves."""
    style = overlay.style
    size = _size(overlay, height)
    align = style.get("align", "center")
    spacing_pct = style.get("letterSpacingPct")
    spacing_px = None if spacing_pct is None else spacing_pct * size / 100
    comp = 0 if spacing_px is None else round(spacing_px * AN_COL[align] / 2)
    x, y = round(overlay.x * width) + comp, round(overlay.y * height)
    an = AN_ROW[style.get("anchor", "middle")] + AN_COL[align]

    shape_suffix = ""
    if spacing_px is not None:
        shape_suffix += f"\\fsp{spacing_px:g}"
    if "rotate" in style:
        shape_suffix += f"\\frz{-style['rotate']:g}"
    if "italic" in style:
        shape_suffix += f"\\i{1 if style['italic'] else 0}"
    if "underline" in style:
        shape_suffix += f"\\u{1 if style['underline'] else 0}"

    tags = _motion_tags(x, y, an, overlay.anim, starts_here, ends_here, height) + shape_suffix
    opacity = style.get("opacity", 1)
    alpha = None if "opacity" not in style else _alpha(opacity)
    fade = f"{{\\fad({OVERLAY_FADE_MS},{OVERLAY_FADE_MS})}}" if overlay.anim == "fade" else ""

    font = style.get("font", base_font)
    outline = style.get("outline")
    base_outline = outline["color"] if outline else "#000000"
    display = _display_text(overlay, size, font, base_outline)
    shadow = style.get("shadow")
    glow = style.get("glow")
    needs_shape = _shadow_extended(shadow) or bool(glow)
    shape = _shape_text(overlay, size, font) if needs_shape else display

    events: list[str] = []
    if shadow is not None and _shadow_extended(shadow):
        distance_px = shadow.get("distancePct", 4) * size / 100
        angle = math.radians(shadow.get("angle", 45))
        dx, dy = round(distance_px * math.cos(angle)), round(distance_px * math.sin(angle))
        sigma_px = shadow.get("blurPct", 0) * size / 100
        blur = _blur(sigma_px) if sigma_px > 0 else ""
        shadow_opacity = opacity * shadow.get("opacity", 1)
        shadow_alpha = "" if shadow_opacity >= 1 else f"\\alpha{_alpha(shadow_opacity)}"
        shadow_tags = (
            _motion_tags(x + dx, y + dy, an, overlay.anim, starts_here, ends_here, height)
            + shape_suffix
            + f"\\bord0\\shad0{blur}{shadow_alpha}"
        )
        events.append(f"Dialogue: -2,{window},{name}_shadow,{{{shadow_tags}}}{fade}{shape}")
    if glow:
        spread_px = round(glow["spreadPct"] * size / 100)
        sigma_px = glow["blurPct"] * size / 100
        glow_c = inline_colour(glow["color"])
        glow_alpha = "" if opacity >= 1 else f"\\alpha{_alpha(opacity)}"
        # Each layer blurs 0.5 sigma wider than the one below, so `strength` is visible.
        for i in range(glow["strength"]):
            layer_blur = _blur(sigma_px * (1 + 0.5 * i))
            glow_tags = (
                f"{tags}\\bord{spread_px}\\1c{glow_c}\\3c{glow_c}{layer_blur}\\shad0{glow_alpha}"
            )
            events.append(f"Dialogue: -1,{window},{name}_glow,{{{glow_tags}}}{fade}{shape}")
    if not _layered(overlay, size):
        own = tags + (f"\\alpha{alpha}" if alpha else "")
        events.append(f"Dialogue: 1,{window},{name},{{{own}}}{fade}{display}")
        return events
    box_tags = tags + "\\1a&HFF&" + (f"\\3a{alpha}" if alpha else "")
    text_tags = tags + (f"\\alpha{alpha}" if alpha else "")
    events.append(f"Dialogue: 1,{window},{name}_box,{{{box_tags}}}{fade}{shape}")
    events.append(f"Dialogue: 2,{window},{name},{{{text_tags}}}{fade}{display}")
    return events


@dataclass(frozen=True)
class ImageWindow:
    """Output frames of a chunk an image overlay shows on: `[k0, k1)` from the chunk start, `j0`
    the overlay's own frame at `k0` (an overlay that began in an earlier chunk keeps its clock),
    `frames` its whole life."""

    k0: int
    k1: int
    j0: int
    frames: int


def image_window(overlay: PlannedImageOverlay, chunk: Chunk, fps: int) -> ImageWindow | None:
    frame = 1000.0 / fps
    first = round((overlay.start_ms - chunk.start_ms) / frame)
    end = round((overlay.end_ms - chunk.start_ms) / frame)
    k0, k1 = max(first, 0), min(end, round(chunk.dur_ms / frame))
    if k1 <= k0:
        return None
    return ImageWindow(k0=k0, k1=k1, j0=k0 - first, frames=end - first)


def image_windows(
    plan: TimelinePlan, chunk: Chunk
) -> list[tuple[PlannedImageOverlay, ImageWindow]]:
    """Image overlays showing in the chunk, lower rows first so a higher row draws on top; the
    sort is stable, so equal rows keep document order."""
    fps = plan.canvas.fps
    kept: list[tuple[PlannedImageOverlay, ImageWindow]] = []
    for overlay in plan.overlays:
        if isinstance(overlay, PlannedImageOverlay):
            window = image_window(overlay, chunk, fps)
            if window is not None:
                kept.append((overlay, window))
    return sorted(kept, key=lambda item: -(item[0].row_index or 0))


def overlay_width_px(overlay: PlannedImageOverlay, width: int) -> int:
    """Even width, at least 2, of an image overlay on an output `width` wide."""
    return max(2, 2 * round(overlay.width_pct * width / 200))


def thin_strip_warnings(plan: TimelinePlan, probes: Mapping[str, Mapping[str, Any]]) -> list[str]:
    """One warning per image overlay whose drawn height rounds below one pixel: the filter keeps
    it two pixels high, so the strip shows but is not the shape the asset has. A sheet is judged
    by its cell, a still by its measured size."""
    warnings: list[str] = []
    for overlay in plan.overlays:
        if not isinstance(overlay, PlannedImageOverlay):
            continue
        src_w: Any
        src_h: Any
        if overlay.animation:
            src_w, src_h = overlay.animation["frameW"], overlay.animation["frameH"]
        else:
            probe = probes.get(overlay.asset_id, {})
            src_w, src_h = probe.get("width"), probe.get("height")
        if not isinstance(src_w, int) or not isinstance(src_h, int) or src_w < 1:
            continue
        drawn_w = overlay_width_px(overlay, plan.out_width)
        if drawn_w * src_h / src_w < 1:
            warnings.append(
                f"image overlay {overlay.id}: the {src_w}x{src_h} image drawn {drawn_w} px wide "
                "is under 1 px high; it is drawn 2 px high"
            )
    return warnings


def sticker_frame(k: int, k0: int, fps: int, animation: Mapping[str, int]) -> int:
    """Sheet frame an animated overlay shows at output frame `k`, looping every `frameMs` from
    its start frame `k0`: the formula the member app previews with."""
    elapsed_ms = (k - k0) * 1000.0 / fps
    return math.floor(elapsed_ms / animation["frameMs"]) % animation["frameCount"]


def image_overlay_filters(
    overlay: PlannedImageOverlay,
    window: ImageWindow,
    src: int,
    base: str,
    index: int,
    width: int,
    fps: int,
) -> tuple[list[str], str]:
    """Filter graph pieces that put an image overlay (input `src`) on the chunk picture `base`;
    returns the pieces and the output label.

    A still is scaled once and looped inside the graph (an `-loop 1` input decodes it again every
    frame). An animated sheet loops whole and `crop`s one cell per output frame before scaling.
    The clock is set twice: to the overlay's own clock for `fade`, then to the chunk's. `enable`
    ends the window, or `overlay` would keep the last frame to the end of the chunk."""
    w = overlay_width_px(overlay, width)
    alpha = overlay.opacity
    fades: list[str] = []
    if overlay.anim == "fade":
        # Opacity is min((t - start) / 200 ms, (end - t) / 200 ms) clamped to 0..1; an overlay
        # under 400 ms never reaches 1, so the ramp shortens to half its life and the peak drops.
        life_ms = window.frames * 1000.0 / fps
        fade_ms = min(OVERLAY_FADE_MS, life_ms / 2)
        alpha *= fade_ms / OVERLAY_FADE_MS
        d, out_at = fade_ms / 1000, (life_ms - fade_ms) / 1000
        fades = [
            f"fade=t=in:st=0:d={d:.6f}:alpha=1",
            f"fade=t=out:st={out_at:.6f}:d={d:.6f}:alpha=1",
        ]
    sheet = overlay.animation
    if sheet:
        # `n + j0` is the frames elapsed since the overlay began, not since the chunk began.
        frame = f"mod(floor(({window.j0}+n)*1000/({fps}*{sheet['frameMs']})),{sheet['frameCount']})"
        x_expr = f"mod({frame},{sheet['cols']})*{sheet['frameW']}"
        y_expr = f"floor({frame}/{sheet['cols']})*{sheet['frameH']}"
        pre = [
            "loop=loop=-1:size=1",
            f"crop={sheet['frameW']}:{sheet['frameH']}:x='{x_expr}':y='{y_expr}'",
            f"scale={w}:'max(2,2*round({w}*{sheet['frameH']}/{sheet['frameW']}/2))'",
            "format=rgba",
            f"colorchannelmixer=aa={alpha:.4f}",
        ]
    else:
        # The height comes from the decoded frame (`ih/iw`) and never falls below 2: a 2000x10
        # strip at 8 px wide is 0.04 px high, which `scale=w:-2` rounds to 0 and ffmpeg refuses.
        pre = [
            f"scale={w}:'max(2,2*round({w}*ih/iw/2))'",
            "format=rgba",
            f"colorchannelmixer=aa={alpha:.4f}",
            "loop=loop=-1:size=1",
        ]
    layer = [
        *pre,
        f"trim=end_frame={window.k1 - window.k0}",
        # 1/1000 frame units keep fade ramps whole (frame units would round 150 ms to 5 frames).
        f"settb=1/{fps * 1000}",
        f"setpts=(N+{window.j0})*1000",
        *fades,
        f"settb=1/{fps}",
        f"setpts=N+{window.k0}",
    ]
    lo, hi = (window.k0 - 0.5) / fps, (window.k1 - 0.5) / fps
    out = f"[vim{index}]"
    return [
        f"[{src}:v]{','.join(layer)}[im{index}]",
        f"{base}[im{index}]overlay=x={overlay.x:.6f}*W-w/2:y={overlay.y:.6f}*H-h/2:"
        f"enable='between(t,{lo:.4f},{hi:.4f})'{out}",
    ], out


def text_overlays_in_draw_order(
    overlays: Sequence[PlannedTextOverlay | PlannedImageOverlay],
) -> list[tuple[int, PlannedTextOverlay]]:
    """(plan position, overlay) of every text overlay, lower rows first so row 0 draws on top."""
    items = [(i, o) for i, o in enumerate(overlays) if isinstance(o, PlannedTextOverlay)]
    return sorted(items, key=lambda item: -(item[1].row_index or 0))
