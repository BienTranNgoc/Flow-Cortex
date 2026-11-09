"""Caption printing: styles, line breaking, karaoke text, stacking and the ASS document.

Pure: every function maps a plan to text. Sizes and positions follow the canvas (PlayRes equals
the canvas), so a draft is the exact scaled copy of the final render.
"""

from __future__ import annotations

import math
import re
import unicodedata
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from typing import Any

from ..captions.line_breaks import (
    FontMetrics,
    GlyphTable,
    break_lines,
    font_metrics,
    resolve_font_key,
)
from ..captions.style import CaptionStyle, resolve_style
from .timeline import KaraokeSpan, PlannedCue, TimelinePlan

# Must equal the tiers the member app previews with, or the burnt text differs from what was seen.
FONT_SIZE_PCT = {"small": 3.2, "medium": 4.375, "large": 5.4, "xlarge": 6.5}
CAPTION_ENTER_MS = 150
CAPTION_POP_FROM_PCT = 70
# The ASS Style line carries no line-spacing column, so a block's height is lines x size x this.
CAPTION_LINE_SPACING = 1.2
# CJK and Thai have no spaces for a width-based break to use, so `boxWidth` is ignored there.
_IGNORE_BOXWIDTH_LANGS = frozenset({"zh", "ja", "ko", "th"})

MetricsLoader = Callable[[], GlyphTable]


@dataclass(frozen=True)
class RenderStyle:
    """An ASS style plus what each cue of it prints with."""

    name: str
    caption: CaptionStyle
    text_hex: str
    italic: bool
    uppercase: bool
    animation: str
    margin_l: int
    margin_r: int
    size: int
    outline: int
    shadow: int
    pos_x: int | None
    pos_y: int | None
    metrics: FontMetrics | None
    max_width_px: int | None


@dataclass(frozen=True)
class CaptionStyles:
    base: RenderStyle
    by_id: Mapping[str, RenderStyle]

    def of(self, style_id: str | None) -> RenderStyle:
        return self.base if style_id is None else self.by_id[style_id]

    def all(self) -> list[RenderStyle]:
        return [self.base, *self.by_id.values()]


def _js_round(value: float) -> int:
    """Half-up, as the member app's `Math.round` (Python's `round` goes to even)."""
    return math.floor(value + 0.5)


def _primary_lang(lang: str | None) -> str:
    return (lang or "").split("-")[0].lower()


def _render_style(
    name: str,
    spec: Mapping[str, Any],
    width: int,
    height: int,
    lang: str | None,
    metrics_loader: MetricsLoader,
    table_cache: list[GlyphTable],
) -> RenderStyle:
    resolved_spec = dict(spec)
    font_size = resolved_spec.pop("fontSize", None)
    if font_size is not None:
        resolved_spec["fontSizePct"] = FONT_SIZE_PCT[font_size]
    resolved_spec.pop("preset", None)
    style = resolve_style(resolved_spec, height)
    size, outline = style.size, style.outline
    # A box backdrop is drawn from OutlineColour and an ASS shadow would sit under it as a copy.
    shadow = 0 if style.border_style == 3 else style.shadow

    scale = spec.get("scale")
    if scale is not None:
        size = max(1, _js_round(size * scale))
        outline = _js_round(outline * scale)
        shadow = _js_round(shadow * scale)

    pos_x = pos_y = None
    if "x" in spec and "y" in spec:
        pos_x, pos_y = _js_round(spec["x"] * width), _js_round(spec["y"] * height)

    margin = 0
    metrics: FontMetrics | None = None
    max_width_px: int | None = None
    box_width = spec.get("boxWidth")
    if box_width is not None and _primary_lang(lang) not in _IGNORE_BOXWIDTH_LANGS:
        margin = max(0, _js_round((width - box_width * width) / 2))
        if not table_cache:
            table_cache.append(metrics_loader())
        table = table_cache[0]
        key = resolve_font_key(table, style.font, bool(spec.get("bold", True)))
        metrics = font_metrics(table, key)
        max_width_px = max(0, width - 2 * margin)

    return RenderStyle(
        name=name,
        caption=style,
        text_hex=spec.get("textColor", "#FFFFFF"),
        italic=bool(spec.get("italic", False)),
        uppercase=bool(spec.get("uppercase", False)),
        animation=spec.get("animation", "none"),
        margin_l=margin,
        margin_r=margin,
        size=size,
        outline=outline,
        shadow=shadow,
        pos_x=pos_x,
        pos_y=pos_y,
        metrics=metrics,
        max_width_px=max_width_px,
    )


