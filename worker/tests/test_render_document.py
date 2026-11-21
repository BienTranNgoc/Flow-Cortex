"""The project document and job contract: every refusal names the path it came from."""

from __future__ import annotations

import copy
from collections.abc import Callable
from typing import Any

import pytest

from flowcortex_worker.render.document import (
    TRANSITION_KINDS,
    TextOverlay,
    parse_document,
    parse_job,
)

Doc = dict[str, Any]


def scene(index: int = 0, **over: Any) -> dict[str, Any]:
    base: dict[str, Any] = {
        "id": f"sc{index}",
        "clipId": "a1",
        "srcInMs": 0,
        "srcOutMs": 3000,
        "atMs": 3000 * index,
        "durMs": 3000,
        "fit": "cover",
        "fill": "#000000",
        "audio": {"keep": True, "gainDb": 0},
        "motion": None,
        "transitionOut": None,
    }
    base.update(over)
    return base


def style(**over: Any) -> dict[str, Any]:
    return {"fontSize": "medium", "highlightMode": "sweep", **over}


def project() -> Doc:
    return {
        "canvas": {"width": 1080, "height": 1920, "fps": 30, "sampleRate": 48000},
        "assets": {
            "a1": {"kind": "video"},
            "a2": {"kind": "image"},
            "aud": {"kind": "audio"},
            "v1": {"kind": "audio", "role": "dub"},
            "m1": {"kind": "audio", "role": "music"},
        },
        "output": {
            "captionTrackId": "ct_main",
            "voiceTrackId": None,
            "overlayLang": "vi",
            "lanes": {"captions": True, "voice": False, "music": False, "overlays": False},
        },
        "timeline": {
            "scenes": [scene(0), scene(1, clipId="a2")],
            "overlays": [],
            "captions": {
                "style": style(),
                "styles": {"loud": style(scale=1.5)},
                "tracks": {
                    "ct_main": {
                        "lang": "vi",
                        "rows": [{"id": "rw1"}],
                        "cues": [
                            {
                                "id": "cu1",
                                "startMs": 0,
                                "endMs": 1000,
                                "text": "Xin chào",
                                "styleId": None,
                                "row": "rw1",
                                "words": [{"t": "xin", "startMs": 0, "endMs": 400}],
                            }
                        ],
                    }
                },
            },
            "voice": {"tracks": {}},
            "music": {"clips": [], "duck": {"enabled": False}},
        },
    }


def cue(doc: Doc) -> dict[str, Any]:
    cues: list[dict[str, Any]] = doc["timeline"]["captions"]["tracks"]["ct_main"]["cues"]
    return cues[0]


def refuses(mutate: Callable[[Doc], None], *needles: str) -> None:
    doc = project()
    mutate(doc)
    with pytest.raises(ValueError) as caught:
        parse_document(doc)
    for needle in needles:
        assert needle in str(caught.value)


def set_scene(**over: Any) -> Callable[[Doc], None]:
    return lambda doc: doc["timeline"]["scenes"][0].update(over)


def test_valid_project_parses() -> None:
    parsed = parse_document(project())
    assert [s.id for s in parsed.scenes] == ["sc0", "sc1"]
    assert parsed.scenes[1].asset_kind == "image"
    assert parsed.caption_tracks["ct_main"].cues[0].words[0].t == "xin"


def test_transition_kinds_are_the_editor_list() -> None:
    assert len(TRANSITION_KINDS) == 54
    assert len(set(TRANSITION_KINDS)) == 54


def test_transition_kind_outside_the_list_is_refused() -> None:
    kind = "fade:duration=9[x];movie=/etc/passwd"
    refuses(
        set_scene(transitionOut={"kind": kind, "durMs": 400, "mode": "extend"}),
        "timeline.scenes[0].transitionOut.kind",
    )


