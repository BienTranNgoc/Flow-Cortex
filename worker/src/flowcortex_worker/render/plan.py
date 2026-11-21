"""The plan core, its hash and the closed list of document leaves the renderer honours.

Pure. The core holds what the document and the profile decide and nothing a probe measured, so a
plan job (declared values) and a render (measured values) of one document agree on `planHash`.
"""

from __future__ import annotations

import hashlib
import json
from collections.abc import Mapping
from typing import Any

from .document import Document, render_asset_ids
from .timeline import (
    MIX_DEFAULTS,
    PlannedCue,
    PlannedImageOverlay,
    PlannedMusicClip,
    PlannedScene,
    PlannedTextOverlay,
    PlannedVoiceClip,
    TimelinePlan,
)

RENDERER = "flowcortex.video.render/2"

HONOURS: tuple[str, ...] = (
    "$.assets{}.animation.cols",
    "$.assets{}.animation.frameCount",
    "$.assets{}.animation.frameH",
    "$.assets{}.animation.frameMs",
    "$.assets{}.animation.frameW",
    "$.assets{}.probe.colorTransfer",
    "$.assets{}.probe.durationMs",
    "$.assets{}.probe.fps",
    "$.assets{}.probe.hasAudio",
    "$.assets{}.probe.height",
    "$.assets{}.probe.pixFmt",
    "$.assets{}.probe.rotation",
    "$.assets{}.probe.width",
    "$.canvas.fps",
    "$.canvas.height",
    "$.canvas.sampleRate",
    "$.canvas.width",
    "$.output.captionTrackId",
    "$.output.lanes.captions",
    "$.output.lanes.music",
    "$.output.lanes.overlays",
    "$.output.lanes.voice",
    "$.output.overlayLang",
    "$.output.voiceTrackId",
    "$.timeline.captions.style.animation",
    "$.timeline.captions.style.backdrop",
    "$.timeline.captions.style.backdropColor",
    "$.timeline.captions.style.bold",
    "$.timeline.captions.style.boxWidth",
    "$.timeline.captions.style.fontFamily",
    "$.timeline.captions.style.fontSize",
    "$.timeline.captions.style.highlightColor",
    "$.timeline.captions.style.highlightMode",
    "$.timeline.captions.style.italic",
    "$.timeline.captions.style.marginPct",
    "$.timeline.captions.style.maxSyllablesPerLine",
    "$.timeline.captions.style.outlineColor",
    "$.timeline.captions.style.outlineWidth",
    "$.timeline.captions.style.position",
    "$.timeline.captions.style.preset",
    "$.timeline.captions.style.scale",
    "$.timeline.captions.style.shadow.color",
    "$.timeline.captions.style.textColor",
    "$.timeline.captions.style.uppercase",
    "$.timeline.captions.style.x",
    "$.timeline.captions.style.y",
    "$.timeline.captions.styles{}.animation",
    "$.timeline.captions.styles{}.backdrop",
    "$.timeline.captions.styles{}.backdropColor",
    "$.timeline.captions.styles{}.bold",
    "$.timeline.captions.styles{}.boxWidth",
    "$.timeline.captions.styles{}.fontFamily",
    "$.timeline.captions.styles{}.fontSize",
    "$.timeline.captions.styles{}.highlightColor",
    "$.timeline.captions.styles{}.highlightMode",
    "$.timeline.captions.styles{}.italic",
    "$.timeline.captions.styles{}.marginPct",
    "$.timeline.captions.styles{}.maxSyllablesPerLine",
    "$.timeline.captions.styles{}.outlineColor",
    "$.timeline.captions.styles{}.outlineWidth",
    "$.timeline.captions.styles{}.position",
    "$.timeline.captions.styles{}.preset",
    "$.timeline.captions.styles{}.scale",
    "$.timeline.captions.styles{}.shadow.color",
    "$.timeline.captions.styles{}.textColor",
    "$.timeline.captions.styles{}.uppercase",
    "$.timeline.captions.styles{}.x",
    "$.timeline.captions.styles{}.y",
    "$.timeline.captions.tracks{}.cues[].endMs",
    "$.timeline.captions.tracks{}.cues[].row",
    "$.timeline.captions.tracks{}.cues[].startMs",
    "$.timeline.captions.tracks{}.cues[].styleId",
    "$.timeline.captions.tracks{}.cues[].text",
    "$.timeline.captions.tracks{}.cues[].words[].endMs",
    "$.timeline.captions.tracks{}.cues[].words[].startMs",
    "$.timeline.captions.tracks{}.cues[].words[].t",
    "$.timeline.captions.tracks{}.rows[].hidden",
    "$.timeline.captions.tracks{}.rows[].id",
    "$.timeline.captions.tracks{}.style.animation",
    "$.timeline.captions.tracks{}.style.backdrop",
    "$.timeline.captions.tracks{}.style.backdropColor",
    "$.timeline.captions.tracks{}.style.bold",
    "$.timeline.captions.tracks{}.style.boxWidth",
    "$.timeline.captions.tracks{}.style.fontFamily",
    "$.timeline.captions.tracks{}.style.fontSize",
    "$.timeline.captions.tracks{}.style.highlightColor",
    "$.timeline.captions.tracks{}.style.highlightMode",
    "$.timeline.captions.tracks{}.style.italic",
    "$.timeline.captions.tracks{}.style.marginPct",
    "$.timeline.captions.tracks{}.style.maxSyllablesPerLine",
    "$.timeline.captions.tracks{}.style.outlineColor",
    "$.timeline.captions.tracks{}.style.outlineWidth",
    "$.timeline.captions.tracks{}.style.position",
    "$.timeline.captions.tracks{}.style.preset",
    "$.timeline.captions.tracks{}.style.scale",
    "$.timeline.captions.tracks{}.style.shadow.color",
    "$.timeline.captions.tracks{}.style.textColor",
    "$.timeline.captions.tracks{}.style.uppercase",
    "$.timeline.captions.tracks{}.style.x",
    "$.timeline.captions.tracks{}.style.y",
    "$.timeline.music.clips[].assetId",
    "$.timeline.music.clips[].atMs",
    "$.timeline.music.clips[].durMs",
    "$.timeline.music.clips[].fadeInMs",
    "$.timeline.music.clips[].fadeOutMs",
    "$.timeline.music.clips[].gainDb",
    "$.timeline.music.clips[].loop",
    "$.timeline.music.clips[].row",
    "$.timeline.music.clips[].srcInMs",
    "$.timeline.music.clips[].srcOutMs",
    "$.timeline.music.duck.enabled",
    "$.timeline.music.rows[].id",
    "$.timeline.music.rows[].muted",
    "$.timeline.overlayRows.image[].hidden",
    "$.timeline.overlayRows.image[].id",
    "$.timeline.overlayRows.text[].hidden",
    "$.timeline.overlayRows.text[].id",
    "$.timeline.overlays[].accent.color",
    "$.timeline.overlays[].accent.outlineColor",
    "$.timeline.overlays[].align",
    "$.timeline.overlays[].anchor",
    "$.timeline.overlays[].anim",
    "$.timeline.overlays[].assetId",
    "$.timeline.overlays[].bg",
    "$.timeline.overlays[].bgColor",
    "$.timeline.overlays[].bold",
    "$.timeline.overlays[].color",
    "$.timeline.overlays[].endMs",
    "$.timeline.overlays[].font",
    "$.timeline.overlays[].glow.blurPct",
    "$.timeline.overlays[].glow.color",
    "$.timeline.overlays[].glow.spreadPct",
    "$.timeline.overlays[].glow.strength",
    "$.timeline.overlays[].italic",
    "$.timeline.overlays[].kind",
    "$.timeline.overlays[].letterSpacingPct",
    "$.timeline.overlays[].lines[].color",
    "$.timeline.overlays[].lines[].font",
    "$.timeline.overlays[].lines[].sizeScale",
    "$.timeline.overlays[].opacity",
    "$.timeline.overlays[].outline.color",
    "$.timeline.overlays[].outline.widthPct",
    "$.timeline.overlays[].rotate",
    "$.timeline.overlays[].row",
    "$.timeline.overlays[].shadow.angle",
    "$.timeline.overlays[].shadow.blurPct",
    "$.timeline.overlays[].shadow.color",
    "$.timeline.overlays[].shadow.distancePct",
    "$.timeline.overlays[].shadow.opacity",
    "$.timeline.overlays[].sizePct",
    "$.timeline.overlays[].startMs",
    "$.timeline.overlays[].text{}",
    "$.timeline.overlays[].underline",
    "$.timeline.overlays[].widthPct",
    "$.timeline.overlays[].x",
    "$.timeline.overlays[].y",
    "$.timeline.scenes[].atMs",
    "$.timeline.scenes[].audio.gainDb",
    "$.timeline.scenes[].audio.keep",
    "$.timeline.scenes[].clipId",
    "$.timeline.scenes[].durMs",
    "$.timeline.scenes[].fill",
    "$.timeline.scenes[].fit",
    "$.timeline.scenes[].motion.from",
    "$.timeline.scenes[].motion.kind",
    "$.timeline.scenes[].motion.to",
    "$.timeline.scenes[].offsetX",
    "$.timeline.scenes[].offsetY",
    "$.timeline.scenes[].srcInMs",
    "$.timeline.scenes[].srcOutMs",
    "$.timeline.scenes[].transitionOut.durMs",
    "$.timeline.scenes[].transitionOut.kind",
    "$.timeline.scenes[].transitionOut.mode",
    "$.timeline.scenes[].zoom",
    "$.timeline.voice.tracks{}.captionTrackId",
    "$.timeline.voice.tracks{}.clips[].assetId",
    "$.timeline.voice.tracks{}.clips[].atMs",
    "$.timeline.voice.tracks{}.clips[].cueId",
    "$.timeline.voice.tracks{}.clips[].durMs",
    "$.timeline.voice.tracks{}.clips[].fadeInMs",
    "$.timeline.voice.tracks{}.clips[].fadeOutMs",
    "$.timeline.voice.tracks{}.clips[].fitPolicy",
    "$.timeline.voice.tracks{}.clips[].gainDb",
    "$.timeline.voice.tracks{}.clips[].rate",
    "$.timeline.voice.tracks{}.clips[].row",
    "$.timeline.voice.tracks{}.clips[].srcInMs",
    "$.timeline.voice.tracks{}.clips[].srcOutMs",
    "$.timeline.voice.tracks{}.rows[].id",
    "$.timeline.voice.tracks{}.rows[].muted",
)