def caption_styles(
    plan: TimelinePlan, width: int, height: int, metrics_loader: MetricsLoader
) -> CaptionStyles:
    """Resolve the track style and every project style against the canvas.

    The glyph table is read through `metrics_loader` at most once, and only when a style asks for
    `boxWidth`. Raises `ValueError` for an invalid style (for example a font outside the library).
    """
    table_cache: list[GlyphTable] = []
    lang = plan.caption_lang
    base = _render_style(
        "Cap", plan.caption_style, width, height, lang, metrics_loader, table_cache
    )
    by_id = {
        style_id: _render_style(
            f"Cap{index}", spec, width, height, lang, metrics_loader, table_cache
        )
        for index, (style_id, spec) in enumerate(plan.caption_styles.items(), start=1)
    }
    return CaptionStyles(base, by_id)


def timecode(ms: float) -> str:
    """`h:mm:ss.cc` from whole centiseconds, so it never reads `60.00`."""
    cs = round(max(0.0, ms) / 10)
    hours, rest = divmod(cs, 360000)
    minutes, rest = divmod(rest, 6000)
    seconds, centis = divmod(rest, 100)
    return f"{hours}:{minutes:02d}:{seconds:02d}.{centis:02d}"


def ass_escape(text: str) -> str:
    return text.replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}").replace("\n", "\\N")


def _is_syllable(token: str) -> bool:
    return any(unicodedata.category(ch)[0] in "LN" for ch in token)