@pytest.mark.parametrize("kind", TRANSITION_KINDS)
def test_every_listed_transition_kind_is_accepted(kind: str) -> None:
    doc = project()
    doc["timeline"]["scenes"][0]["transitionOut"] = {"kind": kind, "durMs": 400, "mode": "overlap"}
    assert parse_document(doc).scenes[0].transition_out is not None


@pytest.mark.parametrize(
    ("mutate", "needle"),
    [
        (lambda d: d["canvas"].update(width=1081), "canvas.width"),
        (lambda d: d["canvas"].update(width=2000), "canvas.width"),
        (lambda d: d["canvas"].update(height=14), "canvas.height"),
        (lambda d: d["canvas"].update(fps=60), "canvas.fps"),
        (lambda d: d["canvas"].update(sampleRate=44100), "canvas.sampleRate"),
        (lambda d: d["timeline"].update(scenes=[]), "timeline.scenes"),
        (set_scene(durMs=0, srcOutMs=0), "timeline.scenes[0].durMs"),
        (set_scene(srcOutMs=4000, durMs=2000), "srcOutMs - srcInMs"),
        (set_scene(fit="stretch"), "timeline.scenes[0].fit"),
        (set_scene(fill="red"), "timeline.scenes[0].fill"),
        (set_scene(audio={"keep": True, "gainDb": 13}), "timeline.scenes[0].audio.gainDb"),
        (set_scene(audio={"keep": "yes", "gainDb": 0}), "timeline.scenes[0].audio.keep"),
        (set_scene(offsetX=1.5), "timeline.scenes[0].offsetX"),
        (set_scene(zoom=0.2), "timeline.scenes[0].zoom"),
        (set_scene(atMs=-1), "timeline.scenes[0].atMs"),
        (set_scene(atMs=True), "timeline.scenes[0].atMs"),
        (
            set_scene(transitionOut={"kind": "fade", "durMs": 400, "mode": "cut"}),
            "timeline.scenes[0].transitionOut.mode",
        ),
        (
            set_scene(transitionOut={"kind": "fade", "durMs": 0, "mode": "extend"}),
            "timeline.scenes[0].transitionOut.durMs",
        ),
        (set_scene(clipId="aud"), "timeline.scenes[0].clipId"),
        (set_scene(clipId="missing"), "timeline.scenes[0].clipId"),
        (lambda d: d["assets"]["a1"].update(kind="gif"), "assets.a1.kind"),
    ],
)
def test_scene_canvas_and_asset_leaves_are_refused(
    mutate: Callable[[Doc], None], needle: str
) -> None:
    refuses(mutate, needle)


def test_motion_on_a_video_scene_is_refused() -> None:
    refuses(
        set_scene(motion={"kind": "zoom_in", "from": 1, "to": 1.2}),
        "timeline.scenes[0].motion",
        "image scenes only",
    )


def test_motion_range_and_kind_are_checked_on_an_image_scene() -> None:
    def motion(**over: Any) -> Callable[[Doc], None]:
        spec = {"kind": "zoom_out", "from": 1.3, "to": 1.0, **over}
        return lambda d: d["timeline"]["scenes"][1].update(motion=spec)

    refuses(motion(**{"from": 3.5}), "timeline.scenes[1].motion.from")
    refuses(motion(kind="spin"), "timeline.scenes[1].motion.kind")
    doc = project()
    motion()(doc)
    assert parse_document(doc).scenes[1].motion is not None


