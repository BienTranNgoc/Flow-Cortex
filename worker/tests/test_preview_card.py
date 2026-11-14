"""Staging card rules: determinism, vocabulary, cast, stage, camera and dialogue.

The fixtures under tests/fixtures/preview are byte copies of the reference project's, with sha256:
  minh-thu.json                 058b71772cc2ef9b3fe48d958104a61ed77e152c94b6e4acaf2cfc72d2a46e19
  son-tinh.json                 de0ea007df807027987ff176e35c7490fc94b5e886bf8ace9440d00f4f64672d
  demo-hanh-trinh.json          b411db70e8126ffc6a64753e2ff3ee7bf80ab188a530293635eabb2a69fddc49
  d8-hien-nha.json              92dd0d409b49f0b7a182251a736ec0d038d8afd6e962ebfa0949edf014121bec
  lan-minh-trao-qua.json        1d53708ed32d7e62517215b602a5b7a470aa600a9ddb559de121cde306145c65
  minh-thu-1.card.json          867300fc3cf206fbf6f17b6300d8fc1de7ed252cc39476a6d9e2b59f358848e7
  minh-thu-1.gui-di.card.json   c456658917525bfbe46fd15c7a20bb8c30f3ff899db658f8043d1e116fcd9f44
"""

from __future__ import annotations

import copy
import hashlib
import json
import math
from pathlib import Path
from typing import Any

import pytest

from flowcortex_worker.preview import build_card, dumps
from flowcortex_worker.preview.card import split_dialogue

FIXTURES = Path(__file__).parent / "fixtures" / "preview"
GLOSSARY = json.loads((FIXTURES / "xood-card-glossary.json").read_text(encoding="utf-8"))
SHA256 = {
    "minh-thu.json": "058b71772cc2ef9b3fe48d958104a61ed77e152c94b6e4acaf2cfc72d2a46e19",
    "son-tinh.json": "de0ea007df807027987ff176e35c7490fc94b5e886bf8ace9440d00f4f64672d",
    "demo-hanh-trinh.json": "b411db70e8126ffc6a64753e2ff3ee7bf80ab188a530293635eabb2a69fddc49",
    "d8-hien-nha.json": "92dd0d409b49f0b7a182251a736ec0d038d8afd6e962ebfa0949edf014121bec",
    "lan-minh-trao-qua.json": "1d53708ed32d7e62517215b602a5b7a470aa600a9ddb559de121cde306145c65",
    "minh-thu-1.card.json": "867300fc3cf206fbf6f17b6300d8fc1de7ed252cc39476a6d9e2b59f358848e7",
    "minh-thu-1.gui-di.card.json": (
        "c456658917525bfbe46fd15c7a20bb8c30f3ff899db658f8043d1e116fcd9f44"
    ),
}


def minh_thu() -> dict[str, Any]:
    doc: dict[str, Any] = json.loads((FIXTURES / "minh-thu.json").read_text(encoding="utf-8"))
    return doc


def to_english(node: Any, parent: str | None = None) -> Any:
    """A Xood card through the glossary; the corner label and restructured fields are left out."""
    if isinstance(node, list):
        return [to_english(item, parent) for item in node]
    if not isinstance(node, dict):
        return node
    out = {}
    for key, value in node.items():
        if key in GLOSSARY["dropped"] or key in GLOSSARY["restructured"]:
            continue
        name = GLOSSARY["keysUnderParent"].get(f"{parent}.{key}") or GLOSSARY["keys"][key]
        if isinstance(value, str) and key in GLOSSARY["values"]:
            value = GLOSSARY["values"][key].get(value, value)
        out[name] = to_english(value, key)
    return out


def without_text(card: dict[str, Any]) -> dict[str, Any]:
    return {k: v for k, v in card.items() if k not in ("subtitles", "warnings")}


def test_fixtures_are_byte_copies() -> None:
    for name, digest in SHA256.items():
        assert hashlib.sha256((FIXTURES / name).read_bytes()).hexdigest() == digest, name


