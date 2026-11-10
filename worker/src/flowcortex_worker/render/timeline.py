"""Plans a project render from the checked document and the measured assets.

Pure: no process runs here. Scene marks are snapped to the frame grid, transitions get their
handles, scenes are cut into encode chunks, and the caption cues, overlays, voice clips and
music clips the lanes leave in are picked. Anything the plan cannot honour raises
`ValueError("<path>: <reason>")`.
"""

from __future__ import annotations

import math
import unicodedata
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any

from .document import (
    Canvas,
    CaptionWord,
    Document,
    ImageOverlay,
    Motion,
    Scene,
    TextOverlay,
    VoiceClip,
    kept_by_row,
)

MAX_CHAIN_SCENES = 12
# Peak RSS of one ffmpeg grows about 340 MiB per chained scene (3 scenes 1178 MiB, 6 scenes
# 2212 MiB); a 60 s 1080x1920 render with 4-scene chunks peaked at 1761 MiB.
MAX_CHUNK_SCENES = 4
CAPTION_GRID_MS = 10
VOICE_TRIM_FADE_MS = 60
DURATION_TOLERANCE_MS = 50
MIX_DEFAULTS: Mapping[str, Any] = {
    "targetLufs": -14.0,
    "truePeakDb": -1.0,
    "lra": 11.0,
    "duck": {"threshold": 0.02, "ratio": 6, "attack": 20, "release": 400},
}
PROBE_KEYS = (
    "durationMs",
    "width",
    "height",
    "fps",
    "hasAudio",
    "rotation",
    "pixFmt",
    "colorTransfer",
)
_FPS_TOLERANCE = 0.6
_HDR_TRANSFERS = ("smpte2084", "arib-std-b67")


@dataclass(frozen=True)
class Profile:
    preset: str
    crf: int
    max_short_side: int | None


PROFILES: dict[str, Profile] = {
    "draft": Profile("ultrafast", 28, 480),
    "final": Profile("medium", 20, None),
}


@dataclass(frozen=True)
class PlannedTransition:
    kind: str
    dur_ms: float
    mode: str


@dataclass(frozen=True)
class SourceClamp:
    """A clip that is up to one frame shorter than its scene asks: what to encode instead."""

    src_out_ms: float
    dur_ms: float


@dataclass(frozen=True)
class PlannedScene:
    id: str
    clip_id: str | None
    kind: str | None
    src_in_ms: float
    src_out_ms: float
    at_ms: float
    dur_ms: float
    fit: str
    fill: str
    keep_audio: bool
    gain_db: float
    motion: Motion | None
    transition_out: PlannedTransition | None
    offset_x: float | None
    offset_y: float | None
    zoom: float | None
    probe: Mapping[str, Any]
    clamp: SourceClamp | None


@dataclass(frozen=True)
class Chunk:
    """`lead_scene` is a scene of the chunk before, whose tail opens this chunk (its transition
    into the first scene is encoded here); `cut_after` means the last scene's transition into
    the next chunk is left to that chunk, so this one stops where the cross-fade starts."""

    scene_indexes: tuple[int, ...]
    start_ms: float
    dur_ms: float
    lead_scene: int | None = None
    cut_after: bool = False


@dataclass(frozen=True)
class KaraokeSpan:
    text: str
    start_ms: float
    end_ms: float


@dataclass(frozen=True)
class PlannedCue:
    id: str
    start_ms: float
    end_ms: float
    text: str
    style_id: str | None
    karaoke: tuple[KaraokeSpan, ...]
    row_index: int | None


@dataclass(frozen=True)
class PlannedTextOverlay:
    """`style` keeps only the style leaves the document carries (absent differs from null)."""

    id: str
    text: str
    x: float
    y: float
    size_pct: float
    color: str
    bg: str
    anim: str
    start_ms: float
    end_ms: float
    style: Mapping[str, Any]
    row_index: int | None
    kind: str = "text"


