"""Plan core, plan hash and the honoured-leaves manifest."""

from __future__ import annotations

import copy
import hashlib
import json
from collections.abc import Callable, Iterator
from typing import Any

import pytest
from test_render_document import project

from flowcortex_worker.captions.fonts import ALLOWED_FONTS
from flowcortex_worker.render.document import Document, parse_document
from flowcortex_worker.render.plan import (
    HONOURS,
    RENDERER,
    honours,
    manifest_hash,
    plan_core,
    plan_document,
    plan_hash,
)
from flowcortex_worker.render.timeline import TimelinePlan, declared_probes, plan_timeline

Doc = dict[str, Any]

STYLE: dict[str, Any] = {
    "animation": "pop",
    "backdrop": "custom",
    "backdropColor": "#112233",
    "bold": True,
    "boxWidth": 0.8,
    "fontFamily": ALLOWED_FONTS[0],
    "fontSize": "large",
    "highlightColor": "#FFD400",
    "highlightMode": "jump",
    "italic": True,
    "marginPct": 10,
    "maxSyllablesPerLine": 5,
    "outlineColor": "#000000",
    "outlineWidth": "thin",
    "position": "middle",
    "preset": "lecture",
    "scale": 1.2,
    "shadow": {"color": "#222222"},
    "textColor": "#FFFFFF",
    "uppercase": True,
    "x": 0.5,
    "y": 0.8,
}

A1_PROBE: dict[str, Any] = {
    "durationMs": 5000,
    "width": 1920,
    "height": 1080,
    "fps": 30,
    "hasAudio": True,
    "rotation": 0,
    "pixFmt": "yuv420p",
    "colorTransfer": "bt709",
    "sha256": "a" * 64,
    "bytes": 123456,
}