def test_glossary_covers_every_key_of_the_golden_cards() -> None:
    for name in ("minh-thu-1.card.json", "minh-thu-1.gui-di.card.json"):
        to_english(json.loads((FIXTURES / name).read_text(encoding="utf-8")))


def test_golden_card_parity_through_the_glossary() -> None:
    golden = json.loads((FIXTURES / "minh-thu-1.card.json").read_text(encoding="utf-8"))
    card = build_card(minh_thu(), "sh1", "9:16")
    assert without_text(card) == without_text(to_english(golden))
    assert card["subtitles"] == [
        {"speakerId": "ch_minh", "text": "Thư, chào em.", "fromFrame": 0, "toFrame": 120}
    ]
    assert card["warnings"] == []


def test_same_input_same_bytes_and_document_unchanged() -> None:
    doc = minh_thu()
    before = copy.deepcopy(doc)
    first = dumps(build_card(doc, "sh1", "9:16"))
    assert first == dumps(build_card(doc, "sh1", "9:16"))
    assert doc == before


def test_dumps_format() -> None:
    text = dumps(build_card(minh_thu(), "sh1", "9:16"))
    assert text.endswith("}\n") and not text.endswith("\n\n")
    assert '"title": "Minh chào Thư khi gặp lại"' in text
    assert '"foot": [0.6, 0.0]' in text
    assert text.splitlines()[1].startswith(' "kind": "flowcortex.staging-card/1"')
    assert json.loads(text)["kind"] == "flowcortex.staging-card/1"


def test_minh_thu_cast_axis_and_camera() -> None:
    card = build_card(minh_thu(), "sh1", "9:16")
    dummies = {d["id"]: d for d in card["dummies"]}
    assert set(dummies) == {"ch_minh", "ch_thu"}
    assert dummies["ch_minh"]["inFrame"] and not dummies["ch_thu"]["inFrame"]
    assert card["axis"] == {"left": "ch_thu", "right": "ch_minh"}
    assert dummies["ch_thu"]["foot"] == [-0.6, 0.0] and dummies["ch_minh"]["foot"] == [0.6, 0.0]
    assert card["camera"]["placement"]["function"] == "single"
    assert card["camera"]["placement"]["params"]["headingDeg"] == 25.0
    assert card["camera"]["motion"] == {"function": "static", "params": {}}
    assert card["camera"]["minDistanceM"] == 0.6


@pytest.mark.parametrize(
    ("aspect", "size", "sensor"),
    [("9:16", (496, 864), 36.0), ("16:9", (854, 480), 24.0), ("1:1", (640, 640), 24.0)],
)
def test_frame_table(aspect: str, size: tuple[int, int], sensor: float) -> None:
    frame = build_card(minh_thu(), "sh1", aspect)["frame"]
    assert (frame["width"], frame["height"], frame["sensorMm"], frame["fps"]) == (*size, sensor, 24)


@pytest.mark.parametrize(
    ("ms", "frames"), [(5000, 120), (500, 12), (1, 1), (20, 1), (62, 1), (63, 2), (4000, 96)]
)
def test_frame_count_rounds_and_never_drops_below_one(ms: int, frames: int) -> None:
    doc = minh_thu()
    doc["story"]["shots"][0]["durationTargetMs"] = ms
    assert build_card(doc, "sh1", "16:9")["frame"]["frameCount"] == frames


def refused(mutate: Any, aspect: str = "9:16") -> str:
    doc = minh_thu()
    mutate(doc)
    with pytest.raises(ValueError) as caught:
        build_card(doc, "sh1", aspect)
    return str(caught.value)


def staging(doc: dict[str, Any]) -> dict[str, Any]:
    node: dict[str, Any] = doc["story"]["shots"][0]["staging"]
    return node


