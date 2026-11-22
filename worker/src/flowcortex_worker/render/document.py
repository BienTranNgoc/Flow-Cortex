"""The project document and the job around it, checked leaf by leaf before anything is encoded.

Every value is untrusted: it reaches an ffmpeg filter graph, a work-file name or an ASS file.
A bad value raises `ValueError("<path>: <reason>")` naming the document path it came from.
"""

from __future__ import annotations

import math
import re
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass, field
from typing import Any, Literal

from ..captions.fonts import ALLOWED_FONTS

PROJECT_PROFILES: tuple[str, ...] = ("draft", "final")
JOB_MODES: tuple[str, ...] = ("plan", "render")
PROJECT_INPUT = "project"
ASSET_PREFIX = "asset:"

# xfade names the editor offers (the non-null kinds of its transition library). The kind is
# pasted into the filter graph, so the list is closed.
TRANSITION_KINDS: tuple[str, ...] = (
    "fade",
    "dissolve",
    "slideleft",
    "slideright",
    "slideup",
    "slidedown",
    "fadewhite",
    "fadeblack",
    "zoomin",
    "circleopen",
    "wipeleft",
    "wiperight",
    "wipeup",
    "wipedown",
    "wipetl",
    "wipetr",
    "wipebl",
    "wipebr",
    "smoothleft",
    "smoothright",
    "smoothup",
    "smoothdown",
    "circleclose",
    "radial",
    "vertopen",
    "vertclose",
    "horzopen",
    "horzclose",
    "diagtl",
    "diagtr",
    "diagbl",
    "diagbr",
    "rectcrop",
    "coverleft",
    "coverright",
    "coverup",
    "coverdown",
    "revealleft",
    "revealright",
    "revealup",
    "revealdown",
    "squeezeh",
    "squeezev",
    "pixelize",
    "hblur",
    "fadegrays",
    "hlslice",
    "hrslice",
    "vuslice",
    "vdslice",
    "hlwind",
    "hrwind",
    "vuwind",
    "vdwind",
)

FITS = ("cover", "contain", "contain_blur")
ASSET_KINDS = ("video", "image", "audio", "clay", "render")
PICTURE_KINDS = ("video", "image", "clay", "render")
FPS_VALUES = (24, 25, 30)
SAMPLE_RATE = 48000
TRANSITION_MODES = ("extend", "overlap")
MOTION_KINDS = ("zoom_in", "zoom_out")

_HEX_RE = re.compile(r"^#[0-9A-Fa-f]{6}$")
_LANG_RE = re.compile(r"^[a-z]{2,3}(-[A-Za-z]{4})?(-[A-Z]{2})?$")
_FONT_SIZES = ("small", "medium", "large", "xlarge")
_ANIMATIONS = ("none", "fade", "pop")
_HIGHLIGHT_MODES = ("off", "sweep", "jump")
_POSITIONS = ("bottom", "middle", "top")
_OUTLINE_WIDTHS = ("none", "thin", "medium", "thick")
_BACKDROPS = ("none", "dark_soft", "dark_solid", "light_solid", "custom")
_LANES = ("captions", "voice", "music", "overlays")
_CAPTION_PRESETS = (
    "movie_sub",
    "tiktok_bold",
    "sales_punch",
    "dark_box",
    "lecture",
    "gold_karaoke",
)
_ASSET_ROLES = (
    "upload",
    "run",
    "take",
    "dub",
    "music",
    "stem",
    "character_ref",
    "clay",
    "render",
    "voice_sample",
)
_OVERLAY_BACKGROUNDS = ("none", "dark", "accent", "custom")
_TEXT_ANIMATIONS = ("none", "fade", "slide_up", "zoom_in")
_IMAGE_ANIMATIONS = ("none", "fade")
_ALIGNS = ("left", "center", "right")
_ANCHORS = ("top", "middle", "bottom")
_FIT_POLICIES = ("ripple", "trim_tail", "rewrite")
_MAX_LINES = 4
_MAX_SAFE_INT = 2**53 - 1


@dataclass(frozen=True)
class Canvas:
    width: int
    height: int
    fps: int
    sample_rate: int


@dataclass(frozen=True)
class Motion:
    kind: str
    from_scale: float
    to_scale: float


@dataclass(frozen=True)
class Transition:
    kind: str
    dur_ms: int
    mode: str


@dataclass(frozen=True)
class Scene:
    id: str
    clip_id: str | None
    asset_kind: str | None
    src_in_ms: int
    src_out_ms: int
    at_ms: int
    dur_ms: int
    fit: str
    fill: str
    keep_audio: bool
    gain_db: float
    motion: Motion | None
    transition_out: Transition | None
    offset_x: float | None
    offset_y: float | None
    zoom: float | None


@dataclass(frozen=True)
class Row:
    id: str
    hidden: bool
    muted: bool


@dataclass(frozen=True)
class CaptionWord:
    t: str
    start_ms: int
    end_ms: int


@dataclass(frozen=True)
class CaptionCue:
    id: str
    start_ms: int
    end_ms: int
    text: str
    style_id: str | None
    row: str | None
    words: tuple[CaptionWord, ...]