@dataclass(frozen=True)
class PlannedImageOverlay:
    """`animation` is the asset's sheet layout, None for a still. An overlay whose snapped end
    is not after its start stays in the plan and is never drawn."""

    id: str
    asset_id: str
    x: float
    y: float
    width_pct: float
    opacity: float
    anim: str
    start_ms: float
    end_ms: float
    animation: Mapping[str, int] | None
    row_index: int | None
    kind: str = "image"


@dataclass(frozen=True)
class TrimPlan:
    """A voice clip cut at the end of its cue: only `play_ms` of timeline time is heard."""

    cue_id: str
    to_ms: int
    play_ms: int
    fade_ms: int


@dataclass(frozen=True)
class PlannedVoiceClip:
    id: str
    asset_id: str
    src_in_ms: int
    src_out_ms: int
    at_ms: int
    dur_ms: int
    gain_db: float
    fade_in_ms: int
    fade_out_ms: int
    fit_policy: str
    rate: float
    trim: TrimPlan | None
    stem: bool


@dataclass(frozen=True)
class PlannedMusicClip:
    id: str
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
class TimelinePlan:
    canvas: Canvas
    profile: str
    out_width: int
    out_height: int
    scenes: tuple[PlannedScene, ...]
    chunks: tuple[Chunk, ...]
    total_ms: float
    cues: tuple[PlannedCue, ...]
    caption_style: Mapping[str, Any]
    caption_styles: Mapping[str, Mapping[str, Any]]
    caption_lang: str | None
    snapped: tuple[dict[str, Any], ...]
    handles: tuple[dict[str, str], ...]
    warnings: tuple[str, ...]
    lanes: Mapping[str, bool]
    voice_track_id: str | None
    overlay_lang: str | None
    overlays: tuple[PlannedTextOverlay | PlannedImageOverlay, ...]
    voice: tuple[PlannedVoiceClip, ...]
    music: tuple[PlannedMusicClip, ...]
    duck_enabled: bool