def test_refusals_name_shot_field_and_value() -> None:
    assert "Shot 1" in refused(lambda d: d["story"]["shots"][0].update(staging=None))
    assert "durationTargetMs" in refused(
        lambda d: d["story"]["shots"][0].update(durationTargetMs=0)
    )
    assert "durationTargetMs" in refused(
        lambda d: d["story"]["shots"][0].update(durationTargetMs=True)
    )
    assert "durationTargetMs" in refused(lambda d: d["story"]["shots"][0].pop("durationTargetMs"))
    assert "size is required" in refused(lambda d: staging(d).update(size=None))
    assert "shotType is required" in refused(lambda d: staging(d).update(shotType=None))
    assert "move is required" in refused(lambda d: staging(d).update(move=None))
    assert "No shot 'sh1'" in refused(lambda d: d["story"]["shots"][0].update(id="other"))
    assert "4:5" in refused(lambda d: None, aspect="4:5")


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("shotType", "dutch"),
        ("move", "whip_pan"),
        ("size", "XL"),
        ("angle", "diagonal"),
    ],
)
def test_each_closed_list_refuses_an_unknown_value(field: str, value: str) -> None:
    message = refused(lambda d: staging(d).update({field: value}))
    assert field in message and value in message and "Shot 1" in message


def test_unknown_space_is_refused() -> None:
    def mutate(doc: dict[str, Any]) -> None:
        doc["story"]["sets"][0]["space"] = "attic"

    message = refused(mutate)
    assert "space" in message and "attic" in message


def test_missing_angle_means_eye_and_missing_space_the_empty_ground() -> None:
    doc = minh_thu()
    staging(doc).pop("angle")
    doc["story"]["sets"][0].pop("space")
    card = build_card(doc, "sh1", "9:16")
    assert card["camera"]["placement"]["params"]["angle"] == "eye"
    assert card["shot"]["angle"] is None
    assert card["stage"]["kind"] == "empty_ground"


@pytest.mark.parametrize(
    ("path", "value"),
    [
        (("frame",), "ch_minh"),
        (("actions",), "run"),
        (("frame", 0), "ch_minh"),
        (("extras",), 3),
        (("povId",), ["x"]),
    ],
)
def test_wrong_leaf_type_is_a_value_error_naming_the_field(
    path: tuple[Any, ...], value: Any
) -> None:
    def mutate(doc: dict[str, Any]) -> None:
        node: Any = staging(doc)
        for key in path[:-1]:
            node = node[key]
        node[path[-1]] = value

    message = refused(mutate)
    assert "Shot 1" in message and "staging" in message


def test_wrong_type_in_shot_set_and_document_is_a_value_error() -> None:
    assert "dialogue" in refused(lambda d: d["story"]["shots"][0].update(dialogue="hello"))
    assert "characterIds" in refused(
        lambda d: d["story"]["shots"][0].update(characterIds="ch_minh")
    )
    assert "set.props" in refused(lambda d: d["story"]["sets"][0].update(props=3))
    assert "characters" in refused(lambda d: d.update(characters={}))
    with pytest.raises(ValueError, match="JSON object"):
        build_card([], "sh1", "9:16")
    with pytest.raises(ValueError, match="story"):
        build_card({}, "sh1", "9:16")


def test_pov_without_size_is_accepted_and_records_none() -> None:
    card = build_card(document(stage_for("pov", "static")), "sh_x", "9:16")
    assert card["shot"]["size"] is None


def document(
    stage: dict[str, Any],
    space: str | None = "room",
    cast: tuple[str, ...] = ("ch_lan", "ch_minh", "ch_thu"),
    dialogue: list[dict[str, Any]] | None = None,
    props: bool = True,
    left: str = "ch_minh",
    duration_ms: int = 4000,
) -> dict[str, Any]:
    names = {"ch_lan": "Lan", "ch_minh": "Minh", "ch_thu": "Thư"}
    characters = [
        {"id": m, "kind": "character", "name": n, "clayColor": [180 + 10 * i, 120, 90]}
        for i, (m, n) in enumerate(names.items())
    ]
    characters.append({"id": "ch_pho", "kind": "location", "name": "Phố"})
    the_set = {
        "id": "set_1",
        "space": space,
        "leftCharacterId": left,
        "fixedPieces": ["quầy", "cửa"],
        "props": [{"id": "pr_hop", "name": "hộp quà", "count": 1}] if props else [],
    }
    shot = {
        "id": "sh_x",
        "order": 3,
        "title": "Phân cảnh thử",
        "durationTargetMs": duration_ms,
        "characterIds": list(cast),
        "dialogue": dialogue or [],
        "staging": {"setId": "set_1", "actions": [], **stage},
    }
    return {"characters": characters, "story": {"shots": [shot], "sets": [the_set]}}