@pytest.mark.parametrize(
    ("mutate", "needle"),
    [
        (lambda d: cue(d).pop("startMs"), "cues[0].startMs"),
        (lambda d: cue(d).update(endMs=0), "cues[0].endMs"),
        (lambda d: cue(d).update(text=5), "cues[0].text"),
        (lambda d: cue(d).update(styleId=3), "cues[0].styleId"),
        (lambda d: cue(d)["words"][0].pop("t"), "words[0].t"),
        (lambda d: cue(d)["words"][0].update(startMs=-1), "words[0].startMs"),
        (
            lambda d: d["timeline"]["captions"]["tracks"]["ct_main"]["rows"][0].update(
                hidden="yes"
            ),
            "rows[0].hidden",
        ),
        (
            lambda d: d["timeline"]["captions"]["tracks"]["ct_main"].update(lang="vietnamese"),
            "ct_main.lang",
        ),
        (
            lambda d: d["timeline"]["captions"]["style"].update(fontSize="huge"),
            "captions.style.fontSize",
        ),
        (lambda d: d["timeline"]["captions"]["style"].update(x=1.2), "captions.style.x"),
        (lambda d: d["timeline"]["captions"]["style"].update(scale=0.2), "captions.style.scale"),
        (
            lambda d: d["timeline"]["captions"]["style"].update(boxWidth=0.1),
            "captions.style.boxWidth",
        ),
        (
            lambda d: d["timeline"]["captions"]["style"].update(italic="true"),
            "captions.style.italic",
        ),
        (lambda d: d["timeline"]["captions"]["style"].update(animation="spin"), "style.animation"),
        (
            lambda d: d["timeline"]["captions"]["styles"]["loud"].update(maxSyllablesPerLine=9),
            "styles.loud.maxSyllablesPerLine",
        ),
        (
            lambda d: d["timeline"]["captions"]["tracks"]["ct_main"].update(style={"scale": 9}),
            "ct_main.style.scale",
        ),
        (lambda d: d["output"]["lanes"].update(captions="on"), "output.lanes.captions"),
        (lambda d: d["output"].update(captionTrackId=5), "output.captionTrackId"),
    ],
)
def test_caption_and_output_leaves_are_refused(mutate: Callable[[Doc], None], needle: str) -> None:
    refuses(mutate, needle)


def test_style_scale_error_names_the_range() -> None:
    refuses(lambda d: d["timeline"]["captions"]["style"].update(scale=0.2), "0.3 to 3", "scale")


def test_unknown_voice_track_is_refused() -> None:
    refuses(lambda d: d["output"].update(voiceTrackId="vt_gone"), "output.voiceTrackId")


def test_overlays_off_with_content_is_accepted() -> None:
    doc = project()
    doc["timeline"]["overlays"] = [{"id": "ov1"}, {"id": "ov2", "kind": "image"}]
    parse_document(doc)


def test_voice_on_without_a_track_is_accepted() -> None:
    doc = project()
    doc["output"]["lanes"]["voice"] = True
    parse_document(doc)


def test_voice_on_with_every_clip_on_a_muted_row_is_accepted() -> None:
    doc = project()
    doc["output"]["lanes"]["voice"] = True
    doc["output"]["voiceTrackId"] = "vt_one"
    doc["timeline"]["voice"]["tracks"]["vt_one"] = {
        "rows": [{"id": "rw1", "muted": True}],
        "clips": [{"id": "vc1", "row": "rw1"}, {"id": "vc2", "row": "rw1"}],
    }
    parse_document(doc)


def test_overlays_on_with_every_overlay_on_a_hidden_row_is_accepted() -> None:
    doc = project()
    doc["output"]["lanes"]["overlays"] = True
    doc["timeline"]["overlayRows"] = {
        "text": [{"id": "rw1", "hidden": True}],
        "image": [{"id": "rw9", "hidden": True}],
    }
    doc["timeline"]["overlays"] = [
        {"id": "ov1", "row": "rw1"},
        {"id": "ov2", "kind": "image", "row": "rw9"},
    ]
    parse_document(doc)


def test_parse_document_does_not_mutate_its_input() -> None:
    doc = project()
    before = copy.deepcopy(doc)
    parse_document(doc)
    assert doc == before


def good_params(**over: Any) -> dict[str, Any]:
    return {"profile": "draft", "mode": "render", **over}


KEYS = ["project", "asset:a1", "asset:a2"]