@dataclass(frozen=True)
class CaptionTrack:
    id: str
    lang: str | None
    style: Mapping[str, Any] | None
    rows: tuple[Row, ...]
    cues: tuple[CaptionCue, ...]


@dataclass(frozen=True)
class AssetInfo:
    """What the renderer reads of an asset: its kind, role, sheet layout and declared probe."""

    kind: str
    role: str | None = None
    animation: Mapping[str, int] | None = None
    probe: Mapping[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class TextOverlay:
    """A text overlay kept on an active lane. `style` holds exactly the optional style leaves the
    document carries, so an absent leaf and an explicit null stay different."""

    id: str
    index: int
    row_index: int | None
    text: Mapping[str, str]
    x: float
    y: float
    size_pct: float
    color: str
    bg: str
    anim: str
    start_ms: int
    end_ms: int
    style: Mapping[str, Any]


@dataclass(frozen=True)
class ImageOverlay:
    id: str
    index: int
    row_index: int | None
    asset_id: str
    x: float
    y: float
    width_pct: float
    opacity: float
    anim: str
    start_ms: int
    end_ms: int


@dataclass(frozen=True)
class VoiceTrack:
    id: str
    caption_track_id: str | None
    rows: tuple[Row, ...]


@dataclass(frozen=True)
class VoiceClip:
    id: str
    index: int
    asset_id: str
    cue_id: str | None
    src_in_ms: int
    src_out_ms: int
    at_ms: int
    dur_ms: int
    gain_db: float
    fade_in_ms: int
    fade_out_ms: int
    fit_policy: str
    rate: float


@dataclass(frozen=True)
class MusicClip:
    id: str
    index: int
    asset_id: str
    src_in_ms: int
    src_out_ms: int
    at_ms: int
    dur_ms: int
    gain_db: float
    fade_in_ms: int
    fade_out_ms: int
    loop: bool


@dataclass(frozen=True)
class Document:
    """The checked project. `overlays`, `voice_clips` and `music_clips` hold only what the lane
    switches and the hidden / muted rows leave in the render, in document order."""

    canvas: Canvas
    scenes: tuple[Scene, ...]
    caption_lane: bool
    caption_track_id: str | None
    caption_style: Mapping[str, Any]
    caption_styles: Mapping[str, Mapping[str, Any]]
    caption_tracks: Mapping[str, CaptionTrack] = field(default_factory=dict)
    lanes: Mapping[str, bool] = field(default_factory=dict)
    voice_track_id: str | None = None
    overlay_lang: str | None = None
    overlays: tuple[TextOverlay | ImageOverlay, ...] = ()
    overlay_rows_text: tuple[Row, ...] = ()
    overlay_rows_image: tuple[Row, ...] = ()
    voice_tracks: Mapping[str, VoiceTrack] = field(default_factory=dict)
    voice_clips: tuple[VoiceClip, ...] = ()
    music_clips: tuple[MusicClip, ...] = ()
    music_rows: tuple[Row, ...] = ()
    duck_enabled: bool = False
    assets: Mapping[str, AssetInfo] = field(default_factory=dict)


@dataclass(frozen=True)
class JobSpec:
    profile: str
    mode: str
    asset_ids: tuple[str, ...]


def _is_number(value: object) -> bool:
    return isinstance(value, int | float) and not isinstance(value, bool) and math.isfinite(value)


def _mapping(value: Any, path: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise ValueError(f"{path}: must be an object, got {type(value).__name__}")
    return value


def _list(value: Any, path: str) -> list[Any]:
    if not isinstance(value, list):
        raise ValueError(f"{path}: must be an array, got {type(value).__name__}")
    return value


def _string(value: Any, path: str, *, minimum: int = 0) -> str:
    if not isinstance(value, str) or len(value) < minimum:
        raise ValueError(f"{path}: must be a string, got {value!r}")
    return value


def _optional_string(value: Any, path: str) -> str | None:
    return None if value is None else _string(value, path, minimum=1)


def _bool(value: Any, path: str) -> bool:
    if not isinstance(value, bool):
        raise ValueError(f"{path}: must be true or false, got {value!r}")
    return value


def _int(value: Any, path: str, *, minimum: int = 0, maximum: int = _MAX_SAFE_INT) -> int:
    if (
        not isinstance(value, int)
        or isinstance(value, bool)
        or not minimum <= value <= maximum
    ):
        bound = f">= {minimum}" if maximum == _MAX_SAFE_INT else f"within {minimum} to {maximum}"
        raise ValueError(f"{path}: must be an integer {bound}, got {value!r}")
    return value


def _number(value: Any, path: str, low: float, high: float) -> float:
    if not _is_number(value) or not low <= value <= high:
        raise ValueError(f"{path}: must be a number within {low:g} to {high:g}, got {value!r}")
    return float(value)


def _number_above(value: Any, path: str, low: float, high: float = math.inf) -> float:
    """A number strictly above `low` and at most `high`."""
    if not _is_number(value) or not low < value <= high:
        bound = f"above {low:g}" if high == math.inf else f"above {low:g} up to {high:g}"
        raise ValueError(f"{path}: must be a number {bound}, got {value!r}")
    return float(value)


def _choice(value: Any, path: str, allowed: Sequence[str]) -> str:
    if not isinstance(value, str) or value not in allowed:
        raise ValueError(f"{path}: must be one of {', '.join(allowed)}, got {value!r}")
    return value


def _hex(value: Any, path: str) -> str:
    if not isinstance(value, str) or _HEX_RE.match(value) is None:
        raise ValueError(f"{path}: must be #RRGGBB, got {value!r}")
    return value


def _rows(value: Any, path: str) -> tuple[Row, ...]:
    if value is None:
        return ()
    rows = []
    for i, raw in enumerate(_list(value, path)):
        here = f"{path}[{i}]"
        row = _mapping(raw, here)
        for flag in ("hidden", "muted"):
            if flag in row and row[flag] is not True:
                raise ValueError(f"{here}.{flag}: must be true when present, got {row[flag]!r}")
        rows.append(
            Row(
                _string(row.get("id"), f"{here}.id", minimum=1),
                hidden="hidden" in row,
                muted="muted" in row,
            )
        )
    return tuple(rows)


def row_index(rows: Sequence[Row], row: object) -> int:
    """Position of the named row, 0 (the top row) when the name matches none."""
    for i, candidate in enumerate(rows):
        if candidate.id == row:
            return i
    return 0


def kept_by_row(
    item_rows: Sequence[object], rows: Sequence[Row], flag: Literal["hidden", "muted"]
) -> list[tuple[int, int | None]]:
    """`(item index, row index)` of every item not sitting on a row carrying `flag`.

    The row index is None when the box declares no rows: such a box has one visible row.
    """
    kept: list[tuple[int, int | None]] = []
    for i, row in enumerate(item_rows):
        if not rows:
            kept.append((i, None))
            continue
        idx = row_index(rows, row)
        if not getattr(rows[idx], flag):
            kept.append((i, idx))
    return kept


def _check_style(raw: Any, path: str) -> Mapping[str, Any]:
    style = _mapping(raw, path)
    enums = {
        "fontSize": _FONT_SIZES,
        "animation": _ANIMATIONS,
        "highlightMode": _HIGHLIGHT_MODES,
        "position": _POSITIONS,
        "outlineWidth": _OUTLINE_WIDTHS,
        "backdrop": _BACKDROPS,
        "fontFamily": ALLOWED_FONTS,
        "preset": _CAPTION_PRESETS,
    }
    for key, allowed in enums.items():
        if key in style:
            _choice(style[key], f"{path}.{key}", allowed)
    if style.get("shadow") is not None:
        shadow = _mapping(style["shadow"], f"{path}.shadow")
        _hex(shadow.get("color"), f"{path}.shadow.color")
    for key in ("x", "y"):
        if key in style:
            _number(style[key], f"{path}.{key}", 0, 1)
    if "scale" in style:
        _number(style["scale"], f"{path}.scale", 0.3, 3)
    if "boxWidth" in style:
        _number(style["boxWidth"], f"{path}.boxWidth", 0.2, 1)
    if "marginPct" in style:
        _number(style["marginPct"], f"{path}.marginPct", 0, 45)
    for key in ("italic", "uppercase", "bold"):
        if key in style:
            _bool(style[key], f"{path}.{key}")
    for key in ("textColor", "highlightColor", "outlineColor", "backdropColor"):
        if key in style:
            _hex(style[key], f"{path}.{key}")
    if "maxSyllablesPerLine" in style:
        syllables = style["maxSyllablesPerLine"]
        if not isinstance(syllables, int) or isinstance(syllables, bool) or not 2 <= syllables <= 8:
            raise ValueError(
                f"{path}.maxSyllablesPerLine: must be an integer within 2 to 8, got {syllables!r}"
            )
    return style


def _line_endings(text: str) -> str:
    return text.replace("\r\n", "\n").replace("\r", "\n")


def _check_cue(raw: Any, path: str) -> CaptionCue:
    cue = _mapping(raw, path)
    start = _int(cue.get("startMs"), f"{path}.startMs")
    end = _int(cue.get("endMs"), f"{path}.endMs")
    if end <= start:
        raise ValueError(f"{path}.endMs: must be greater than startMs ({start}), got {end}")
    words = []
    for j, raw_word in enumerate(_list(cue.get("words", []), f"{path}.words")):
        here = f"{path}.words[{j}]"
        word = _mapping(raw_word, here)
        words.append(
            CaptionWord(
                _line_endings(_string(word.get("t"), f"{here}.t")),
                _int(word.get("startMs"), f"{here}.startMs"),
                _int(word.get("endMs"), f"{here}.endMs"),
            )
        )
    row = cue.get("row")
    return CaptionCue(
        id=_string(cue.get("id", ""), f"{path}.id"),
        start_ms=start,
        end_ms=end,
        text=_line_endings(_string(cue.get("text"), f"{path}.text")),
        style_id=_optional_string(cue.get("styleId"), f"{path}.styleId"),
        row=None if row is None else _string(row, f"{path}.row", minimum=1),
        words=tuple(words),
    )


def _check_track(track_id: str, raw: Any) -> CaptionTrack:
    path = f"timeline.captions.tracks.{track_id}"
    track = _mapping(raw, path)
    lang = track.get("lang")
    if lang is not None and (not isinstance(lang, str) or _LANG_RE.match(lang) is None):
        raise ValueError(
            f"{path}.lang: must be a BCP-47 language tag such as vi or en-US, got {lang!r}"
        )
    style = track.get("style")
    cues = _list(track.get("cues", []), f"{path}.cues")
    return CaptionTrack(
        id=track_id,
        lang=lang,
        style=None if style is None else _check_style(style, f"{path}.style"),
        rows=_rows(track.get("rows"), f"{path}.rows"),
        cues=tuple(_check_cue(cue, f"{path}.cues[{i}]") for i, cue in enumerate(cues)),
    )


def _check_canvas(raw: Any) -> Canvas:
    canvas = _mapping(raw, "canvas")
    size: dict[str, int] = {}
    for key in ("width", "height"):
        value = canvas.get(key)
        if (
            not isinstance(value, int)
            or isinstance(value, bool)
            or not 16 <= value <= 1920
            or value % 2
        ):
            raise ValueError(
                f"canvas.{key}: must be an even integer within 16 to 1920, got {value!r}"
            )
        size[key] = value
    fps = canvas.get("fps")
    if not _is_number(fps) or fps not in FPS_VALUES:
        raise ValueError(f"canvas.fps: must be one of 24, 25, 30, got {fps!r}")
    rate = canvas.get("sampleRate")
    if not _is_number(rate) or rate != SAMPLE_RATE:
        raise ValueError(f"canvas.sampleRate: must be {SAMPLE_RATE}, got {rate!r}")
    return Canvas(size["width"], size["height"], int(fps), SAMPLE_RATE)


def _check_scene(raw: Any, index: int, assets: Mapping[str, str]) -> Scene:
    path = f"timeline.scenes[{index}]"
    scene = _mapping(raw, path)
    src_in = _int(scene.get("srcInMs"), f"{path}.srcInMs")
    src_out = _int(scene.get("srcOutMs"), f"{path}.srcOutMs")
    at = _int(scene.get("atMs"), f"{path}.atMs")
    dur = _int(scene.get("durMs"), f"{path}.durMs", minimum=1)
    if src_out - src_in != dur:
        raise ValueError(
            f"{path}.durMs: srcOutMs - srcInMs ({src_out - src_in}) must equal durMs ({dur}); "
            "speed changes are not supported"
        )
    clip_id = scene.get("clipId")
    asset_kind = None
    if clip_id is not None:
        clip_id = _string(clip_id, f"{path}.clipId", minimum=1)
        if clip_id not in assets:
            raise ValueError(f"{path}.clipId: asset {clip_id!r} is not in assets")
        asset_kind = assets[clip_id]
        if asset_kind not in PICTURE_KINDS:
            raise ValueError(
                f"{path}.clipId: asset {clip_id!r} is of kind {asset_kind}; "
                f"a scene needs one of {', '.join(PICTURE_KINDS)}"
            )
    audio = _mapping(scene.get("audio"), f"{path}.audio")
    motion = None
    raw_motion = scene.get("motion")
    if raw_motion is not None:
        here = f"{path}.motion"
        spec = _mapping(raw_motion, here)
        motion = Motion(
            _choice(spec.get("kind"), f"{here}.kind", MOTION_KINDS),
            _number(spec.get("from"), f"{here}.from", 1, 3),
            _number(spec.get("to"), f"{here}.to", 1, 3),
        )
        if asset_kind != "image":
            raise ValueError(f"{here}: motion applies to image scenes only")
    transition = None
    raw_transition = scene.get("transitionOut")
    if raw_transition is not None:
        here = f"{path}.transitionOut"
        spec = _mapping(raw_transition, here)
        transition = Transition(
            _choice(spec.get("kind"), f"{here}.kind", TRANSITION_KINDS),
            _int(spec.get("durMs"), f"{here}.durMs", minimum=1),
            _choice(spec.get("mode"), f"{here}.mode", TRANSITION_MODES),
        )
    frame_leaves: dict[str, float | None] = {}
    for key, low, high in (("offsetX", -1, 1), ("offsetY", -1, 1), ("zoom", 0.3, 4)):
        frame_leaves[key] = (
            _number(scene[key], f"{path}.{key}", low, high) if key in scene else None
        )
    return Scene(
        id=_string(scene.get("id"), f"{path}.id", minimum=1),
        clip_id=clip_id,
        asset_kind=asset_kind,
        src_in_ms=src_in,
        src_out_ms=src_out,
        at_ms=at,
        dur_ms=dur,
        fit=_choice(scene.get("fit"), f"{path}.fit", FITS),
        fill=_hex(scene.get("fill"), f"{path}.fill"),
        keep_audio=_bool(audio.get("keep"), f"{path}.audio.keep"),
        gain_db=_number(audio.get("gainDb"), f"{path}.audio.gainDb", -60, 12),
        motion=motion,
        transition_out=transition,
        offset_x=frame_leaves["offsetX"],
        offset_y=frame_leaves["offsetY"],
        zoom=frame_leaves["zoom"],
    )


Check = Callable[[Any, str], Any]


def _present(box: Mapping[str, Any], path: str, spec: Mapping[str, Check]) -> dict[str, Any]:
    """Check every leaf of `spec` that `box` carries; an absent leaf is not an error here."""
    return {key: check(box[key], f"{path}.{key}") for key, check in spec.items() if key in box}


def _need(found: Mapping[str, Any], path: str, keys: Sequence[str]) -> None:
    for key in keys:
        if key not in found:
            raise ValueError(f"{path}.{key}: required")


def _nullable(check: Check) -> Check:
    return lambda value, path: None if value is None else check(value, path)


def _ms(value: Any, path: str) -> int:
    return _int(value, path)


def _unit(value: Any, path: str) -> float:
    return _number(value, path, 0, 1)


def _gain(value: Any, path: str) -> float:
    return _number(value, path, -60, 12)


def _name(value: Any, path: str) -> str:
    return _string(value, path, minimum=1)


def _font(value: Any, path: str) -> str:
    return _choice(value, path, ALLOWED_FONTS)


def _object(spec: Mapping[str, Check], required: Sequence[str]) -> Check:
    def check(value: Any, path: str) -> dict[str, Any]:
        found = _present(_mapping(value, path), path, spec)
        _need(found, path, required)
        return found

    return check


_OUTLINE = _object(
    {"color": _hex, "widthPct": lambda v, p: _number(v, p, 0, 25)}, ("color", "widthPct")
)
_SHADOW = _object(
    {
        "color": _hex,
        "distancePct": lambda v, p: _number(v, p, 0, 30),
        "angle": lambda v, p: _number(v, p, -180, 180),
        "blurPct": lambda v, p: _number(v, p, 0, 30),
        "opacity": _unit,
    },
    ("color",),
)
_GLOW = _object(
    {
        "color": _hex,
        "blurPct": lambda v, p: _number(v, p, 1, 40),
        "spreadPct": lambda v, p: _number(v, p, 0, 15),
        "strength": lambda v, p: _int(v, p, minimum=1, maximum=3),
    },
    ("color", "blurPct", "spreadPct", "strength"),
)
_ACCENT = _object({"color": _hex, "outlineColor": _hex}, ("color",))
_LINE = _object(
    {"font": _font, "sizeScale": lambda v, p: _number(v, p, 0.4, 2.5), "color": _hex},
    ("sizeScale",),
)


def _lines(value: Any, path: str) -> list[dict[str, Any]]:
    items = _list(value, path)
    if len(items) > _MAX_LINES:
        raise ValueError(f"{path}: at most {_MAX_LINES} lines, got {len(items)}")
    return [_LINE(item, f"{path}[{i}]") for i, item in enumerate(items)]


def _text_map(value: Any, path: str) -> dict[str, str]:
    texts = _mapping(value, path)
    for lang, text in texts.items():
        if not isinstance(lang, str) or _LANG_RE.match(lang) is None:
            raise ValueError(f"{path}.{lang}: must be a BCP-47 language tag such as vi or en-US")
        _string(text, f"{path}.{lang}")
    return dict(texts)


_OVERLAY_COMMON: dict[str, Check] = {
    "id": _name,
    "x": _unit,
    "y": _unit,
    "startMs": _ms,
    "endMs": _ms,
    "row": _name,
    "opacity": _unit,
}
_TEXT_STYLE_LEAVES: dict[str, Check] = {
    "font": _font,
    "bold": _bool,
    "italic": _bool,
    "underline": _bool,
    "align": lambda v, p: _choice(v, p, _ALIGNS),
    "anchor": lambda v, p: _choice(v, p, _ANCHORS),
    "outline": _nullable(_OUTLINE),
    "bgColor": _hex,
    "shadow": _nullable(_SHADOW),
    "glow": _nullable(_GLOW),
    "accent": _nullable(_ACCENT),
    "lines": _lines,
    "opacity": _unit,
    "rotate": lambda v, p: _number(v, p, -180, 180),
    "letterSpacingPct": lambda v, p: _number(v, p, -20, 100),
}
_TEXT_OVERLAY: dict[str, Check] = {
    **_OVERLAY_COMMON,
    **_TEXT_STYLE_LEAVES,
    "text": _text_map,
    "sizePct": lambda v, p: _number_above(v, p, 0, 50),
    "color": _hex,
    "bg": lambda v, p: _choice(v, p, _OVERLAY_BACKGROUNDS),
    "anim": lambda v, p: _choice(v, p, _TEXT_ANIMATIONS),
}
_TEXT_REQUIRED = ("id", "text", "x", "y", "sizePct", "color", "bg", "anim", "startMs", "endMs")
_IMAGE_OVERLAY: dict[str, Check] = {
    **_OVERLAY_COMMON,
    "assetId": _name,
    "widthPct": lambda v, p: _number(v, p, 2, 100),
    "anim": lambda v, p: _choice(v, p, _IMAGE_ANIMATIONS),
}
_IMAGE_REQUIRED = (
    "id",
    "assetId",
    "x",
    "y",
    "widthPct",
    "opacity",
    "anim",
    "startMs",
    "endMs",
)
_VOICE_CLIP: dict[str, Check] = {
    "id": _name,
    "cueId": _nullable(lambda v, p: _string(v, p)),
    "assetId": _name,
    "srcInMs": _ms,
    "srcOutMs": _ms,
    "atMs": _ms,
    "durMs": lambda v, p: _int(v, p, minimum=1),
    "gainDb": _gain,
    "fadeInMs": _ms,
    "fadeOutMs": _ms,
    "fitPolicy": lambda v, p: _choice(v, p, _FIT_POLICIES),
    "row": _name,
    "rate": lambda v, p: _number(v, p, 0.5, 2),
}
_VOICE_REQUIRED = (
    "id",
    "assetId",
    "srcInMs",
    "srcOutMs",
    "atMs",
    "durMs",
    "gainDb",
    "fadeInMs",
    "fadeOutMs",
    "fitPolicy",
    "rate",
)
_MUSIC_CLIP: dict[str, Check] = {
    "id": _name,
    "assetId": _name,
    "srcInMs": _ms,
    "srcOutMs": _ms,
    "atMs": _ms,
    "durMs": lambda v, p: _int(v, p, minimum=1),
    "gainDb": _gain,
    "fadeInMs": _ms,
    "fadeOutMs": _ms,
    "loop": _bool,
    "row": _name,
}
_MUSIC_REQUIRED = (
    "id",
    "assetId",
    "srcInMs",
    "srcOutMs",
    "atMs",
    "durMs",
    "gainDb",
    "fadeInMs",
    "fadeOutMs",
    "loop",
)
_PROBE: dict[str, Check] = {
    "durationMs": _ms,
    "width": lambda v, p: _int(v, p, minimum=1),
    "height": lambda v, p: _int(v, p, minimum=1),
    "fps": lambda v, p: _number_above(v, p, 0),
    "hasAudio": _bool,
    "rotation": lambda v, p: _int(v, p, minimum=-_MAX_SAFE_INT),
    "pixFmt": lambda v, p: _string(v, p),
    "colorTransfer": lambda v, p: _string(v, p),
    "sha256": lambda v, p: _sha256(v, p),
    "bytes": _ms,
}
_ANIMATION: dict[str, Check] = {
    "frameCount": lambda v, p: _int(v, p, minimum=1, maximum=600),
    "cols": lambda v, p: _int(v, p, minimum=1, maximum=32),
    "frameW": lambda v, p: _int(v, p, minimum=1, maximum=2048),
    "frameH": lambda v, p: _int(v, p, minimum=1, maximum=2048),
    "frameMs": lambda v, p: _int(v, p, minimum=1, maximum=10000),
}
_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


def _sha256(value: Any, path: str) -> str:
    if not isinstance(value, str) or _SHA256_RE.match(value) is None:
        raise ValueError(f"{path}: must be 64 lowercase hex characters, got {value!r}")
    return value


def _check_asset(asset_id: str, raw: Any) -> AssetInfo:
    path = f"assets.{asset_id}"
    asset = _mapping(raw, path)
    kind = _choice(asset.get("kind"), f"{path}.kind", ASSET_KINDS)
    role = asset.get("role")
    if "role" in asset:
        role = _choice(role, f"{path}.role", _ASSET_ROLES)
    animation = None
    if "animation" in asset:
        animation = _object(_ANIMATION, tuple(_ANIMATION))(asset["animation"], f"{path}.animation")
    probe: dict[str, Any] = {}
    if "probe" in asset:
        probe = _present(_mapping(asset["probe"], f"{path}.probe"), f"{path}.probe", _PROBE)
    return AssetInfo(kind=kind, role=role, animation=animation, probe=probe)


def _referenced_asset(assets: Mapping[str, AssetInfo], asset_id: str, path: str) -> None:
    if asset_id not in assets:
        raise ValueError(f"{path}.assetId: asset {asset_id!r} is not in assets")


def _check_overlays(
    timeline: Mapping[str, Any], lane_on: bool, assets: Mapping[str, AssetInfo]
) -> tuple[tuple[TextOverlay | ImageOverlay, ...], tuple[Row, ...], tuple[Row, ...]]:
    rows = _mapping(timeline.get("overlayRows", {}), "timeline.overlayRows")
    text_rows = _rows(rows.get("text"), "timeline.overlayRows.text")
    image_rows = _rows(rows.get("image"), "timeline.overlayRows.image")
    raw = _list(timeline.get("overlays", []), "timeline.overlays")

    found: list[tuple[bool, dict[str, Any]]] = []
    for i, item in enumerate(raw):
        path = f"timeline.overlays[{i}]"
        box = _mapping(item, path)
        if "kind" in box and box["kind"] != "image":
            raise ValueError(f"{path}.kind: must be image when present, got {box['kind']!r}")
        is_image = "kind" in box
        found.append((is_image, _present(box, path, _IMAGE_OVERLAY if is_image else _TEXT_OVERLAY)))

    kept: list[TextOverlay | ImageOverlay] = []
    if not lane_on:
        return (), text_rows, image_rows
    for i, (is_image, leaves) in enumerate(found):
        path = f"timeline.overlays[{i}]"
        visible = kept_by_row([leaves.get("row")], image_rows if is_image else text_rows, "hidden")
        if not visible:
            continue
        row_idx = visible[0][1]
        if is_image:
            _need(leaves, path, _IMAGE_REQUIRED)
            _referenced_asset(assets, leaves["assetId"], path)
            kept.append(
                ImageOverlay(
                    id=leaves["id"],
                    index=i,
                    row_index=row_idx,
                    asset_id=leaves["assetId"],
                    x=leaves["x"],
                    y=leaves["y"],
                    width_pct=leaves["widthPct"],
                    opacity=leaves["opacity"],
                    anim=leaves["anim"],
                    start_ms=leaves["startMs"],
                    end_ms=leaves["endMs"],
                )
            )
            continue
        _need(leaves, path, _TEXT_REQUIRED)
        kept.append(
            TextOverlay(
                id=leaves["id"],
                index=i,
                row_index=row_idx,
                text=leaves["text"],
                x=leaves["x"],
                y=leaves["y"],
                size_pct=leaves["sizePct"],
                color=leaves["color"],
                bg=leaves["bg"],
                anim=leaves["anim"],
                start_ms=leaves["startMs"],
                end_ms=leaves["endMs"],
                style={k: leaves[k] for k in _TEXT_STYLE_LEAVES if k in leaves},
            )
        )
    return tuple(kept), text_rows, image_rows


def _check_voice(
    timeline: Mapping[str, Any],
    lane_on: bool,
    chosen: str | None,
    assets: Mapping[str, AssetInfo],
) -> tuple[dict[str, VoiceTrack], tuple[VoiceClip, ...]]:
    voice = _mapping(timeline.get("voice", {}), "timeline.voice")
    raw_tracks = _mapping(voice.get("tracks", {}), "timeline.voice.tracks")
    if chosen is not None and chosen not in raw_tracks:
        raise ValueError(f"output.voiceTrackId: {chosen!r} is not in timeline.voice.tracks")

    tracks: dict[str, VoiceTrack] = {}
    clips: tuple[VoiceClip, ...] = ()
    for track_id, raw in raw_tracks.items():
        path = f"timeline.voice.tracks.{track_id}"
        box = _mapping(raw, path)
        link = _present(box, path, {"captionTrackId": _nullable(_name)})
        rows = _rows(box.get("rows"), f"{path}.rows")
        tracks[track_id] = VoiceTrack(track_id, link.get("captionTrackId"), rows)
        found = [
            _present(_mapping(clip, f"{path}.clips[{i}]"), f"{path}.clips[{i}]", _VOICE_CLIP)
            for i, clip in enumerate(_list(box.get("clips", []), f"{path}.clips"))
        ]
        if not lane_on or track_id != chosen:
            continue
        built: list[VoiceClip] = []
        for index, _ in kept_by_row([leaves.get("row") for leaves in found], rows, "muted"):
            leaves = found[index]
            here = f"{path}.clips[{index}]"
            _need(leaves, here, _VOICE_REQUIRED)
            _referenced_asset(assets, leaves["assetId"], here)
            built.append(
                VoiceClip(
                    id=leaves["id"],
                    index=index,
                    asset_id=leaves["assetId"],
                    cue_id=leaves.get("cueId"),
                    src_in_ms=leaves["srcInMs"],
                    src_out_ms=leaves["srcOutMs"],
                    at_ms=leaves["atMs"],
                    dur_ms=leaves["durMs"],
                    gain_db=leaves["gainDb"],
                    fade_in_ms=leaves["fadeInMs"],
                    fade_out_ms=leaves["fadeOutMs"],
                    fit_policy=leaves["fitPolicy"],
                    rate=leaves["rate"],
                )
            )
        clips = tuple(built)
    return tracks, clips


def _check_music(
    timeline: Mapping[str, Any], lane_on: bool, assets: Mapping[str, AssetInfo]
) -> tuple[tuple[MusicClip, ...], tuple[Row, ...], bool]:
    music = _mapping(timeline.get("music", {}), "timeline.music")
    rows = _rows(music.get("rows"), "timeline.music.rows")
    duck = _present(
        _mapping(music.get("duck", {}), "timeline.music.duck"),
        "timeline.music.duck",
        {"enabled": _bool},
    )
    found = [
        _present(
            _mapping(clip, f"timeline.music.clips[{i}]"), f"timeline.music.clips[{i}]", _MUSIC_CLIP
        )
        for i, clip in enumerate(_list(music.get("clips", []), "timeline.music.clips"))
    ]
    built: list[MusicClip] = []
    if lane_on:
        for index, _ in kept_by_row([leaves.get("row") for leaves in found], rows, "muted"):
            leaves = found[index]
            here = f"timeline.music.clips[{index}]"
            _need(leaves, here, _MUSIC_REQUIRED)
            _referenced_asset(assets, leaves["assetId"], here)
            built.append(
                MusicClip(
                    id=leaves["id"],
                    index=index,
                    asset_id=leaves["assetId"],
                    src_in_ms=leaves["srcInMs"],
                    src_out_ms=leaves["srcOutMs"],
                    at_ms=leaves["atMs"],
                    dur_ms=leaves["durMs"],
                    gain_db=leaves["gainDb"],
                    fade_in_ms=leaves["fadeInMs"],
                    fade_out_ms=leaves["fadeOutMs"],
                    loop=leaves["loop"],
                )
            )
    return tuple(built), rows, bool(duck.get("enabled", False))


def parse_document(doc: Any) -> Document:
    """Check every leaf the renderer reads and return the typed document."""
    root = _mapping(doc, "project")
    canvas = _check_canvas(root.get("canvas"))

    raw_assets = _mapping(root.get("assets", {}), "assets")
    assets = {asset_id: _check_asset(asset_id, asset) for asset_id, asset in raw_assets.items()}
    kinds = {asset_id: info.kind for asset_id, info in assets.items()}

    timeline = _mapping(root.get("timeline"), "timeline")
    raw_scenes = _list(timeline.get("scenes"), "timeline.scenes")
    if not raw_scenes:
        raise ValueError("timeline.scenes: at least one scene is required")
    scenes = tuple(_check_scene(raw, i, kinds) for i, raw in enumerate(raw_scenes))

    output = _mapping(root.get("output"), "output")
    raw_lanes = _mapping(output.get("lanes"), "output.lanes")
    lanes = {lane: _bool(raw_lanes.get(lane), f"output.lanes.{lane}") for lane in _LANES}
    caption_track_id = _optional_string(output.get("captionTrackId"), "output.captionTrackId")
    voice_track_id = _optional_string(output.get("voiceTrackId"), "output.voiceTrackId")
    overlay_lang = output.get("overlayLang")
    if "overlayLang" in output and (
        not isinstance(overlay_lang, str) or _LANG_RE.match(overlay_lang) is None
    ):
        raise ValueError(
            f"output.overlayLang: must be a BCP-47 language tag such as vi or en-US, "
            f"got {overlay_lang!r}"
        )

    captions = _mapping(timeline.get("captions", {}), "timeline.captions")
    style = captions.get("style")
    styles = _mapping(captions.get("styles", {}), "timeline.captions.styles")
    tracks = _mapping(captions.get("tracks", {}), "timeline.captions.tracks")

    caption_style = {} if style is None else _check_style(style, "timeline.captions.style")
    caption_styles = {
        style_id: _check_style(spec, f"timeline.captions.styles.{style_id}")
        for style_id, spec in styles.items()
    }
    caption_tracks = {track_id: _check_track(track_id, spec) for track_id, spec in tracks.items()}

    overlays, rows_text, rows_image = _check_overlays(timeline, lanes["overlays"], assets)
    voice_tracks, voice_clips = _check_voice(timeline, lanes["voice"], voice_track_id, assets)
    music_clips, music_rows, duck_enabled = _check_music(timeline, lanes["music"], assets)

    return Document(
        canvas=canvas,
        scenes=scenes,
        caption_lane=lanes["captions"],
        caption_track_id=caption_track_id,
        caption_style=caption_style,
        caption_styles=caption_styles,
        caption_tracks=caption_tracks,
        lanes=lanes,
        voice_track_id=voice_track_id,
        overlay_lang=overlay_lang if isinstance(overlay_lang, str) else None,
        overlays=overlays,
        overlay_rows_text=rows_text,
        overlay_rows_image=rows_image,
        voice_tracks=voice_tracks,
        voice_clips=voice_clips,
        music_clips=music_clips,
        music_rows=music_rows,
        duck_enabled=duck_enabled,
        assets=assets,
    )


def parse_job(params: Mapping[str, Any], input_keys: Sequence[str], document: Document) -> JobSpec:
    """Check the job's params and input set against the document; return the asset ids to read.

    `input_keys` is the job's declared key list, so a duplicate stays visible.
    """
    extra = sorted(set(params) - {"profile", "mode"})
    if extra:
        raise ValueError(
            f"params.{extra[0]}: not accepted; a project job takes profile and mode only"
        )
    for key in ("profile", "mode"):
        if key not in params:
            raise ValueError(f"params.{key}: required")
    profile = _choice(params["profile"], "params.profile", PROJECT_PROFILES)
    mode = _choice(params["mode"], "params.mode", JOB_MODES)

    seen: set[str] = set()
    for key in input_keys:
        if key in seen:
            raise ValueError(f"inputs: duplicate input {key!r}")
        seen.add(key)

    if mode == "plan":
        asset_ids: tuple[str, ...] = ()
    else:
        asset_ids = render_asset_ids(document)
    expected = (PROJECT_INPUT, *(f"{ASSET_PREFIX}{asset_id}" for asset_id in asset_ids))
    for key in expected:
        if key not in seen:
            raise ValueError(f"inputs: missing input {key!r}")
    for key in input_keys:
        if key not in expected:
            reason = (
                "a plan job reads only the project"
                if mode == "plan"
                else "it is not read by any scene, overlay, voice or music clip of this render"
            )
            raise ValueError(f"inputs: extra input {key!r}: {reason}")
    return JobSpec(profile=profile, mode=mode, asset_ids=asset_ids)


def render_asset_ids(document: Document) -> tuple[str, ...]:
    """Assets a render reads, deduplicated: scenes, image overlays, voice clips, music clips.

    A clip or overlay the plan later drops (trim, shorter than a frame) still counts: only the
    lane switch and the row filter decide, so the set needs no plan.
    """
    ids = [scene.clip_id for scene in document.scenes if scene.clip_id is not None]
    ids += [o.asset_id for o in document.overlays if isinstance(o, ImageOverlay)]
    ids += [c.asset_id for c in document.voice_clips]
    ids += [c.asset_id for c in document.music_clips]
    return tuple(dict.fromkeys(ids))