FRAME_BY_TYPE: dict[str, list[dict[str, Any]]] = {
    "single": [
        {
            "characterId": "ch_minh",
            "role": "subject",
            "position": "left",
            "gaze": "frame_right",
            "faceAngleDeg": 20,
        }
    ],
    "over_shoulder": [
        {"characterId": "ch_minh", "role": "subject", "gaze": "frame_left", "faceAngleDeg": 20},
        {"characterId": "ch_thu", "role": "over_shoulder"},
    ],
    "two_shot": [
        {"characterId": "ch_minh", "role": "subject"},
        {"characterId": "ch_thu", "role": "subject"},
    ],
    "group": [{"characterId": m, "role": "subject"} for m in ("ch_lan", "ch_minh", "ch_thu")],
    "insert": [],
    "establishing": [],
    "pov": [{"characterId": "ch_minh", "role": "subject", "gaze": "lens"}],
}
PLACEMENT = {
    "single": "single",
    "over_shoulder": "over_shoulder",
    "two_shot": "group",
    "group": "group",
    "insert": "insert",
    "establishing": "establishing",
    "pov": "pov",
}
MOTION = {
    "static": "static",
    "push_in": "dolly",
    "pull_back": "dolly",
    "pan": "pan",
    "tilt": "tilt",
    "handheld": "handheld",
    "track": "follow",
    "truck": "truck",
    "crane": "crane",
    "drone": "drone",
    "orbit": "orbit",
}


def stage_for(shot_type: str, move: str, size: str = "MS") -> dict[str, Any]:
    stage: dict[str, Any] = {
        "shotType": shot_type,
        "move": move,
        "size": None if shot_type == "pov" else size,
        "angle": "eye",
        "frame": copy.deepcopy(FRAME_BY_TYPE[shot_type]),
    }
    if shot_type == "pov":
        stage["povId"] = "ch_lan"
    return stage


@pytest.mark.parametrize("move", sorted(MOTION))
@pytest.mark.parametrize("shot_type", sorted(PLACEMENT))
def test_every_shot_type_and_move_builds_with_the_right_functions(
    shot_type: str, move: str
) -> None:
    card = build_card(document(stage_for(shot_type, move)), "sh_x", "9:16")
    assert card["camera"]["placement"]["function"] == PLACEMENT[shot_type]
    assert card["camera"]["motion"]["function"] == MOTION[move]
    walking = [d for d in card["dummies"] if d["pose"] == "walking"]
    expected = 1 if move == "track" and shot_type not in ("insert", "establishing") else 0
    assert len(walking) == expected


def test_single_shot_needs_exactly_one_subject() -> None:
    both = stage_for("single", "static")
    both["frame"] = [
        {"characterId": "ch_minh", "role": "subject"},
        {"characterId": "ch_thu", "role": "subject"},
    ]
    message = refusal(document(both))
    assert "Shot 3" in message and "exactly one framed subject" in message


def refusal(doc: dict[str, Any]) -> str:
    with pytest.raises(ValueError) as caught:
        build_card(doc, "sh_x", "9:16")
    return str(caught.value)


def test_over_shoulder_needs_subject_and_over_shoulder_character() -> None:
    lone = stage_for("over_shoulder", "static")
    lone["frame"] = lone["frame"][:1]
    assert "Shot 3" in refusal(document(lone)) and "over_shoulder" in refusal(document(lone))


@pytest.mark.parametrize("shot_type", ["two_shot", "group"])
def test_two_shot_and_group_need_someone_framed(shot_type: str) -> None:
    empty = stage_for(shot_type, "static")
    empty["frame"] = []
    assert "Shot 3" in refusal(document(empty))


def test_framed_character_outside_the_shot_is_refused() -> None:
    doc = document(stage_for("single", "static"), cast=("ch_thu",))
    message = refusal(doc)
    assert "Shot 3" in message and "ch_minh" in message