def test_job_with_exact_inputs_lists_the_assets_in_scene_order() -> None:
    spec = parse_job(good_params(), KEYS, parse_document(project()))
    assert spec.profile == "draft"
    assert spec.asset_ids == ("a1", "a2")


def test_repeated_clip_is_one_asset() -> None:
    doc = project()
    doc["timeline"]["scenes"][1]["clipId"] = "a1"
    spec = parse_job(good_params(), ["project", "asset:a1"], parse_document(doc))
    assert spec.asset_ids == ("a1",)


@pytest.mark.parametrize(
    ("params", "needle"),
    [
        ({"profile": "draft", "mode": "render", "renderDefaults": {}}, "params.renderDefaults"),
        ({"profile": "draft", "mode": "render", "lang": "vi"}, "params.lang"),
        ({"profile": "draft", "mode": "dry"}, "params.mode"),
        ({"profile": "draft"}, "params.mode"),
        ({"mode": "render"}, "params.profile"),
        ({"profile": "draft", "mode": "render", "threads": 8}, "params.threads"),
        ({"profile": "draft", "mode": "render", "limits": {}}, "params.limits"),
        ({"profile": "vertical_ad_v1", "mode": "render"}, "params.profile"),
    ],
)
def test_job_params_are_refused(params: dict[str, Any], needle: str) -> None:
    with pytest.raises(ValueError) as caught:
        parse_job(params, KEYS, parse_document(project()))
    assert needle in str(caught.value)


@pytest.mark.parametrize(
    ("keys", "needle"),
    [
        (["project", "asset:a1"], "missing input 'asset:a2'"),
        (["asset:a1", "asset:a2"], "missing input 'project'"),
        ([*KEYS, "asset:a9"], "extra input 'asset:a9'"),
        ([*KEYS, "speech"], "extra input 'speech'"),
        ([*KEYS, "asset:a1"], "duplicate input 'asset:a1'"),
    ],
)
def test_job_inputs_are_refused(keys: list[str], needle: str) -> None:
    with pytest.raises(ValueError) as caught:
        parse_job(good_params(), keys, parse_document(project()))
    assert needle in str(caught.value)


def test_fill_scene_needs_no_asset_input() -> None:
    doc = project()
    doc["timeline"]["scenes"] = [scene(0, clipId=None, fill="#112233")]
    assert parse_job(good_params(), ["project"], parse_document(doc)).asset_ids == ()


def text_overlay(**over: Any) -> dict[str, Any]:
    base: dict[str, Any] = {
        "id": "ov1",
        "text": {"vi": "Xin chào", "en": "Hello"},
        "x": 0.5,
        "y": 0.5,
        "sizePct": 5,
        "color": "#FFFFFF",
        "bg": "none",
        "anim": "none",
        "startMs": 0,
        "endMs": 2000,
    }
    base.update(over)
    return base


def image_overlay(**over: Any) -> dict[str, Any]:
    base: dict[str, Any] = {
        "id": "ov2",
        "kind": "image",
        "assetId": "a2",
        "x": 0.5,
        "y": 0.5,
        "widthPct": 20,
        "opacity": 1,
        "anim": "none",
        "startMs": 0,
        "endMs": 2000,
    }
    base.update(over)
    return base


def voice_clip(**over: Any) -> dict[str, Any]:
    base: dict[str, Any] = {
        "id": "vc1",
        "cueId": None,
        "assetId": "v1",
        "srcInMs": 0,
        "srcOutMs": 3000,
        "atMs": 0,
        "durMs": 3000,
        "gainDb": 0,
        "fadeInMs": 0,
        "fadeOutMs": 0,
        "fitPolicy": "ripple",
        "rate": 1,
    }
    base.update(over)
    return base


def music_clip(**over: Any) -> dict[str, Any]:
    base: dict[str, Any] = {
        "id": "mu1",
        "assetId": "m1",
        "srcInMs": 0,
        "srcOutMs": 3000,
        "atMs": 0,
        "durMs": 3000,
        "gainDb": -6,
        "fadeInMs": 0,
        "fadeOutMs": 0,
        "loop": False,
    }
    base.update(over)
    return base