def full_project() -> Doc:
    """One document that carries every honoured leaf, every lane on."""
    raw = project()
    raw["assets"] = {
        "a1": {"kind": "video", "probe": copy.deepcopy(A1_PROBE)},
        "a2": {
            "kind": "image",
            "probe": {"width": 512, "height": 512, "bytes": 2048},
            "animation": {"frameCount": 8, "cols": 4, "frameW": 128, "frameH": 128, "frameMs": 80},
        },
        "v1": {"kind": "audio", "role": "dub"},
        "m1": {"kind": "audio", "role": "music"},
    }
    raw["output"] = {
        "captionTrackId": "ct_main",
        "voiceTrackId": "vt_one",
        "overlayLang": "vi",
        "lanes": {"captions": True, "voice": True, "music": True, "overlays": True},
    }
    timeline = raw["timeline"]
    timeline["scenes"] = [
        {
            "id": "sc0",
            "clipId": "a1",
            "srcInMs": 0,
            "srcOutMs": 3000,
            "atMs": 0,
            "durMs": 3000,
            "fit": "cover",
            "fill": "#000000",
            "audio": {"keep": True, "gainDb": 0},
            "motion": None,
            "transitionOut": {"kind": "fade", "durMs": 400, "mode": "overlap"},
            "offsetX": 0.1,
            "offsetY": -0.1,
            "zoom": 1.2,
        },
        {
            "id": "sc1",
            "clipId": "a2",
            "srcInMs": 0,
            "srcOutMs": 3000,
            "atMs": 2600,
            "durMs": 3000,
            "fit": "contain",
            "fill": "#101010",
            "audio": {"keep": False, "gainDb": -3},
            "motion": {"kind": "zoom_in", "from": 1, "to": 1.2},
            "transitionOut": None,
        },
    ]
    timeline["captions"] = {
        "style": copy.deepcopy(STYLE),
        "styles": {"loud": copy.deepcopy(STYLE)},
        "tracks": {
            "ct_main": {
                "lang": "vi",
                "style": copy.deepcopy(STYLE),
                "rows": [{"id": "rw1"}, {"id": "rw2", "hidden": True}],
                "cues": [
                    {
                        "id": "cu1",
                        "startMs": 0,
                        "endMs": 1000,
                        "text": "Xin chào",
                        "styleId": "loud",
                        "row": "rw1",
                        "words": [
                            {"t": "Xin", "startMs": 0, "endMs": 400},
                            {"t": "chào", "startMs": 400, "endMs": 900},
                        ],
                    }
                ],
            }
        },
    }
    timeline["overlayRows"] = {
        "text": [{"id": "rt1"}, {"id": "rt2", "hidden": True}],
        "image": [{"id": "ri1"}, {"id": "ri2", "hidden": True}],
    }
    timeline["overlays"] = [
        {
            "id": "ov1",
            "text": {"vi": "Xin chào", "en": "Hello"},
            "x": 0.5,
            "y": 0.5,
            "sizePct": 5,
            "color": "#FFFFFF",
            "bg": "custom",
            "anim": "fade",
            "startMs": 0,
            "endMs": 2000,
            "row": "rt1",
            "opacity": 0.9,
            "font": ALLOWED_FONTS[0],
            "bold": True,
            "italic": True,
            "underline": True,
            "align": "center",
            "anchor": "middle",
            "outline": {"color": "#000000", "widthPct": 2},
            "bgColor": "#202020",
            "shadow": {
                "color": "#000000",
                "distancePct": 2,
                "angle": 45,
                "blurPct": 3,
                "opacity": 0.5,
            },
            "glow": {"color": "#FFFFFF", "blurPct": 10, "spreadPct": 2, "strength": 2},
            "accent": {"color": "#FF0000", "outlineColor": "#00FF00"},
            "lines": [{"font": ALLOWED_FONTS[0], "sizeScale": 1.5, "color": "#FFAA00"}],
            "rotate": 5,
            "letterSpacingPct": 3,
        },
        {
            "id": "ov2",
            "kind": "image",
            "assetId": "a2",
            "x": 0.5,
            "y": 0.5,
            "widthPct": 20,
            "opacity": 1,
            "anim": "fade",
            "startMs": 0,
            "endMs": 2000,
            "row": "ri1",
        },
    ]
    timeline["voice"] = {
        "tracks": {
            "vt_one": {
                "captionTrackId": "ct_main",
                "rows": [{"id": "vr1"}, {"id": "vr2", "muted": True}],
                "clips": [
                    {
                        "id": "vc1",
                        "cueId": "cu1",
                        "assetId": "v1",
                        "srcInMs": 0,
                        "srcOutMs": 3000,
                        "atMs": 0,
                        "durMs": 3000,
                        "gainDb": 0,
                        "fadeInMs": 10,
                        "fadeOutMs": 10,
                        "fitPolicy": "ripple",
                        "rate": 1,
                        "row": "vr1",
                    }
                ],
            }
        }
    }
    timeline["music"] = {
        "rows": [{"id": "mr1"}, {"id": "mr2", "muted": True}],
        "duck": {"enabled": True},
        "clips": [
            {
                "id": "mu1",
                "assetId": "m1",
                "srcInMs": 0,
                "srcOutMs": 3000,
                "atMs": 0,
                "durMs": 3000,
                "gainDb": -6,
                "fadeInMs": 0,
                "fadeOutMs": 0,
                "loop": True,
                "row": "mr1",
            }
        ],
    }
    return raw


IMAGE_ONLY_LEAVES = ("kind", "assetId", "widthPct")
SECOND_ROW_LEAVES = ("hidden", "muted")


def segments(path: str) -> list[str | int]:
    """The concrete route of an honoured path through `full_project()`."""
    parts = path.removeprefix("$.").split(".")
    leaf = parts[-1].removesuffix("[]")
    image_overlay = parts[:2] == ["timeline", "overlays[]"] and parts[2] in IMAGE_ONLY_LEAVES
    route: list[str | int] = []
    for i, part in enumerate(parts):
        name = part.removesuffix("{}").removesuffix("[]")
        route.append(name)
        if part.endswith("{}"):
            if name == "assets":
                route.append("a2" if parts[i + 1] == "animation" else "a1")
            elif name == "tracks":
                route.append("ct_main" if parts[i - 1] == "captions" else "vt_one")
            elif name == "styles":
                route.append("loud")
            else:
                route.append("vi")
        elif part.endswith("[]"):
            second = i == len(parts) - 2 and leaf in SECOND_ROW_LEAVES
            image_scene = name == "scenes" and parts[2] == "motion"
            route.append(
                1 if second or image_scene or (image_overlay and name == "overlays") else 0
            )
    return route