def line_breaks_by_syllables(text: str, max_syllables: int) -> list[tuple[int, int]]:
    """Whitespace spans of `text` to turn into line breaks: each paragraph (typed newlines are
    kept) of n syllables gets ceil(n / max) lines, balanced, the lower lines taking the extra
    syllable. Punctuation standing alone stays with the line above."""
    breaks: list[tuple[int, int]] = []
    for paragraph in re.finditer(r"[^\n]+", text):
        heads = [
            paragraph.start() + m.start()
            for m in re.finditer(r"\S+", paragraph.group())
            if _is_syllable(m.group())
        ]
        lines = -(-len(heads) // max_syllables)
        if lines < 2:
            continue
        short, extra = divmod(len(heads), lines)
        sizes = [short] * (lines - extra) + [short + 1] * extra
        position = 0
        for size in sizes[:-1]:
            position += size
            start = end = heads[position]
            while start > paragraph.start() and text[start - 1].isspace():
                start -= 1
            breaks.append((start, end))
    return breaks


def with_breaks(
    text: str, breaks: Sequence[tuple[int, int]], low: int = 0, high: int | None = None
) -> str:
    """`text[low:high]` with the whitespace spans of `breaks` (computed over the whole text)
    replaced by newlines; spans outside the window are skipped."""
    high = len(text) if high is None else high
    out: list[str] = []
    position = low
    for start, end in breaks:
        if start >= low and end <= high:
            out += [text[position:start], "\n"]
            position = end
    out.append(text[position:high])
    return "".join(out)


def _cased(text: str, style: RenderStyle) -> str:
    return text.upper() if style.uppercase else text


def _measured_breaks(cased: str, style: RenderStyle) -> list[tuple[int, int]]:
    assert style.metrics is not None and style.max_width_px is not None
    return break_lines(cased, style.metrics, style.size, style.max_width_px)


def caption_text(text: str, style: RenderStyle) -> str:
    """A cue's text broken into lines (by measured width under `boxWidth`, else by syllables),
    upper-cased before escaping when the style asks."""
    if style.max_width_px is not None:
        cased = _cased(unicodedata.normalize("NFC", text), style)
        return ass_escape(with_breaks(cased, _measured_breaks(cased, style)))
    breaks = line_breaks_by_syllables(text, style.caption.max_syllables)
    return ass_escape(_cased(with_breaks(text, breaks), style))


def _line_count(text: str, style: RenderStyle) -> int:
    if style.max_width_px is not None:
        cased = _cased(unicodedata.normalize("NFC", text), style)
        return len(_measured_breaks(cased, style)) + 1
    return len(line_breaks_by_syllables(text, style.caption.max_syllables)) + 1


def karaoke_text(
    spans: Sequence[KaraokeSpan], cue_start_ms: float, style: RenderStyle, window_start_ms: float
) -> str:
    """`\\kf` (sweep) or `\\k` (jump) tags counted from where this event starts; a silence is a
    `\\k` wait. A cue crossing into the window starts at the window start, so words already spoken
    get length 0."""
    tag = "kf" if style.caption.mode == "sweep" else "k"
    base = max(cue_start_ms, window_start_ms)
    if style.max_width_px is not None:
        # Measure the upper-cased text, each word NFC alone so span lengths stay those of `full`.
        words = [_cased(unicodedata.normalize("NFC", s.text), style) for s in spans]
        full = "".join(words)
        breaks = _measured_breaks(full, style)
        lengths = [len(w) for w in words]
    else:
        full = "".join(s.text for s in spans)
        breaks = line_breaks_by_syllables(full, style.caption.max_syllables)
        lengths = [len(s.text) for s in spans]
    out = ""
    cursor = 0
    position = 0
    for i, span in enumerate(spans):
        piece = with_breaks(full, breaks, position, position + lengths[i])
        position += lengths[i]
        start_cs = round((span.start_ms - base) / 10)
        end_cs = round((span.end_ms - base) / 10)
        if start_cs > cursor:
            out += f"{{\\k{start_cs - cursor}}}"
            cursor = start_cs
        cs = max(1, end_cs - cursor) if end_cs > 0 else 0
        cursor += cs
        out += f"{{\\{tag}{cs}}}{ass_escape(_cased(piece, style))}"
    return out


def _clusters(cues: Sequence[PlannedCue]) -> list[list[PlannedCue]]:
    """Cues overlapping in time, directly or through a third; touching edges do not overlap."""
    ordered = sorted(cues, key=lambda c: (c.start_ms, c.id))
    clusters: list[list[PlannedCue]] = []
    current: list[PlannedCue] = []
    current_end = 0.0
    for cue in ordered:
        if current and cue.start_ms < current_end:
            current.append(cue)
            current_end = max(current_end, cue.end_ms)
        else:
            if current:
                clusters.append(current)
            current, current_end = [cue], cue.end_ms
    if current:
        clusters.append(current)
    return clusters


def stack_positions(
    cues: Sequence[PlannedCue], styles: CaptionStyles, width: int, height: int
) -> dict[str, tuple[int, int, int]]:
    """`(x, y, \\an)` per cue id for every overlapping cluster that spans two or more rows.

    Each row gets one slot for the whole cluster, as tall as that row's tallest block, so a
    cue does not drift while another row is silent. Slots stack from the style's anchor: bottom
    keeps the lowest slot at the margin, top keeps the highest, middle centres the pile, and a
    free position (`x`/`y`) centres each block in its slot. A single-row cluster is left to libass.
    """
    out: dict[str, tuple[int, int, int]] = {}
    for cluster in _clusters(cues):
        rows = sorted({c.row_index or 0 for c in cluster})
        if len(rows) < 2:
            continue
        slot = dict.fromkeys(rows, 0.0)
        info: list[tuple[PlannedCue, int, str, float, float]] = []
        for cue in cluster:
            style = styles.of(cue.style_id)
            row = cue.row_index or 0
            if cue.text:
                slot[row] = max(
                    slot[row], _line_count(cue.text, style) * style.size * CAPTION_LINE_SPACING
                )
            if style.pos_x is not None and style.pos_y is not None:
                kind, cx, cy = "positioned", float(style.pos_x), float(style.pos_y)
            elif style.caption.align == 8:
                kind, cx, cy = "top", width / 2, float(style.caption.margin_v)
            elif style.caption.align == 5:
                kind, cx, cy = "middle", width / 2, height / 2
            else:
                kind, cx, cy = "bottom", width / 2, float(height - style.caption.margin_v)
            info.append((cue, row, kind, cx, cy))
        above = {r: sum(slot[q] for q in rows if q < r) for r in rows}
        below = {r: sum(slot[q] for q in rows if q > r) for r in rows}
        total, last = sum(slot.values()), slot[rows[-1]]
        for cue, row, kind, cx, cy in info:
            if kind == "top":
                y, an = cy + above[row], 8
            elif kind == "middle":
                y, an = cy - total / 2 + above[row] + slot[row] / 2, 5
            elif kind == "positioned":
                y, an = cy - below[row] + (last - slot[row]) / 2, 5
            else:
                y, an = cy - below[row], 2
            out[cue.id] = (_js_round(cx), _js_round(y), an)
    return out


def caption_lead(
    style: RenderStyle, starts_here: bool, stack: tuple[int, int, int] | None = None
) -> str:
    """Override block that opens a cue: position (stacked, else free `x`/`y`), `\\q2` when the
    cue was broken by width (libass must not wrap it again; set per cue so other styles still
    wrap), italics, and the entrance animation only in the window where the cue starts."""
    tags = ""
    if stack is not None:
        cx, cy, an = stack
        tags = f"\\an{an}\\pos({cx},{cy})"
    elif style.pos_x is not None:
        tags = f"\\an5\\pos({style.pos_x},{style.pos_y})"
    if style.max_width_px is not None:
        tags += "\\q2"
    if style.italic:
        tags += "\\i1"
    if starts_here and style.animation == "fade":
        tags += f"\\fad({CAPTION_ENTER_MS},0)"
    if starts_here and style.animation == "pop":
        z = CAPTION_POP_FROM_PCT
        tags += f"\\fscx{z}\\fscy{z}\\t(0,{CAPTION_ENTER_MS},\\fscx100\\fscy100)"
    return f"{{{tags}}}" if tags else ""


def _inline_colour(hex_rgb: str) -> str:
    """`#RRGGBB` as the `&HBBGGRR&` of a `\\1c` tag (which carries no alpha)."""
    return f"&H{hex_rgb[5:7]}{hex_rgb[3:5]}{hex_rgb[1:3]}&".upper()


def _style_line(style: RenderStyle) -> str:
    c = style.caption
    return (
        f"Style: {style.name},{c.font},{style.size},{c.primary},{c.secondary},"
        f"{c.outline_colour},{c.back_colour},{c.bold},{style.outline},{style.shadow},{c.align},"
        f"{style.margin_l},{style.margin_r},{c.margin_v},{c.border_style}"
    )


def ass_document(plan: TimelinePlan, styles: CaptionStyles, start_ms: float, dur_ms: float) -> str:
    """ASS for the window `[start, start + dur)`: times shifted to the window start, cues outside
    it dropped. PlayRes is the canvas whatever the output size."""
    width, height = plan.canvas.width, plan.canvas.height
    end_ms = start_ms + dur_ms
    lines = [
        "[Script Info]",
        "ScriptType: v4.00+",
        f"PlayResX: {width}",
        f"PlayResY: {height}",
        "WrapStyle: 0",
        "ScaledBorderAndShadow: yes",
        "",
        "[V4+ Styles]",
        "Format: Name,Fontname,Fontsize,PrimaryColour,SecondaryColour,OutlineColour,BackColour,"
        "Bold,Outline,Shadow,Alignment,MarginL,MarginR,MarginV,BorderStyle",
        *(_style_line(s) for s in styles.all()),
        "",
        "[Events]",
        "Format: Layer,Start,End,Style,Text",
    ]
    stacked = stack_positions(plan.cues, styles, width, height)
    for cue in plan.cues:
        low, high = max(cue.start_ms, start_ms), min(cue.end_ms, end_ms)
        if high <= low:
            continue
        style = styles.of(cue.style_id)
        if style.caption.mode == "off":
            text = caption_text(cue.text, style)
        elif cue.karaoke:
            text = karaoke_text(cue.karaoke, cue.start_ms, style, start_ms)
        else:
            # Without word times there is no \k tag, and libass would fill the whole cue with
            # PrimaryColour (the highlight), so the text colour is set back explicitly.
            text = f"{{\\1c{_inline_colour(style.text_hex)}}}{caption_text(cue.text, style)}"
        lead = caption_lead(style, cue.start_ms >= start_ms, stacked.get(cue.id))
        lines.append(
            f"Dialogue: 0,{timecode(low - start_ms)},{timecode(high - start_ms)},"
            f"{style.name},{lead}{text}"
        )
    return "\n".join(lines) + "\n"