def with_overlays(doc: Doc, *items: dict[str, Any], lane: bool = True) -> Doc:
    doc["output"]["lanes"]["overlays"] = lane
    doc["timeline"]["overlays"] = list(items)
    return doc


def with_voice(
    doc: Doc, *clips: dict[str, Any], lane: bool = True, chosen: bool = True, **track: Any
) -> Doc:
    doc["output"]["lanes"]["voice"] = lane
    if chosen:
        doc["output"]["voiceTrackId"] = "vt_one"
    doc["timeline"]["voice"]["tracks"]["vt_one"] = {
        "captionTrackId": "ct_main",
        "clips": list(clips),
        **track,
    }
    return doc


def with_music(doc: Doc, *clips: dict[str, Any], lane: bool = True, **box: Any) -> Doc:
    doc["output"]["lanes"]["music"] = lane
    doc["timeline"]["music"] = {"clips": list(clips), "duck": {"enabled": True}, **box}
    return doc


def refuses_doc(doc: Doc, *needles: str) -> None:
    with pytest.raises(ValueError) as caught:
        parse_document(doc)
    for needle in needles:
        assert needle in str(caught.value)


GLOW = {"color": "#FFFFFF", "blurPct": 10, "spreadPct": 2, "strength": 2}


@pytest.mark.parametrize(
    ("over", "needle"),
    [
        ({"sizePct": 0}, "timeline.overlays[0].sizePct"),
        ({"sizePct": 51}, "timeline.overlays[0].sizePct"),
        ({"color": "red"}, "timeline.overlays[0].color"),
        ({"bg": "neon"}, "timeline.overlays[0].bg"),
        ({"anim": "spin"}, "timeline.overlays[0].anim"),
        ({"font": "Lora"}, "timeline.overlays[0].font"),
        ({"outline": {"color": "#000000", "widthPct": 26}}, "overlays[0].outline.widthPct"),
        ({"shadow": {"color": "#000000", "angle": 181}}, "overlays[0].shadow.angle"),
        ({"glow": {**GLOW, "strength": 4}}, "overlays[0].glow.strength"),
        ({"glow": {**GLOW, "blurPct": 0.5}}, "overlays[0].glow.blurPct"),
        ({"lines": [{"sizeScale": 1}] * 5}, "timeline.overlays[0].lines"),
        ({"lines": [{"sizeScale": 3}]}, "overlays[0].lines[0].sizeScale"),
        ({"letterSpacingPct": -21}, "timeline.overlays[0].letterSpacingPct"),
        ({"rotate": 181}, "timeline.overlays[0].rotate"),
        ({"opacity": 1.5}, "timeline.overlays[0].opacity"),
        ({"x": 1.2}, "timeline.overlays[0].x"),
        ({"text": {"vietnamese": "x"}}, "timeline.overlays[0].text.vietnamese"),
        ({"startMs": -1}, "timeline.overlays[0].startMs"),
        ({"kind": "banner"}, "timeline.overlays[0].kind"),
    ],
)
def test_text_overlay_leaves_are_refused_even_with_the_lane_off(
    over: dict[str, Any], needle: str
) -> None:
    refuses_doc(with_overlays(project(), text_overlay(**over), lane=False), needle)


@pytest.mark.parametrize(
    ("over", "needle"),
    [
        ({"widthPct": 1}, "timeline.overlays[0].widthPct"),
        ({"anim": "slide_up"}, "timeline.overlays[0].anim"),
        ({"opacity": -0.1}, "timeline.overlays[0].opacity"),
        ({"assetId": ""}, "timeline.overlays[0].assetId"),
    ],
)
def test_image_overlay_leaves_are_refused_even_with_the_lane_off(
    over: dict[str, Any], needle: str
) -> None:
    refuses_doc(with_overlays(project(), image_overlay(**over), lane=False), needle)


