"""The project document and job contract: every refusal names the path it came from."""

from __future__ import annotations

import copy
from collections.abc import Callable
from typing import Any

import pytest

from flowcortex_worker.render.document import (
    TRANSITION_KINDS,
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
        },
        "output": {
            "captionTrackId": "ct_main",
            "voiceTrackId": None,
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


def test_music_lane_on_with_a_clip_is_refused() -> None:
    def music_on(doc: Doc) -> None:
        doc["output"]["lanes"]["music"] = True
        doc["timeline"]["music"]["clips"] = [{"id": "mu1", "assetId": "aud"}]

    refuses(music_on, "output.lanes.music", "not support")


def test_voice_lane_on_with_a_clip_is_refused() -> None:
    def voice_on(doc: Doc) -> None:
        doc["output"]["lanes"]["voice"] = True
        doc["output"]["voiceTrackId"] = "vt_one"
        doc["timeline"]["voice"]["tracks"]["vt_one"] = {"clips": [{"id": "vc1"}]}

    refuses(voice_on, "output.lanes.voice")


def test_unknown_voice_track_is_refused() -> None:
    refuses(lambda d: d["output"].update(voiceTrackId="vt_gone"), "output.voiceTrackId")


def test_overlays_off_with_content_is_accepted() -> None:
    doc = project()
    doc["timeline"]["overlays"] = [{"id": "ov1"}, {"id": "ov2", "kind": "image"}]
    parse_document(doc)


def test_overlays_on_with_content_is_refused() -> None:
    doc = project()
    doc["output"]["lanes"]["overlays"] = True
    doc["timeline"]["overlays"] = [{"id": "ov1"}]
    with pytest.raises(ValueError, match=r"output\.lanes\.overlays"):
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


def test_voice_row_that_is_not_muted_still_refuses() -> None:
    doc = project()
    doc["output"]["lanes"]["voice"] = True
    doc["output"]["voiceTrackId"] = "vt_one"
    doc["timeline"]["voice"]["tracks"]["vt_one"] = {
        "rows": [{"id": "rw1", "muted": True}, {"id": "rw2"}],
        "clips": [{"id": "vc1", "row": "rw1"}, {"id": "vc2", "row": "rw2"}],
    }
    with pytest.raises(ValueError, match=r"output\.lanes\.voice"):
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
        ({"profile": "draft", "mode": "plan"}, "plan mode is not supported"),
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