def honours() -> list[str]:
    return list(HONOURS)


def _canon(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def _sha256(text: str) -> str:
    return "sha256:" + hashlib.sha256(text.encode("utf-8")).hexdigest()


def manifest_hash() -> str:
    return _sha256("\n".join(sorted(HONOURS)))


def _plain(value: Any) -> Any:
    """Mappings and sequences as dicts and lists, so the canonical JSON never sees a subclass."""
    if isinstance(value, Mapping):
        return {str(k): _plain(v) for k, v in value.items()}
    if isinstance(value, list | tuple):
        return [_plain(v) for v in value]
    return value


def _scene(scene: PlannedScene) -> dict[str, Any]:
    motion = scene.motion
    transition = scene.transition_out
    return {
        "id": scene.id,
        "clipId": scene.clip_id,
        "kind": scene.kind,
        "srcInMs": scene.src_in_ms,
        "srcOutMs": scene.src_out_ms,
        "atMs": scene.at_ms,
        "durMs": scene.dur_ms,
        "fit": scene.fit,
        "fill": scene.fill,
        "keepAudio": scene.keep_audio,
        "gainDb": scene.gain_db,
        "motion": None
        if motion is None
        else {"kind": motion.kind, "from": motion.from_scale, "to": motion.to_scale},
        "transitionOut": None
        if transition is None
        else {"kind": transition.kind, "durMs": transition.dur_ms, "mode": transition.mode},
        "offsetX": scene.offset_x,
        "offsetY": scene.offset_y,
        "zoom": scene.zoom,
    }


def _cue(cue: PlannedCue) -> dict[str, Any]:
    entry: dict[str, Any] = {
        "id": cue.id,
        "startMs": cue.start_ms,
        "endMs": cue.end_ms,
        "text": cue.text,
        "styleId": cue.style_id,
        "karaoke": [
            {"text": s.text, "startMs": s.start_ms, "endMs": s.end_ms} for s in cue.karaoke
        ],
    }
    if cue.row_index is not None:
        entry["rowIndex"] = cue.row_index
    return entry


def _overlay(overlay: PlannedTextOverlay | PlannedImageOverlay) -> dict[str, Any]:
    entry: dict[str, Any]
    if isinstance(overlay, PlannedTextOverlay):
        entry = {
            "id": overlay.id,
            "kind": "text",
            "text": overlay.text,
            "x": overlay.x,
            "y": overlay.y,
            "sizePct": overlay.size_pct,
            "color": overlay.color,
            "bg": overlay.bg,
            "anim": overlay.anim,
            "startMs": overlay.start_ms,
            "endMs": overlay.end_ms,
            "style": _plain(overlay.style),
        }
    else:
        entry = {
            "id": overlay.id,
            "kind": "image",
            "assetId": overlay.asset_id,
            "x": overlay.x,
            "y": overlay.y,
            "widthPct": overlay.width_pct,
            "opacity": overlay.opacity,
            "anim": overlay.anim,
            "startMs": overlay.start_ms,
            "endMs": overlay.end_ms,
            "animation": _plain(overlay.animation),
        }
    if overlay.row_index is not None:
        entry["rowIndex"] = overlay.row_index
    return entry


def _voice(clip: PlannedVoiceClip) -> dict[str, Any]:
    trim = clip.trim
    entry: dict[str, Any] = {
        "id": clip.id,
        "assetId": clip.asset_id,
        "srcInMs": clip.src_in_ms,
        "srcOutMs": clip.src_out_ms,
        "atMs": clip.at_ms,
        "durMs": clip.dur_ms,
        "gainDb": clip.gain_db,
        "fadeInMs": clip.fade_in_ms,
        "fadeOutMs": clip.fade_out_ms,
        "fitPolicy": clip.fit_policy,
        "rate": clip.rate,
        "trim": None
        if trim is None
        else {
            "cueId": trim.cue_id,
            "toMs": trim.to_ms,
            "playMs": trim.play_ms,
            "fadeMs": trim.fade_ms,
        },
    }
    if clip.stem:
        entry["stem"] = True
    return entry


def _music(clip: PlannedMusicClip) -> dict[str, Any]:
    return {
        "id": clip.id,
        "assetId": clip.asset_id,
        "srcInMs": clip.src_in_ms,
        "srcOutMs": clip.src_out_ms,
        "atMs": clip.at_ms,
        "durMs": clip.dur_ms,
        "gainDb": clip.gain_db,
        "fadeInMs": clip.fade_in_ms,
        "fadeOutMs": clip.fade_out_ms,
        "loop": clip.loop,
    }


def _asset_identities(plan: TimelinePlan, document: Document) -> dict[str, str]:
    """A declared content hash when the asset carries one, else id, duration and size."""
    identities: dict[str, str] = {}
    for asset_id in render_asset_ids(document):
        info = document.assets.get(asset_id)
        probe = info.probe if info is not None else {}
        declared = probe.get("sha256")
        identities[asset_id] = (
            declared if declared else f"{asset_id}:{probe.get('durationMs')}:{probe.get('bytes')}"
        )
    return identities


def plan_core(plan: TimelinePlan, document: Document) -> dict[str, Any]:
    """Everything the hash covers; no probe value and no source clamp."""
    canvas = plan.canvas
    return {
        "renderer": RENDERER,
        "profile": plan.profile,
        "canvas": {
            "width": canvas.width,
            "height": canvas.height,
            "fps": canvas.fps,
            "sampleRate": canvas.sample_rate,
        },
        "output": {"width": plan.out_width, "height": plan.out_height},
        "tracks": {
            "captionTrackId": document.caption_track_id,
            "voiceTrackId": plan.voice_track_id,
            "overlayLang": plan.overlay_lang,
        },
        "lanes": _plain(plan.lanes),
        "scenes": [_scene(s) for s in plan.scenes],
        "chunks": [
            {
                "sceneIndexes": list(c.scene_indexes),
                "leadScene": c.lead_scene,
                "cutAfter": c.cut_after,
                "startMs": c.start_ms,
                "durMs": c.dur_ms,
            }
            for c in plan.chunks
        ],
        "captions": {
            "style": _plain(plan.caption_style),
            "styles": _plain(plan.caption_styles),
            "cues": [_cue(c) for c in plan.cues],
        },
        "overlays": [_overlay(o) for o in plan.overlays],
        "voice": [_voice(c) for c in plan.voice],
        "music": {"clips": [_music(c) for c in plan.music], "duck": plan.duck_enabled},
        "defaults": _plain(MIX_DEFAULTS),
        "assets": _asset_identities(plan, document),
    }


def plan_hash(core: Mapping[str, Any]) -> str:
    return _sha256(_canon(core))


def plan_document(plan: TimelinePlan, document: Document) -> dict[str, Any]:
    """The `plan.json` body: the core plus its hash, the length and the encode count."""
    core = plan_core(plan, document)
    return {
        **core,
        "durationMs": round(plan.total_ms),
        "encodeCount": len(plan.chunks),
        "planHash": plan_hash(core),
    }