def test_overlay_row_hidden_must_be_true_and_overlay_lang_must_be_a_tag() -> None:
    doc = project()
    doc["timeline"]["overlayRows"] = {"text": [{"id": "rw1", "hidden": False}], "image": []}
    refuses_doc(doc, "timeline.overlayRows.text[0].hidden")
    refuses(lambda d: d["output"].update(overlayLang="Vietnamese"), "output.overlayLang")


@pytest.mark.parametrize(
    ("over", "needle"),
    [
        ({"rate": 3}, "timeline.voice.tracks.vt_one.clips[0].rate"),
        ({"fitPolicy": "cut"}, "clips[0].fitPolicy"),
        ({"gainDb": 13}, "clips[0].gainDb"),
        ({"fadeInMs": -1}, "clips[0].fadeInMs"),
        ({"durMs": 0}, "clips[0].durMs"),
        ({"cueId": 4}, "clips[0].cueId"),
    ],
)
def test_voice_clip_leaves_are_refused_on_a_track_that_is_not_chosen(
    over: dict[str, Any], needle: str
) -> None:
    refuses_doc(with_voice(project(), voice_clip(**over), lane=False, chosen=False), needle)


def test_voice_track_caption_link_is_checked() -> None:
    refuses_doc(
        with_voice(project(), lane=False, chosen=False, captionTrackId=5),
        "timeline.voice.tracks.vt_one.captionTrackId",
    )


@pytest.mark.parametrize(
    ("over", "needle"),
    [
        ({"gainDb": 20}, "timeline.music.clips[0].gainDb"),
        ({"loop": "yes"}, "timeline.music.clips[0].loop"),
        ({"fadeOutMs": -5}, "timeline.music.clips[0].fadeOutMs"),
    ],
)
def test_music_clip_leaves_are_refused_with_the_lane_off(
    over: dict[str, Any], needle: str
) -> None:
    refuses_doc(with_music(project(), music_clip(**over), lane=False), needle)


def test_music_duck_flag_must_be_boolean() -> None:
    refuses_doc(
        with_music(project(), lane=False, duck={"enabled": 1}), "timeline.music.duck.enabled"
    )


@pytest.mark.parametrize(
    ("mutate", "needle"),
    [
        (lambda d: d["assets"]["a1"].update(role="narrator"), "assets.a1.role"),
        (
            lambda d: d["assets"]["a2"].update(
                animation={"frameCount": 601, "cols": 1, "frameW": 8, "frameH": 8, "frameMs": 40}
            ),
            "assets.a2.animation.frameCount",
        ),
        (
            lambda d: d["assets"]["a2"].update(
                animation={"frameCount": 4, "cols": 0, "frameW": 8, "frameH": 8, "frameMs": 40}
            ),
            "assets.a2.animation.cols",
        ),
        (
            lambda d: d["assets"]["a2"].update(animation={"frameCount": 4}),
            "assets.a2.animation.cols",
        ),
        (
            lambda d: d["assets"]["a1"].update(probe={"durationMs": -1}),
            "assets.a1.probe.durationMs",
        ),
        (lambda d: d["assets"]["a1"].update(probe={"hasAudio": "no"}), "assets.a1.probe.hasAudio"),
        (lambda d: d["assets"]["a1"].update(probe={"sha256": "xyz"}), "assets.a1.probe.sha256"),
        (lambda d: d["assets"]["a1"].update(probe={"width": 0}), "assets.a1.probe.width"),
        (lambda d: d["assets"]["a1"].update(probe={"fps": 0}), "assets.a1.probe.fps"),
        (lambda d: d["assets"]["a1"].update(probe={"pixFmt": 3}), "assets.a1.probe.pixFmt"),
    ],
)
def test_asset_role_animation_and_probe_leaves_are_refused(
    mutate: Callable[[Doc], None], needle: str
) -> None:
    refuses(mutate, needle)


