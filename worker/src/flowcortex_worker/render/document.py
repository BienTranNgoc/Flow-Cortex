"""The project document and the job around it, checked leaf by leaf before anything is encoded.

Every value is untrusted: it reaches an ffmpeg filter graph, a work-file name or an ASS file.
A bad value raises `ValueError("<path>: <reason>")` naming the document path it came from.
"""

from __future__ import annotations

import math
import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from typing import Any, Literal

PROJECT_PROFILES: tuple[str, ...] = ("draft", "final")
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
class Document:
    canvas: Canvas
    scenes: tuple[Scene, ...]
    caption_lane: bool
    caption_track_id: str | None
    caption_style: Mapping[str, Any]
    caption_styles: Mapping[str, Mapping[str, Any]]
    caption_tracks: Mapping[str, CaptionTrack] = field(default_factory=dict)


@dataclass(frozen=True)
class JobSpec:
    profile: str
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


def _int(value: Any, path: str, *, minimum: int = 0) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value < minimum:
        raise ValueError(f"{path}: must be an integer >= {minimum}, got {value!r}")
    return value


def _number(value: Any, path: str, low: float, high: float) -> float:
    if not _is_number(value) or not low <= value <= high:
        raise ValueError(f"{path}: must be a number within {low:g} to {high:g}, got {value!r}")
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
    }
    for key, allowed in enums.items():
        if key in style:
            _choice(style[key], f"{path}.{key}", allowed)
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
                _string(word.get("t"), f"{here}.t"),
                _int(word.get("startMs"), f"{here}.startMs"),
                _int(word.get("endMs"), f"{here}.endMs"),
            )
        )
    row = cue.get("row")
    return CaptionCue(
        id=_string(cue.get("id", ""), f"{path}.id"),
        start_ms=start,
        end_ms=end,
        text=_string(cue.get("text"), f"{path}.text"),
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


def _item_rows(items: list[Any], path: str) -> list[object]:
    return [_mapping(item, f"{path}[{i}]").get("row") for i, item in enumerate(items)]


def _reject_unsupported_lanes(
    timeline: Mapping[str, Any], lanes: Mapping[str, bool], voice_track_id: str | None
) -> None:
    """Refuse a lane that is on and still has content once muted and hidden rows are dropped."""
    content: dict[str, bool] = {}

    voice_clips: list[Any] = []
    voice_rows: tuple[Row, ...] = ()
    if voice_track_id is not None:
        voice = _mapping(timeline.get("voice", {}), "timeline.voice")
        tracks = _mapping(voice.get("tracks", {}), "timeline.voice.tracks")
        if voice_track_id not in tracks:
            raise ValueError(
                f"output.voiceTrackId: {voice_track_id!r} is not in timeline.voice.tracks"
            )
        path = f"timeline.voice.tracks.{voice_track_id}"
        track = _mapping(tracks[voice_track_id], path)
        voice_clips = _list(track.get("clips", []), f"{path}.clips")
        voice_rows = _rows(track.get("rows"), f"{path}.rows")
    content["voice"] = bool(
        kept_by_row(_item_rows(voice_clips, "timeline.voice.clips"), voice_rows, "muted")
    )

    music = _mapping(timeline.get("music", {}), "timeline.music")
    music_clips = _list(music.get("clips", []), "timeline.music.clips")
    content["music"] = bool(
        kept_by_row(
            _item_rows(music_clips, "timeline.music.clips"),
            _rows(music.get("rows"), "timeline.music.rows"),
            "muted",
        )
    )

    overlays = _list(timeline.get("overlays", []), "timeline.overlays")
    overlay_rows = _mapping(timeline.get("overlayRows", {}), "timeline.overlayRows")
    text_rows = _rows(overlay_rows.get("text"), "timeline.overlayRows.text")
    image_rows = _rows(overlay_rows.get("image"), "timeline.overlayRows.image")
    row_names = _item_rows(overlays, "timeline.overlays")
    content["overlays"] = any(
        kept_by_row(
            [row_names[i]],
            image_rows if overlay.get("kind") == "image" else text_rows,
            "hidden",
        )
        for i, overlay in enumerate(overlays)
    )

    for lane, has_content in content.items():
        if lanes[lane] and has_content:
            raise ValueError(
                f"output.lanes.{lane}: the {lane} lane is on and has content, "
                "which this renderer does not support; switch the lane off or empty it"
            )


def parse_document(doc: Any) -> Document:
    """Check every leaf the renderer reads and return the typed document."""
    root = _mapping(doc, "project")
    canvas = _check_canvas(root.get("canvas"))

    raw_assets = _mapping(root.get("assets", {}), "assets")
    assets = {
        asset_id: _choice(
            _mapping(asset, f"assets.{asset_id}").get("kind"),
            f"assets.{asset_id}.kind",
            ASSET_KINDS,
        )
        for asset_id, asset in raw_assets.items()
    }

    timeline = _mapping(root.get("timeline"), "timeline")
    raw_scenes = _list(timeline.get("scenes"), "timeline.scenes")
    if not raw_scenes:
        raise ValueError("timeline.scenes: at least one scene is required")
    scenes = tuple(_check_scene(raw, i, assets) for i, raw in enumerate(raw_scenes))

    output = _mapping(root.get("output"), "output")
    raw_lanes = _mapping(output.get("lanes"), "output.lanes")
    lanes = {lane: _bool(raw_lanes.get(lane), f"output.lanes.{lane}") for lane in _LANES}
    caption_track_id = _optional_string(output.get("captionTrackId"), "output.captionTrackId")
    voice_track_id = _optional_string(output.get("voiceTrackId"), "output.voiceTrackId")

    captions = _mapping(timeline.get("captions", {}), "timeline.captions")
    style = captions.get("style")
    styles = _mapping(captions.get("styles", {}), "timeline.captions.styles")
    tracks = _mapping(captions.get("tracks", {}), "timeline.captions.tracks")

    parsed = Document(
        canvas=canvas,
        scenes=scenes,
        caption_lane=lanes["captions"],
        caption_track_id=caption_track_id,
        caption_style={} if style is None else _check_style(style, "timeline.captions.style"),
        caption_styles={
            style_id: _check_style(spec, f"timeline.captions.styles.{style_id}")
            for style_id, spec in styles.items()
        },
        caption_tracks={
            track_id: _check_track(track_id, spec) for track_id, spec in tracks.items()
        },
    )
    _reject_unsupported_lanes(timeline, lanes, voice_track_id)
    return parsed


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
    if params["mode"] == "plan":
        raise ValueError("params.mode: plan mode is not supported by this renderer")
    _choice(params["mode"], "params.mode", ("render",))

    seen: set[str] = set()
    for key in input_keys:
        if key in seen:
            raise ValueError(f"inputs: duplicate input {key!r}")
        seen.add(key)

    asset_ids = tuple(
        dict.fromkeys(scene.clip_id for scene in document.scenes if scene.clip_id is not None)
    )
    expected = (PROJECT_INPUT, *(f"{ASSET_PREFIX}{asset_id}" for asset_id in asset_ids))
    for key in expected:
        if key not in seen:
            raise ValueError(f"inputs: missing input {key!r}")
    for key in input_keys:
        if key not in expected:
            raise ValueError(f"inputs: extra input {key!r} is not read by any rendered scene")
    return JobSpec(profile=profile, asset_ids=asset_ids)