def dotted(route: list[str | int]) -> str:
    out = ""
    for part in route:
        out += f"[{part}]" if isinstance(part, int) else (f".{part}" if out else part)
    return out


def test_manifest_is_the_closed_sorted_list_of_189_paths() -> None:
    assert len(HONOURS) == 189
    assert list(HONOURS) == sorted(set(HONOURS))
    assert honours() == list(HONOURS)
    assert honours() is not honours()


def test_manifest_hash_is_the_sha256_of_the_sorted_paths() -> None:
    expected = hashlib.sha256("\n".join(sorted(HONOURS)).encode("utf-8")).hexdigest()
    assert manifest_hash() == f"sha256:{expected}"


def test_the_fixture_with_every_honoured_leaf_parses() -> None:
    document = parse_document(full_project())
    assert document.voice_clips and document.music_clips and len(document.overlays) == 2


@pytest.mark.parametrize("path", HONOURS)
def test_every_honoured_leaf_is_checked(path: str) -> None:
    raw = full_project()
    route = segments(path)
    box: Any = raw
    for part in route[:-1]:
        box = box[part]
    assert route[-1] in box, f"the fixture does not carry {path}"
    box[route[-1]] = []
    with pytest.raises(ValueError) as caught:
        parse_document(raw)
    assert str(caught.value).startswith(dotted(route))


def planned(
    raw: Doc,
    *,
    probes: dict[str, dict[str, Any]] | None = None,
    measured: bool = True,
    profile: str = "final",
) -> tuple[TimelinePlan, Document]:
    document = parse_document(raw)
    used = declared_probes(document) if probes is None else probes
    return plan_timeline(document, used, profile, measured=measured), document


def hash_of(raw: Doc, **kwargs: Any) -> str:
    result, document = planned(raw, **kwargs)
    return plan_hash(plan_core(result, document))


def measured_probes(**over: Any) -> dict[str, dict[str, Any]]:
    return {
        "a1": {**{k: v for k, v in A1_PROBE.items() if k not in ("sha256", "bytes")}, **over},
        "a2": {"width": 512, "height": 512},
    }


def test_declared_and_measured_durations_hash_alike() -> None:
    raw = full_project()
    declared = hash_of(raw, measured=False)
    assert hash_of(raw, probes=measured_probes(durationMs=5002)) == declared


def test_a_source_clamp_does_not_move_the_hash() -> None:
    raw = full_project()
    short = measured_probes(durationMs=2990)
    clamped, _ = planned(raw, probes=short)
    assert clamped.scenes[0].clamp is not None
    assert hash_of(raw, probes=short) == hash_of(raw, measured=False)


@pytest.mark.parametrize(
    "over",
    [
        {"hasAudio": False},
        {"fps": 25},
        {"rotation": 90},
        {"width": 1280, "height": 720},
        {"colorTransfer": "smpte2084"},
        {"pixFmt": "yuv422p10le"},
    ],
    ids=lambda over: next(iter(over)),
)
def test_measured_probe_values_do_not_move_the_hash(over: dict[str, Any]) -> None:
    raw = full_project()
    assert hash_of(raw, probes=measured_probes(**over)) == hash_of(raw, measured=False)


def mutate(change: Callable[[Doc], None]) -> Doc:
    raw = full_project()
    change(raw)
    return raw