@pytest.mark.parametrize(
    ("mutate", "needle"),
    [
        (
            lambda d: d["timeline"]["captions"]["style"].update(fontFamily="Lora"),
            "style.fontFamily",
        ),
        (lambda d: d["timeline"]["captions"]["style"].update(preset="neon"), "style.preset"),
        (
            lambda d: d["timeline"]["captions"]["styles"]["loud"].update(shadow={"color": "red"}),
            "styles.loud.shadow.color",
        ),
        (lambda d: d["timeline"]["captions"]["style"].update(shadow="black"), "style.shadow"),
    ],
)
def test_caption_style_font_preset_and_shadow_are_refused(
    mutate: Callable[[Doc], None], needle: str
) -> None:
    refuses(mutate, needle)


def test_caption_style_accepts_a_listed_font_a_preset_and_a_shadow() -> None:
    doc = project()
    doc["timeline"]["captions"]["style"].update(
        fontFamily="Montserrat", preset="tiktok_bold", shadow={"color": "#112233"}
    )
    doc["timeline"]["captions"]["styles"]["loud"].update(shadow=None)
    parse_document(doc)


def test_leaves_are_read_with_the_lane_on() -> None:
    doc = project()
    with_overlays(
        doc,
        text_overlay(font="Anton", glow=GLOW, lines=[{"sizeScale": 1.5, "color": "#FF0000"}]),
        image_overlay(),
    )
    with_voice(doc, voice_clip())
    with_music(doc, music_clip())
    parsed = parse_document(doc)
    assert [o.id for o in parsed.overlays] == ["ov1", "ov2"]
    first = parsed.overlays[0]
    assert isinstance(first, TextOverlay)
    assert first.style["font"] == "Anton"
    assert parsed.overlay_lang == "vi"
    assert [c.id for c in parsed.voice_clips] == ["vc1"]
    assert [c.id for c in parsed.music_clips] == ["mu1"]
    assert parsed.duck_enabled is True
    assert parsed.voice_track_id == "vt_one"
    assert parsed.lanes == {"captions": True, "voice": True, "music": True, "overlays": True}


def test_a_lane_that_is_off_keeps_none_of_its_content() -> None:
    doc = project()
    with_overlays(doc, text_overlay(), image_overlay(), lane=False)
    with_voice(doc, voice_clip(), lane=False)
    with_music(doc, music_clip(), lane=False)
    parsed = parse_document(doc)
    assert (parsed.overlays, parsed.voice_clips, parsed.music_clips) == ((), (), ())


def test_rows_drop_hidden_overlays_and_muted_clips() -> None:
    doc = project()
    with_overlays(doc, text_overlay(row="rw1"), image_overlay(row="rw1"))
    doc["timeline"]["overlayRows"] = {
        "text": [{"id": "rw1"}],
        "image": [{"id": "rw1", "hidden": True}],
    }
    with_voice(
        doc,
        voice_clip(row="rw1"),
        voice_clip(id="vc2", row="rw2"),
        rows=[{"id": "rw1", "muted": True}, {"id": "rw2"}],
    )
    with_music(
        doc,
        music_clip(row="rw9"),
        music_clip(id="mu2", row="rw8"),
        rows=[{"id": "rw9", "muted": True}, {"id": "rw8"}],
    )
    parsed = parse_document(doc)
    assert [o.id for o in parsed.overlays] == ["ov1"]
    assert parsed.overlays[0].row_index == 0
    assert [(c.id, c.index) for c in parsed.voice_clips] == [("vc2", 1)]
    assert [c.id for c in parsed.music_clips] == ["mu2"]