def test_pov_viewer_must_belong_to_the_shot() -> None:
    doc = document(stage_for("pov", "static"), cast=("ch_minh", "ch_thu"))
    assert "povId" in refusal(doc)


def test_establishing_builds_only_framed_characters() -> None:
    card = build_card(document(stage_for("establishing", "static", "LS")), "sh_x", "9:16")
    assert card["dummies"] == []


def test_pov_viewer_has_no_dummy_and_camera_at_eye_height() -> None:
    card = build_card(document(stage_for("pov", "static")), "sh_x", "9:16")
    assert "ch_lan" not in [d["id"] for d in card["dummies"]]
    params = card["camera"]["placement"]["params"]
    assert params["characterId"] == "ch_lan"
    assert params["eye"][:2] == [0.0, 1.5]
    assert params["eye"][2] == pytest.approx(0.935 * 1.7, abs=1e-3)
    assert params["yawDeg"] == 0.0 and params["pitchDeg"] == 0


def test_pov_on_worm_angle_puts_the_eye_near_the_ground() -> None:
    stage = stage_for("pov", "static")
    stage["angle"] = "worm"
    params = build_card(document(stage), "sh_x", "9:16")["camera"]["placement"]["params"]
    assert params["eye"][2] == 0.3 and params["pitchDeg"] == -25


@pytest.mark.parametrize(
    ("space", "kind"),
    [("outdoor", "ground"), ("studio", "cyclorama"), (None, "empty_ground"), ("room", "room_box")],
)
def test_stage_per_space(space: str | None, kind: str) -> None:
    card = build_card(document(stage_for("single", "static"), space=space), "sh_x", "9:16")
    assert card["stage"]["kind"] == kind and card["stage"]["space"] == space


def test_room_with_props_has_a_table_and_without_props_none() -> None:
    with_props = build_card(document(stage_for("single", "static")), "sh_x", "9:16")["stage"]
    without = build_card(document(stage_for("single", "static"), props=False), "sh_x", "9:16")[
        "stage"
    ]
    assert with_props["table"] is not None and without["table"] is None


def test_car_seats_everyone_and_holds_two_seats_and_a_wheel() -> None:
    doc = document(stage_for("two_shot", "track"), space="car", cast=("ch_minh", "ch_thu"))
    card = build_card(doc, "sh_x", "9:16")
    assert card["stage"]["kind"] == "car" and len(card["stage"]["seats"]) == 2
    assert card["stage"]["steeringWheel"]["radius"] == 0.19
    assert {d["pose"] for d in card["dummies"]} == {"seated"}
    assert card["camera"]["motion"]["params"]["characterId"] is None


def test_three_people_stand_on_an_arc_facing_its_centre() -> None:
    card = build_card(document(stage_for("group", "static")), "sh_x", "9:16")
    dummies = card["dummies"]
    assert [d["id"] for d in dummies] == ["ch_lan", "ch_minh", "ch_thu"]
    x = {d["id"]: d["foot"][0] for d in dummies}
    assert x["ch_minh"] < x["ch_lan"] < x["ch_thu"]
    for d in dummies:
        px, py = d["foot"]
        fx, fy = math.sin(math.radians(d["yawDeg"])), -math.cos(math.radians(d["yawDeg"]))
        assert fx * -px + fy * -py > 0.99 * math.hypot(px, py)


def test_fixed_pieces_sit_on_the_back_half_circle() -> None:
    card = build_card(document(stage_for("single", "static")), "sh_x", "9:16")
    assert [p["label"] for p in card["fixedPieces"]] == ["quầy", "cửa"]
    for piece in card["fixedPieces"]:
        assert piece["center"][1] > 0 and math.hypot(*piece["center"][:2]) == pytest.approx(
            2.2, abs=1e-3
        )
        assert piece["dimensions"] == [0.8, 0.6, 0.9]


def test_props_sit_on_the_table_in_a_room_and_on_the_ground_elsewhere() -> None:
    room = build_card(document(stage_for("single", "static")), "sh_x", "9:16")
    assert room["props"][0]["center"][2] > room["stage"]["table"]["dimensions"][2]
    ground = build_card(document(stage_for("single", "static"), space="outdoor"), "sh_x", "9:16")
    assert ground["props"][0]["center"][2] == pytest.approx(0.075)
    assert ground["stage"].get("table") is None


