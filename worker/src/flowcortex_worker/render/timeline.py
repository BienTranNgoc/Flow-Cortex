"""Plans a project render from the checked document and the measured assets.

Pure: no process runs here. Scene marks are snapped to the frame grid, transitions get their
handles, scenes are cut into encode chunks, and the caption cues of the chosen track are picked.
Anything the plan cannot honour raises `ValueError("<path>: <reason>")`.
"""

from __future__ import annotations

import math
import unicodedata
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any

from .document import Canvas, CaptionWord, Document, Motion, Scene, kept_by_row

MAX_CHAIN_SCENES = 12
# Peak RSS of one ffmpeg grows about 340 MiB per chained scene (3 scenes 1178 MiB, 6 scenes
# 2212 MiB); a 60 s 1080x1920 render with 4-scene chunks peaked at 1761 MiB.
MAX_CHUNK_SCENES = 4
CAPTION_GRID_MS = 10
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
    scene: Scene, path: str, probes: Mapping[str, Mapping[str, Any]]
) -> dict[str, Any]:
    if scene.clip_id is None:
        return {}
    measured = probes.get(scene.clip_id)
    if measured is None:
        raise ValueError(f"{path}.clipId: asset {scene.clip_id!r} was not measured")
    probe = {key: measured.get(key) for key in PROBE_KEYS}
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

        probe = _scene_probe(scene, path, probes)
        clamp = None
        if _is_finite_clip(scene) and scene.src_out_ms > probe["durationMs"]:
            known = probe["durationMs"]
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


def _plan_handles(scenes: Sequence[PlannedScene]) -> list[dict[str, str]]:
    """Check the source an `extend` transition borrows on both sides; list every transition."""
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
        tail = _handle_state(
            tail_finite,
            (scene.probe["durationMs"] - scene.src_out_ms) if tail_finite else 0.0,
            half,
        )
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


def plan_timeline(
    document: Document,
    probes: Mapping[str, Mapping[str, Any]],
    profile: str,
    max_chunk_scenes: int = MAX_CHUNK_SCENES,
) -> TimelinePlan:
    """Plan the whole render. `probes` maps an asset id to its measured values (`PROBE_KEYS`)."""
    if profile not in PROFILES:
        raise ValueError(f"params.profile: must be one of {', '.join(PROFILES)}, got {profile!r}")
    if max_chunk_scenes < 1:
        raise ValueError(f"max_chunk_scenes: must be at least 1, got {max_chunk_scenes}")
    canvas = document.canvas
    frame = 1000.0 / canvas.fps
    snapped: list[dict[str, Any]] = []
    warnings: list[str] = []

    scenes = _plan_scenes(document, probes, frame, canvas.fps, snapped, warnings)
    handles = _plan_handles(scenes)
    chunks = _plan_chunks(scenes, frame, max_chunk_scenes)
    cues, style, lang = _plan_cues(document, snapped, warnings)
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
    )