def test_a_kept_item_needs_its_required_leaves() -> None:
    doc = with_voice(project(), {k: v for k, v in voice_clip().items() if k != "rate"})
    refuses_doc(doc, "timeline.voice.tracks.vt_one.clips[0].rate", "required")
    doc = with_music(project(), {k: v for k, v in music_clip().items() if k != "loop"})
    refuses_doc(doc, "timeline.music.clips[0].loop", "required")
    doc = with_overlays(project(), {k: v for k, v in text_overlay().items() if k != "x"})
    refuses_doc(doc, "timeline.overlays[0].x", "required")


def test_a_kept_item_must_point_at_a_listed_asset() -> None:
    refuses_doc(
        with_music(project(), music_clip(assetId="ghost")),
        "timeline.music.clips[0].assetId",
        "ghost",
    )
    refuses_doc(
        with_voice(project(), voice_clip(assetId="ghost")),
        "timeline.voice.tracks.vt_one.clips[0].assetId",
    )
    refuses_doc(
        with_overlays(project(), image_overlay(assetId="ghost")),
        "timeline.overlays[0].assetId",
    )
    parse_document(with_music(project(), music_clip(assetId="ghost"), lane=False))


def test_plan_job_reads_only_the_project() -> None:
    doc = parse_document(project())
    spec = parse_job({"profile": "draft", "mode": "plan"}, ["project"], doc)
    assert (spec.mode, spec.asset_ids) == ("plan", ())
    with pytest.raises(ValueError, match="plan job reads only the project"):
        parse_job({"profile": "draft", "mode": "plan"}, ["project", "asset:a1"], doc)
    with pytest.raises(ValueError, match="missing input 'project'"):
        parse_job({"profile": "draft", "mode": "plan"}, [], doc)


def test_render_inputs_follow_scenes_overlays_voice_and_music() -> None:
    doc = project()
    with_overlays(doc, image_overlay(assetId="a1"))
    with_voice(doc, voice_clip())
    with_music(doc, music_clip())
    spec = parse_job(
        good_params(),
        ["project", "asset:a1", "asset:a2", "asset:v1", "asset:m1"],
        parse_document(doc),
    )
    assert spec.asset_ids == ("a1", "a2", "v1", "m1")
    assert spec.mode == "render"


def test_music_clip_without_its_asset_input_is_refused() -> None:
    doc = with_music(project(), music_clip())
    with pytest.raises(ValueError, match="missing input 'asset:m1'"):
        parse_job(good_params(), KEYS, parse_document(doc))


def test_overlay_asset_input_is_extra_when_the_overlay_lane_is_off() -> None:
    doc = with_overlays(project(), image_overlay(assetId="ghost"), lane=False)
    doc["assets"]["ghost"] = {"kind": "image"}
    with pytest.raises(ValueError, match="extra input 'asset:ghost'"):
        parse_job(good_params(), [*KEYS, "asset:ghost"], parse_document(doc))
    assert parse_job(good_params(), KEYS, parse_document(doc)).asset_ids == ("a1", "a2")


def test_a_voice_clip_that_trim_tail_will_drop_still_needs_its_asset() -> None:
    doc = with_voice(
        project(), voice_clip(cueId="cu1", atMs=5000, fitPolicy="trim_tail", durMs=3000)
    )
    with pytest.raises(ValueError, match="missing input 'asset:v1'"):
        parse_job(good_params(), KEYS, parse_document(doc))
    spec = parse_job(good_params(), [*KEYS, "asset:v1"], parse_document(doc))
    assert spec.asset_ids == ("a1", "a2", "v1")


def test_an_image_overlay_shorter_than_a_frame_still_needs_its_asset() -> None:
    doc = with_overlays(project(), image_overlay(assetId="a3", startMs=1000, endMs=1010))
    doc["assets"]["a3"] = {"kind": "image"}
    with pytest.raises(ValueError, match="missing input 'asset:a3'"):
        parse_job(good_params(), KEYS, parse_document(doc))
    spec = parse_job(good_params(), [*KEYS, "asset:a3"], parse_document(doc))
    assert spec.asset_ids == ("a1", "a2", "a3")