def test_prop_a_character_uses_moves_to_that_characters_camera_side() -> None:
    doc = document(stage_for("single", "static"), space="outdoor")
    staging(doc)["actions"] = [
        {"characterId": "ch_minh", "motion": "pick_up", "targetId": "pr_hop", "afterLine": None}
    ]
    card = build_card(doc, "sh_x", "9:16")
    minh = next(d for d in card["dummies"] if d["id"] == "ch_minh")
    prop = card["props"][0]
    assert math.hypot(prop["center"][0] - minh["foot"][0], prop["center"][1] - minh["foot"][1]) == (
        pytest.approx(0.45, abs=1e-3)
    )


def test_prop_users_move_the_table_in_a_room() -> None:
    doc = document(stage_for("single", "static"))
    staging(doc)["actions"] = [
        {"characterId": "ch_minh", "motion": "reach", "targetId": "pr_hop", "afterLine": None}
    ]
    card = build_card(doc, "sh_x", "9:16")
    assert card["stage"]["table"]["center"][:2] != [0.0, 0.55]
    top = card["stage"]["table"]["dimensions"][2]
    assert card["props"][0]["center"][2] > top


@pytest.mark.parametrize(("level", "count"), [("none", 0), (None, 0), ("few", 4), ("crowd", 30)])
def test_extras_count_unlabelled_and_behind(level: str | None, count: int) -> None:
    stage = stage_for("group", "static", "LS")
    stage["extras"] = level
    card = build_card(document(stage, space="outdoor"), "sh_x", "9:16")
    extras = card["extras"]
    assert len(extras["people"]) == count and extras["color"] == [120, 120, 120]
    assert all(set(p) == {"foot", "yawDeg"} for p in extras["people"])
    assert all(p["foot"][1] >= 3.0 for p in extras["people"])
    again = build_card(document(stage, space="outdoor"), "sh_x", "9:16")
    assert again["extras"] == extras


def test_placement_parameters_per_size_and_angle() -> None:
    for size, focal in (("ECU", 100), ("CU", 85), ("MCU", 70), ("MS", 50), ("MLS", 35), ("LS", 28)):
        card = build_card(document(stage_for("group", "static", size)), "sh_x", "9:16")
        assert card["camera"]["placement"]["params"]["focalMm"] == focal
    wide = build_card(document(stage_for("group", "static", "EWS")), "sh_x", "9:16")
    assert wide["camera"]["placement"]["params"]["focalMm"] == 24
    assert wide["camera"]["placement"]["params"]["margin"] == 0.3
    for angle, pitch in (("eye", 8), ("high", 25), ("low", -8), ("overhead", 60), ("worm", -15)):
        stage = stage_for("group", "static")
        stage["angle"] = angle
        card = build_card(document(stage), "sh_x", "9:16")
        assert card["camera"]["placement"]["params"]["pitchDeg"] == pitch
    estab = stage_for("establishing", "static", "LS")
    estab["angle"] = "low"
    params = build_card(document(estab), "sh_x", "9:16")["camera"]["placement"]["params"]
    assert params["pitchDeg"] == 25 and params["margin"] == 0.04


@pytest.mark.parametrize(
    ("gaze", "angle", "heading", "look"),
    [
        ("frame_left", 25, 25.0, "frame_left"),
        ("frame_right", 25, -25.0, "frame_right"),
        ("lens", None, 0.0, "lens"),
        (None, None, 30.0, "along_axis"),
    ],
)
def test_single_heading_follows_the_gaze(
    gaze: str | None, angle: int | None, heading: float, look: str
) -> None:
    doc = minh_thu()
    row = staging(doc)["frame"][0]
    row["gaze"], row["faceAngleDeg"] = gaze, angle
    params = build_card(doc, "sh1", "9:16")["camera"]["placement"]["params"]
    assert (params["headingDeg"], params["look"]) == (heading, look)