def output_size(width: int, height: int, profile: str) -> tuple[int, int]:
    """Frame of the output file: the canvas for `final`; `draft` shrinks the short side to its
    limit, keeps the aspect, rounds the long side to an even number and never upscales."""
    short_max = PROFILES[profile].max_short_side
    short, long_ = min(width, height), max(width, height)
    if short_max is None or short <= short_max:
        return width, height
    long_out = 2 * ((long_ * short_max + short) // (2 * short))
    return (short_max, long_out) if width <= height else (long_out, short_max)


def is_hdr(probe: Mapping[str, Any]) -> bool:
    return probe.get("colorTransfer") in _HDR_TRANSFERS or "10" in str(probe.get("pixFmt", ""))


def _snap(ms: float, grid: float, path: str, snapped: list[dict[str, Any]]) -> float:
    to = round(ms / grid) * grid
    if abs(to - ms) > 1e-6:
        snapped.append({"path": path, "fromMs": ms, "toMs": round(to, 3)})
    return to


def _snap_scene_dur(
    at_ms: float,
    dur_ms: float,
    at_snapped: float,
    grid: float,
    path: str,
    snapped: list[dict[str, Any]],
) -> float:
    """Snap a scene's END and derive its length from the snapped boundaries.

    Snapping each length alone drifts up to half a frame per scene, so scenes that touch in the
    document stop touching after a few of them."""
    end = round((at_ms + dur_ms) / grid) * grid
    dur = end - at_snapped
    if abs(dur - dur_ms) > 1e-6:
        snapped.append({"path": path, "fromMs": dur_ms, "toMs": round(dur, 3)})
    return dur


def _is_finite_clip(scene: Scene | PlannedScene) -> bool:
    kind = scene.asset_kind if isinstance(scene, Scene) else scene.kind
    return scene.clip_id is not None and kind != "image"


def _handle_state(finite: bool, available: float, needed: float) -> str:
    if not finite:
        return "unlimited"
    return "enough" if available >= needed - 1e-6 else "short"


def _letters(text: str) -> str:
    """Letters only, NFC and case-folded: what a cue's words are compared with its text by."""
    return "".join(
        ch.casefold()
        for ch in unicodedata.normalize("NFC", text)
        if not (ch.isspace() or unicodedata.category(ch).startswith("P"))
    )


def karaoke_spans(text: str, words: Sequence[CaptionWord]) -> list[str] | None:
    """Cut `text` into one span per word, so the words' times colour what was typed.

    The words must spell the text once whitespace, punctuation and case are ignored; otherwise
    None (the cue prints whole, unhighlighted). Punctuation and spaces go with the word before
    them, leading punctuation with the first span, and the spans join back to the text (NFC).
    """
    if not words:
        return None
    s = unicodedata.normalize("NFC", text)
    word_letters = [_letters(w.t) for w in words]
    if "".join(word_letters) != _letters(s):
        return None
    owner: list[int] = []
    for i, ch in enumerate(s):
        if not (ch.isspace() or unicodedata.category(ch).startswith("P")):
            owner.extend([i] * len(ch.casefold()))
    starts, offset = [0], 0
    for letters in word_letters[:-1]:
        offset += len(letters)
        starts.append(owner[offset] if offset < len(owner) else len(s))
    return [s[a:b] for a, b in zip(starts, [*starts[1:], len(s)], strict=True)]


def _scene_probe(
    scene: Scene, path: str, probes: Mapping[str, Mapping[str, Any]], measured: bool
) -> dict[str, Any]:
    """The probe values a scene is planned with. A plan from declared values (`measured` false)
    treats an absent asset or value as unknown; a render needs every value it reads."""
    if scene.clip_id is None:
        return {}
    given = probes.get(scene.clip_id)
    if given is None:
        if measured:
            raise ValueError(f"{path}.clipId: asset {scene.clip_id!r} was not measured")
        given = {}
    probe = {key: given.get(key) for key in PROBE_KEYS}
    if measured:
        if _is_finite_clip(scene) and not isinstance(probe["durationMs"], int | float):
            raise ValueError(f"{path}.clipId: asset {scene.clip_id!r} has no measured duration")
        if scene.asset_kind == "video" and not isinstance(probe["hasAudio"], bool):
            raise ValueError(f"{path}.clipId: asset {scene.clip_id!r} has no measured audio flag")
    return probe


def _plan_scenes(
    document: Document,
    probes: Mapping[str, Mapping[str, Any]],
    frame: float,
    fps: int,
    snapped: list[dict[str, Any]],
    warnings: list[str],
    measured: bool,
) -> list[PlannedScene]:
    scenes: list[PlannedScene] = []
    count = len(document.scenes)
    for i, scene in enumerate(document.scenes):
        path = f"timeline.scenes[{i}]"
        src_in = _snap(scene.src_in_ms, frame, f"{path}.srcInMs", snapped)
        at = _snap(scene.at_ms, frame, f"{path}.atMs", snapped)
        dur = _snap_scene_dur(scene.at_ms, scene.dur_ms, at, frame, f"{path}.durMs", snapped)
        if i == 0 and at != 0:
            raise ValueError(f"{path}.atMs: the first scene must start at 0, got {scene.at_ms}")
        previous = scenes[-1] if scenes else None
        if previous is not None:
            overlap = (
                previous.transition_out.dur_ms
                if previous.transition_out and previous.transition_out.mode == "overlap"
                else 0.0
            )
            expected = previous.at_ms + previous.dur_ms - overlap
            if abs(at - expected) > frame / 2:
                raise ValueError(
                    f"{path}.atMs: {scene.at_ms} leaves a gap or overlap after the previous scene; "
                    f"expected {expected:.1f} ms"
                )
            at = expected
        transition = None
        if scene.transition_out is not None:
            if i == count - 1:
                raise ValueError(f"{path}.transitionOut: the last scene has no scene to move to")
            raw = scene.transition_out
            length = _snap(raw.dur_ms, frame, f"{path}.transitionOut.durMs", snapped)
            transition = PlannedTransition(raw.kind, max(frame, length), raw.mode)

        probe = _scene_probe(scene, path, probes, measured)
        clamp = None
        known = probe.get("durationMs")
        if _is_finite_clip(scene) and known is not None and scene.src_out_ms > known:
            over = scene.src_out_ms - known
            if over > frame:
                raise ValueError(
                    f"{path}: scene {scene.id} needs source up to {scene.src_out_ms} ms but clip "
                    f"{scene.clip_id} is only {known} ms long; shorten the scene or replace "
                    "the clip"
                )
            clamp = SourceClamp(src_out_ms=known, dur_ms=dur - over)
            warnings.append(
                f"{path}: scene {scene.id} needs source up to {scene.src_out_ms} ms but clip "
                f"{scene.clip_id} is {known} ms long; clamped to the clip end, {over:.1f} ms short"
            )
        if scene.keep_audio and scene.asset_kind == "video" and probe["hasAudio"] is False:
            warnings.append(
                f"{path}: clip {scene.clip_id} has no audio stream; scene {scene.id} is silent "
                "although audio.keep is true"
            )
        if (
            scene.asset_kind == "video"
            and probe["fps"]
            and abs(float(probe["fps"]) - fps) > _FPS_TOLERANCE
        ):
            warnings.append(
                f"{path}: source runs at {probe['fps']} fps, converted to {fps} fps; "
                "motion may stutter"
            )
        if scene.clip_id is not None and is_hdr(probe):
            warnings.append(
                f"{path}: HDR source ({probe['colorTransfer']}/{probe['pixFmt']}) is tone-mapped"
            )
        scenes.append(
            PlannedScene(
                id=scene.id,
                clip_id=scene.clip_id,
                kind=scene.asset_kind,
                src_in_ms=src_in,
                src_out_ms=src_in + dur,
                at_ms=at,
                dur_ms=dur,
                fit=scene.fit,
                fill=scene.fill,
                keep_audio=scene.keep_audio,
                gain_db=scene.gain_db,
                motion=scene.motion,
                transition_out=transition,
                offset_x=scene.offset_x,
                offset_y=scene.offset_y,
                zoom=scene.zoom,
                probe=probe,
                clamp=clamp,
            )
        )
    return scenes


def _plan_handles(scenes: Sequence[PlannedScene], warnings: list[str]) -> list[dict[str, str]]:
    """Check the source an `extend` transition borrows on both sides; list every transition.

    A tail handle whose clip duration is unknown (a plan from declared values) is reported as
    not measured and warned about, never guessed.
    """
    handles: list[dict[str, str]] = []
    for i, scene in enumerate(scenes[:-1]):
        transition = scene.transition_out
        if transition is None:
            continue
        following = scenes[i + 1]
        if transition.mode == "overlap":
            handles.append(
                {
                    "sceneId": scene.id,
                    "mode": "overlap",
                    "reason": f"overlap of {transition.dur_ms:.0f} ms shortens the timeline",
                }
            )
            continue
        half = math.ceil(transition.dur_ms / 2)
        tail_finite = _is_finite_clip(scene)
        known = scene.probe.get("durationMs")
        if tail_finite and known is None:
            tail = "not measured"
            warnings.append(
                f"timeline.scenes[{i}]: clip {scene.clip_id} of scene {scene.id} has no declared "
                "duration; the tail handle of its extend transition is not measured"
            )
        else:
            available = known - scene.src_out_ms if known is not None else 0.0
            tail = _handle_state(tail_finite, available, half)
        head = _handle_state(_is_finite_clip(following), following.src_in_ms, half)
        state = f"tail of {scene.id}: {tail}, head of {following.id}: {head}"
        if "short" in (tail, head):
            raise ValueError(
                f"timeline.scenes[{i}].transitionOut: extend of {transition.dur_ms:.0f} ms needs "
                f"a handle of at least {half} ms on both sides ({state}); use mode overlap"
            )
        handles.append(
            {
                "sceneId": scene.id,
                "mode": "extend",
                "reason": f"{state} (needs {half} ms on each side)",
            }
        )
    return handles


def _cut_ms(scene: PlannedScene, frame: float) -> float:
    """Where the cross-fade out of `scene` starts, floored to the frame grid: a chunk cut here
    keeps whole frames, and the chunk after it replays the fade from this point."""
    transition = scene.transition_out
    assert transition is not None
    tail = transition.dur_ms / 2 if transition.mode == "extend" else 0.0
    start = scene.at_ms + scene.dur_ms + tail - transition.dur_ms
    return math.floor(start / frame + 1e-6) * frame


def _plan_chunks(
    scenes: Sequence[PlannedScene], frame: float, max_chunk_scenes: int
) -> list[Chunk]:
    """Cut the scenes into encode chunks. A chain of transitions longer than `max_chunk_scenes`
    is split into consecutive chunks, because one ffmpeg graph buffers every chain input and its
    memory grows with the chain. A split lands on a transition: the earlier chunk stops where
    the cross-fade starts and the later chunk opens with the earlier chunk's last scene, so the
    fade is encoded once, in the later chunk."""
    chunks: list[Chunk] = []
    current: list[int] = []
    for i, scene in enumerate(scenes):
        current.append(i)
        if scene.transition_out is not None and i != len(scenes) - 1:
            continue
        if len(current) > MAX_CHAIN_SCENES:
            raise ValueError(
                f"timeline.scenes[{current[0]}]: {len(current)} scenes are chained by "
                f"transitions, over the limit of {MAX_CHAIN_SCENES}; add a boundary "
                "without a transition"
            )
        groups = [
            current[at : at + max_chunk_scenes] for at in range(0, len(current), max_chunk_scenes)
        ]
        start = scenes[current[0]].at_ms
        for g, group in enumerate(groups):
            last = scenes[group[-1]]
            cut_after = g < len(groups) - 1
            end = _cut_ms(last, frame) if cut_after else last.at_ms + last.dur_ms
            if end - start < frame / 2:
                raise ValueError(
                    f"timeline.scenes[{group[-1]}]: the scene is shorter than its transition, "
                    "so a chunk cannot end where the transition starts"
                )
            chunks.append(
                Chunk(
                    tuple(group),
                    start,
                    end - start,
                    lead_scene=groups[g - 1][-1] if g > 0 else None,
                    cut_after=cut_after,
                )
            )
            start = end
        current = []
    return chunks


def _plan_cues(
    document: Document, snapped: list[dict[str, Any]], warnings: list[str]
) -> tuple[list[PlannedCue], Mapping[str, Any], str | None]:
    """Cues of the chosen track (none when the lane is off) and the style the track prints with."""
    track_id = document.caption_track_id
    track = None
    if track_id is not None:
        track = document.caption_tracks.get(track_id)
        if track is None:
            raise ValueError(
                f"output.captionTrackId: {track_id!r} is not in timeline.captions.tracks"
            )
    style = track.style if track is not None and track.style else document.caption_style
    if track is None or not document.caption_lane:
        return [], style, track.lang if track else None

    cues: list[PlannedCue] = []
    kept = kept_by_row([cue.row for cue in track.cues], track.rows, "hidden")
    for index, row_idx in kept:
        cue = track.cues[index]
        path = f"timeline.captions.tracks.{track.id}.cues[{index}]"
        if cue.style_id and cue.style_id not in document.caption_styles:
            raise ValueError(f"{path}.styleId: {cue.style_id!r} is not in timeline.captions.styles")
        cue_style = document.caption_styles[cue.style_id] if cue.style_id else style
        spans = karaoke_spans(cue.text, cue.words)
        karaoke: list[KaraokeSpan] = []
        if spans is not None:
            for j, (word, span) in enumerate(zip(cue.words, spans, strict=True)):
                karaoke.append(
                    KaraokeSpan(
                        span,
                        _snap(
                            word.start_ms, CAPTION_GRID_MS, f"{path}.words[{j}].startMs", snapped
                        ),
                        _snap(word.end_ms, CAPTION_GRID_MS, f"{path}.words[{j}].endMs", snapped),
                    )
                )
        elif cue.words and cue_style.get("highlightMode", "sweep") != "off":
            warnings.append(f"{path}: words do not match the cue text; printed whole, no karaoke")
        cues.append(
            PlannedCue(
                id=cue.id,
                start_ms=_snap(cue.start_ms, CAPTION_GRID_MS, f"{path}.startMs", snapped),
                end_ms=_snap(cue.end_ms, CAPTION_GRID_MS, f"{path}.endMs", snapped),
                text=cue.text,
                style_id=cue.style_id,
                karaoke=tuple(karaoke),
                row_index=row_idx,
            )
        )
    return cues, style, track.lang


def declared_probes(document: Document) -> dict[str, dict[str, Any]]:
    """The probe values the document declares per asset, the input of a plan job."""
    return {asset_id: dict(info.probe) for asset_id, info in document.assets.items()}


def _plan_overlays(
    document: Document, frame: float, snapped: list[dict[str, Any]], warnings: list[str]
) -> list[PlannedTextOverlay | PlannedImageOverlay]:
    planned: list[PlannedTextOverlay | PlannedImageOverlay] = []
    for overlay in document.overlays:
        path = f"timeline.overlays[{overlay.index}]"
        if isinstance(overlay, ImageOverlay):
            asset = document.assets[overlay.asset_id]
            if asset.kind != "image":
                raise ValueError(
                    f"{path}.assetId: asset {overlay.asset_id!r} is of kind {asset.kind}; "
                    "an image overlay needs an image asset"
                )
            start = _snap(overlay.start_ms, frame, f"{path}.startMs", snapped)
            end = _snap(overlay.end_ms, frame, f"{path}.endMs", snapped)
            if end <= start:
                warnings.append(
                    f"{path}: image overlay {overlay.id} is shorter than a frame and is not drawn"
                )
            planned.append(
                PlannedImageOverlay(
                    id=overlay.id,
                    asset_id=overlay.asset_id,
                    x=overlay.x,
                    y=overlay.y,
                    width_pct=overlay.width_pct,
                    opacity=overlay.opacity,
                    anim=overlay.anim,
                    start_ms=start,
                    end_ms=end,
                    animation=asset.animation,
                    row_index=overlay.row_index,
                )
            )
            continue
        text = _overlay_text(overlay, document.overlay_lang)
        if text is None:
            language = (
                f"language {document.overlay_lang} (output.overlayLang)"
                if document.overlay_lang is not None
                else "any language: output.overlayLang is not set"
            )
            warnings.append(
                f"{path}: text overlay {overlay.id} has no text for {language}; skipped"
            )
            continue
        planned.append(
            PlannedTextOverlay(
                id=overlay.id,
                text=text,
                x=overlay.x,
                y=overlay.y,
                size_pct=overlay.size_pct,
                color=overlay.color,
                bg=overlay.bg,
                anim=overlay.anim,
                start_ms=_snap(overlay.start_ms, CAPTION_GRID_MS, f"{path}.startMs", snapped),
                end_ms=_snap(overlay.end_ms, CAPTION_GRID_MS, f"{path}.endMs", snapped),
                style=overlay.style,
                row_index=overlay.row_index,
            )
        )
    return planned


def _overlay_text(overlay: TextOverlay, lang: str | None) -> str | None:
    return None if lang is None else overlay.text.get(lang)


def _trim_cues(document: Document) -> tuple[dict[str, int], str]:
    """End of every cue of the caption track the voice track is linked to, and the reason a cue
    would be missing. The linked track, not the one printed, is the source of the cue ends."""
    track_id = document.voice_track_id
    voice = document.voice_tracks.get(track_id) if track_id is not None else None
    if voice is None:
        return {}, ""
    if voice.caption_track_id is None:
        return {}, f"voice track {voice.id} is linked to no caption track"
    track = document.caption_tracks.get(voice.caption_track_id)
    if track is None:
        return {}, (
            f"caption track {voice.caption_track_id} linked by voice track {voice.id} "
            "is not in the project"
        )
    return {cue.id: cue.end_ms for cue in track.cues}, (
        f"caption track {track.id} has no such cue"
    )


def voice_trim(
    clip: VoiceClip, cue_end: Mapping[str, int], why_missing: str, path: str, warnings: list[str]
) -> TrimPlan | None:
    """The `trim_tail` rule: a clip longer than its cue is cut at the cue end.

    None plays the clip whole (other policy, no cue linked, or already inside its cue); a cue
    that cannot be found also plays whole, with a warning. `play_ms` 0 means the clip starts
    after the cue ended and is not played. The closing fade ends exactly at the cut, and every
    figure is timeline time (`durMs` is already after the speed change).
    """
    if clip.fit_policy != "trim_tail" or clip.cue_id is None:
        return None
    end = cue_end.get(clip.cue_id)
    if end is None:
        warnings.append(
            f"{path}: voice clip {clip.id} is linked to cue {clip.cue_id} but {why_missing}; "
            f"played whole for {clip.dur_ms} ms, not trimmed"
        )
        return None
    play = end - clip.at_ms
    if play >= clip.dur_ms:
        return None
    if play <= 0:
        warnings.append(
            f"{path}: voice clip {clip.id} starts at {clip.at_ms} ms, after its cue "
            f"{clip.cue_id} ended ({end} ms); cut entirely, not played"
        )
        return TrimPlan(clip.cue_id, end, 0, 0)
    return TrimPlan(clip.cue_id, end, play, min(play, max(VOICE_TRIM_FADE_MS, clip.fade_out_ms)))


def _clamp_fades(
    path: str, play_ms: float, fade_in_ms: int, fade_out_ms: int, warnings: list[str]
) -> tuple[int, int]:
    """Fades cannot outlast the played length; the planned values are what the mix applies."""
    limit = max(0, math.floor(play_ms))
    for name, value in (("fadeInMs", fade_in_ms), ("fadeOutMs", fade_out_ms)):
        if value > limit:
            warnings.append(
                f"{path}.{name}: {value} ms is longer than the {play_ms:g} ms the clip plays; "
                f"clamped to {limit} ms"
            )
    return min(fade_in_ms, limit), min(fade_out_ms, limit)


def _plan_voice(
    document: Document,
    probes: Mapping[str, Mapping[str, Any]],
    frame: float,
    warnings: list[str],
) -> list[PlannedVoiceClip]:
    cue_end, why_missing = _trim_cues(document)
    planned: list[PlannedVoiceClip] = []
    for clip in document.voice_clips:
        path = f"timeline.voice.tracks.{document.voice_track_id}.clips[{clip.index}]"
        expected = math.floor((clip.src_out_ms - clip.src_in_ms) / clip.rate + 0.5)
        if abs(clip.dur_ms - expected) > frame:
            raise ValueError(
                f"{path}.durMs: {clip.dur_ms} differs from round((srcOutMs - srcInMs) / rate) = "
                f"{expected} by more than one frame ({frame:.1f} ms) at rate {clip.rate:g}"
            )
        known = probes.get(clip.asset_id, {}).get("durationMs")
        if isinstance(known, int | float) and known < clip.src_out_ms - 1e-6:
            effect = (
                "the tail is cut"
                if clip.fit_policy == "trim_tail"
                else f"policy {clip.fit_policy} is handled before the render"
            )
            warnings.append(
                f"{path}: asset {clip.asset_id} is {known} ms long, shorter than srcOutMs "
                f"{clip.src_out_ms}; {effect}"
            )
        trim = voice_trim(clip, cue_end, why_missing, path, warnings)
        if trim is not None and trim.play_ms <= 0:
            continue
        fade_in_ms, fade_out_ms = _clamp_fades(
            path,
            trim.play_ms if trim is not None else clip.dur_ms,
            clip.fade_in_ms,
            clip.fade_out_ms,
            warnings,
        )
        planned.append(
            PlannedVoiceClip(
                id=clip.id,
                asset_id=clip.asset_id,
                src_in_ms=clip.src_in_ms,
                src_out_ms=clip.src_out_ms,
                at_ms=clip.at_ms,
                dur_ms=clip.dur_ms,
                gain_db=clip.gain_db,
                fade_in_ms=fade_in_ms,
                fade_out_ms=fade_out_ms,
                fit_policy=clip.fit_policy,
                rate=clip.rate,
                trim=trim,
                stem=document.assets[clip.asset_id].role == "stem",
            )
        )
    return planned


def _plan_music(
    document: Document, probes: Mapping[str, Mapping[str, Any]], warnings: list[str]
) -> list[PlannedMusicClip]:
    planned: list[PlannedMusicClip] = []
    for clip in document.music_clips:
        known = probes.get(clip.asset_id, {}).get("durationMs")
        if isinstance(known, int | float) and known < clip.src_out_ms - 1e-6:
            effect = (
                "the looped segment shortens to the file"
                if clip.loop
                else "the tail of the clip is silent"
            )
            warnings.append(
                f"timeline.music.clips[{clip.index}]: asset {clip.asset_id} is {known} ms long, "
                f"shorter than srcOutMs {clip.src_out_ms}; {effect}"
            )
        fade_in_ms, fade_out_ms = _clamp_fades(
            f"timeline.music.clips[{clip.index}]",
            clip.dur_ms,
            clip.fade_in_ms,
            clip.fade_out_ms,
            warnings,
        )
        planned.append(
            PlannedMusicClip(
                id=clip.id,
                asset_id=clip.asset_id,
                src_in_ms=clip.src_in_ms,
                src_out_ms=clip.src_out_ms,
                at_ms=clip.at_ms,
                dur_ms=clip.dur_ms,
                gain_db=clip.gain_db,
                fade_in_ms=fade_in_ms,
                fade_out_ms=fade_out_ms,
                loop=clip.loop,
            )
        )
    return planned


def _compare_declared_durations(
    document: Document, probes: Mapping[str, Mapping[str, Any]], warnings: list[str]
) -> None:
    for asset_id, measured in probes.items():
        info = document.assets.get(asset_id)
        claimed = info.probe.get("durationMs") if info is not None else None
        actual = measured.get("durationMs")
        if (
            claimed is not None
            and isinstance(actual, int | float)
            and abs(claimed - actual) > DURATION_TOLERANCE_MS
        ):
            warnings.append(
                f"assets.{asset_id}.probe.durationMs: declared {claimed} ms but measured "
                f"{actual} ms"
            )


def plan_timeline(
    document: Document,
    probes: Mapping[str, Mapping[str, Any]],
    profile: str,
    max_chunk_scenes: int = MAX_CHUNK_SCENES,
    *,
    measured: bool = True,
) -> TimelinePlan:
    """Plan the whole render. `probes` maps an asset id to its probe values (`PROBE_KEYS`).

    `measured` true is a render: every value a check reads must be there, and a declared
    duration far from the measured one is warned about. False is a plan from declared values:
    an absent value skips its check and is reported as not measured.
    """
    if profile not in PROFILES:
        raise ValueError(f"params.profile: must be one of {', '.join(PROFILES)}, got {profile!r}")
    if max_chunk_scenes < 1:
        raise ValueError(f"max_chunk_scenes: must be at least 1, got {max_chunk_scenes}")
    canvas = document.canvas
    frame = 1000.0 / canvas.fps
    snapped: list[dict[str, Any]] = []
    warnings: list[str] = []

    scenes = _plan_scenes(document, probes, frame, canvas.fps, snapped, warnings, measured)
    handles = _plan_handles(scenes, warnings)
    chunks = _plan_chunks(scenes, frame, max_chunk_scenes)
    cues, style, lang = _plan_cues(document, snapped, warnings)
    overlays = _plan_overlays(document, frame, snapped, warnings)
    voice = _plan_voice(document, probes, frame, warnings)
    music = _plan_music(document, probes, warnings)
    if measured:
        _compare_declared_durations(document, probes, warnings)
    out_width, out_height = output_size(canvas.width, canvas.height, profile)
    last = scenes[-1]
    return TimelinePlan(
        canvas=canvas,
        profile=profile,
        out_width=out_width,
        out_height=out_height,
        scenes=tuple(scenes),
        chunks=tuple(chunks),
        total_ms=last.at_ms + last.dur_ms,
        cues=tuple(cues),
        caption_style=style,
        caption_styles=document.caption_styles,
        caption_lang=lang,
        snapped=tuple(snapped),
        handles=tuple(handles),
        warnings=tuple(warnings),
        lanes=document.lanes,
        voice_track_id=document.voice_track_id,
        overlay_lang=document.overlay_lang,
        overlays=tuple(overlays),
        voice=tuple(voice),
        music=tuple(music),
        duck_enabled=document.duck_enabled,
    )