@pytest.mark.parametrize(
    "change",
    [
        lambda raw: raw["timeline"]["overlays"][0].update(color="#FF0000"),
        lambda raw: raw["output"]["lanes"].update(music=False),
        lambda raw: raw["timeline"]["voice"]["tracks"]["vt_one"]["clips"][0].update(gainDb=-4),
        lambda raw: raw["assets"]["a1"]["probe"].update(sha256="b" * 64),
        lambda raw: raw["timeline"]["captions"]["tracks"]["ct_main"]["style"].update(bold=False),
    ],
    ids=["overlay color", "music lane", "voice gain", "asset content hash", "caption style"],
)
def test_a_rendered_leaf_moves_the_hash(change: Callable[[Doc], None]) -> None:
    assert hash_of(mutate(change), measured=False) != hash_of(full_project(), measured=False)


def test_the_profile_moves_the_hash() -> None:
    raw = full_project()
    assert hash_of(raw, measured=False, profile="draft") != hash_of(
        raw, measured=False, profile="final"
    )


def walk(value: Any) -> Iterator[Any]:
    if isinstance(value, dict):
        for item in value.values():
            yield from walk(item)
    elif isinstance(value, list):
        for item in value:
            yield from walk(item)
    else:
        yield value


def test_core_holds_no_probe_value_and_no_clamp() -> None:
    raw = full_project()
    probe = {
        "durationMs": 2990,
        "width": 1357,
        "height": 2468,
        "fps": 29.97,
        "hasAudio": True,
        "rotation": 270,
        "pixFmt": "yuv422p10le",
        "colorTransfer": "arib-std-b67",
    }
    result, document = planned(raw, probes={"a1": probe, "a2": {"width": 512, "height": 512}})
    clamp = result.scenes[0].clamp
    assert clamp is not None
    core = plan_core(result, document)
    leaves = [leaf for leaf in walk(core) if not isinstance(leaf, bool)]
    sentinels = [*(v for v in probe.values() if not isinstance(v, bool)), clamp.dur_ms]
    assert not [leaf for leaf in leaves if leaf in sentinels]
    assert all("probe" not in scene and "clamp" not in scene for scene in core["scenes"])


def test_core_is_json_stable_and_names_its_renderer() -> None:
    result, document = planned(full_project(), measured=False)
    core = plan_core(result, document)
    assert core["renderer"] == RENDERER == "flowcortex.video.render/2"
    assert json.loads(json.dumps(core)) == core
    assert plan_hash(core) == plan_hash(copy.deepcopy(core))


def test_asset_identity_is_the_declared_hash_else_id_duration_and_size() -> None:
    result, document = planned(full_project(), measured=False)
    assets = plan_core(result, document)["assets"]
    assert assets["a1"] == "a" * 64
    assert assets["a2"] == "a2:None:2048"
    assert set(assets) == {"a1", "a2", "v1", "m1"}


def test_chunks_carry_how_a_split_chain_is_cut() -> None:
    document = parse_document(full_project())
    split = plan_timeline(
        document, declared_probes(document), "final", max_chunk_scenes=1, measured=False
    )
    first, second = plan_core(split, document)["chunks"]
    assert (first["leadScene"], first["cutAfter"]) == (None, True)
    assert (second["leadScene"], second["cutAfter"]) == (0, False)
    assert second["startMs"] == first["startMs"] + first["durMs"]


def test_plan_document_carries_the_hash_and_no_measured_values() -> None:
    result, document = planned(full_project(), measured=False)
    body = plan_document(result, document)
    core = plan_core(result, document)
    assert body["planHash"] == plan_hash(core)
    assert body["durationMs"] == 5600
    assert body["encodeCount"] == len(result.chunks)
    assert {k: v for k, v in body.items() if k in core} == core
    assert set(body) - set(core) == {"durationMs", "encodeCount", "planHash"}
    assert "sceneProbes" not in body and "sceneClamp" not in body


def test_overlay_without_text_for_the_language_still_plans_and_hashes() -> None:
    raw = mutate(lambda r: r["timeline"]["overlays"][0].update(text={"en": "Hello"}))
    result, document = planned(raw, measured=False)
    assert [o.id for o in result.overlays] == ["ov2"]
    assert any("vi" in w for w in result.warnings)
    assert plan_core(result, document)["overlays"][0]["kind"] == "image"