@pytest.mark.parametrize(
    ("position", "x"), [("left", -0.17), ("center", 0.0), ("right", 0.17), (None, 0.0)]
)
def test_frame_position_sets_the_screen_x(position: str | None, x: float) -> None:
    doc = minh_thu()
    staging(doc)["frame"][0]["position"] = position
    assert build_card(doc, "sh1", "9:16")["camera"]["placement"]["params"]["screenX"] == x


def test_insert_aims_at_the_first_prop_or_the_table_or_the_stage_centre() -> None:
    card = build_card(document(stage_for("insert", "static", "CU")), "sh_x", "9:16")
    params = card["camera"]["placement"]["params"]
    assert params["target"] == "pr_hop" and params["center"] == card["props"][0]["center"]
    assert params["width"] == 0.3
    bare = build_card(document(stage_for("insert", "static", "CU"), props=False), "sh_x", "9:16")
    assert bare["camera"]["placement"]["params"]["target"] == "stage_center"
    table_only = document(stage_for("insert", "static", "CU"), props=False)
    table_only["story"]["sets"][0]["props"] = [{"id": "", "name": "x"}]
    assert (
        build_card(table_only, "sh_x", "9:16")["camera"]["placement"]["params"]["target"] == "table"
    )


def test_single_subject_moves_are_solved_on_the_subject() -> None:
    def motion(move: str) -> dict[str, Any]:
        card = build_card(document(stage_for("single", move, "MS")), "sh_x", "9:16")
        params: dict[str, Any] = card["camera"]["motion"]["params"]
        return params

    assert motion("push_in") == {"inward": True, "sizeFrom": "MS", "sizeTo": "MCU"}
    assert motion("pull_back") == {"inward": False, "sizeFrom": "MS", "sizeTo": "MLS"}
    assert motion("crane") == {
        "sizeFrom": "MS",
        "sizeTo": "FS",
        "angleFrom": "eye",
        "angleTo": "overhead",
    }
    orbit = motion("orbit")
    heading = build_card(document(stage_for("single", "static", "MS")), "sh_x", "9:16")["camera"][
        "placement"
    ]["params"]["headingDeg"]
    assert orbit == {"headingFromDeg": heading - 45, "headingToDeg": heading + 45}


def test_moves_without_a_single_subject_use_fixed_parameters() -> None:
    def motion(shot_type: str, move: str) -> dict[str, Any]:
        card = build_card(document(stage_for(shot_type, move, "MS")), "sh_x", "9:16")
        params: dict[str, Any] = card["camera"]["motion"]["params"]
        return params

    assert motion("group", "push_in") == {"inward": True, "factor": 0.7}
    assert motion("group", "pull_back") == {"inward": False, "factor": 1.4}
    assert motion("group", "crane") == {"liftM": 1.5}
    assert motion("group", "orbit") == {"degrees": 90}
    assert motion("group", "pan") == {"degrees": 15} and motion("group", "tilt") == {"degrees": 15}
    assert motion("group", "truck") == {"distanceM": 1.5}
    assert motion("group", "drone") == {"heightM": 6.0, "driftM": 4.0, "pitchDeg": 40}
    assert motion("group", "handheld") == {"amplitude": 0.8, "rate": 10.0, "seed": 1}
    outdoor = build_card(document(stage_for("group", "drone"), space="outdoor"), "sh_x", "9:16")
    assert outdoor["camera"]["motion"]["params"]["heightM"] == 15.0


def test_track_subject_walks_two_metres_away_from_the_others() -> None:
    doc = minh_thu()
    staging(doc)["move"] = "track"
    card = build_card(doc, "sh1", "9:16")
    minh = next(d for d in card["dummies"] if d["id"] == "ch_minh")
    assert minh["pose"] == "walking"
    (x0, y0), (x1, y1) = minh["walk"]["from"], minh["walk"]["to"]
    assert math.hypot(x1 - x0, y1 - y0) == pytest.approx(2.0, abs=1e-3)
    assert (x1, y1) != (-1.4, 0.0)
    assert card["camera"]["motion"] == {
        "function": "follow",
        "params": {"characterId": "ch_minh", "distanceM": 2.0},
    }


def test_track_with_a_motion_on_the_subject_skips_the_walk_card_side() -> None:
    doc = minh_thu()
    staging(doc)["move"] = "track"
    staging(doc)["actions"] = [
        {"characterId": "ch_minh", "motion": "walk_to", "targetId": "ch_thu", "afterLine": None}
    ]
    card = build_card(doc, "sh1", "9:16")
    minh = next(d for d in card["dummies"] if d["id"] == "ch_minh")
    assert minh["pose"] == "standing" and minh["walk"] is None
    assert card["camera"]["motion"]["params"]["characterId"] == "ch_minh"


def test_motion_text_is_never_read() -> None:
    plain = document(stage_for("single", "static"))
    texted = copy.deepcopy(plain)
    staging(texted)["actions"] = [
        {"characterId": "ch_minh", "text": "sit down and run", "afterLine": None}
    ]
    staging(texted)["summary"] = "free text"
    assert build_card(plain, "sh_x", "9:16") == build_card(texted, "sh_x", "9:16")


def lines(*specs: tuple[str | None, str]) -> list[dict[str, Any]]:
    return [{"characterId": who, "text": text, "lang": "vi"} for who, text in specs]


def test_dialogue_splits_frames_evenly_and_silent_lines_take_the_previous_mark() -> None:
    shot = {
        "dialogue": lines(("ch_minh", "Chào."), ("ch_thu", "…"), ("ch_thu", "Ừ, chào anh.")),
    }
    subtitles, spans, marks = split_dialogue(shot, 120)
    assert [(s["fromFrame"], s["toFrame"]) for s in subtitles] == [(0, 60), (60, 120)]
    assert spans == {0: ("ch_minh", 0, 60), 2: ("ch_thu", 60, 120)}
    assert marks == [0, 60, 60, 120]


def test_two_spoken_lines_and_a_silent_one_in_a_built_card() -> None:
    dialogue = lines(("ch_minh", "Chào."), ("ch_thu", "…"), ("ch_thu", "Ừ, chào anh."))
    stage = stage_for("two_shot", "static")
    card = build_card(document(stage, dialogue=dialogue, duration_ms=5000), "sh_x", "9:16")
    assert [
        (s["speakerId"], s["text"], s["fromFrame"], s["toFrame"]) for s in card["subtitles"]
    ] == [
        ("ch_minh", "Chào.", 0, 60),
        ("ch_thu", "Ừ, chào anh.", 60, 120),
    ]
    assert card["speaking"] == [
        {"characterId": "ch_minh", "startSec": 0.0, "endSec": 2.5},
        {"characterId": "ch_thu", "startSec": 2.5, "endSec": 5.0},
    ]


def test_narrator_line_has_a_null_speaker_and_no_speaking_interval() -> None:
    dialogue = lines(
        (None, "Người kể nói."), ("ch_minh", "Chào."), ("ch_lan", "Tôi không có người nộm.")
    )
    stage = stage_for("single", "static")
    card = build_card(
        document(stage, dialogue=dialogue, cast=("ch_minh", "ch_thu")), "sh_x", "9:16"
    )
    assert card["subtitles"][0] == {
        "speakerId": None,
        "text": "Người kể nói.",
        "fromFrame": 0,
        "toFrame": 32,
    }
    assert [s["characterId"] for s in card["speaking"]] == ["ch_minh"]


def test_dialogue_text_is_normalised_and_trimmed() -> None:
    decomposed = "Chào  "
    subtitles, _, _ = split_dialogue({"dialogue": lines(("a", decomposed))}, 24)
    assert subtitles[0]["text"] == "Chào"


def test_son_tinh_fixture_builds_every_shot() -> None:
    doc = json.loads((FIXTURES / "son-tinh.json").read_text(encoding="utf-8"))
    for shot in doc["story"]["shots"]:
        if isinstance(shot.get("staging"), dict) and shot["staging"].get("shotType"):
            card = build_card(doc, shot["id"], "9:16")
            assert card["kind"] == "flowcortex.staging-card/1"
