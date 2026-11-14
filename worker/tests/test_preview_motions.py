"""Dummy motions, speaking intervals and warnings of the staging card.

Fixtures are the byte copies under tests/fixtures/preview; the numbers asserted here are the
reference behaviour, written out by hand.
"""

from __future__ import annotations

import ast
import copy
import json
import math
from collections.abc import Callable
from itertools import pairwise
from pathlib import Path
from typing import Any

import pytest

from flowcortex_worker.preview import build_card, dumps, send_card
from flowcortex_worker.preview.motions import MOTION_CODES, build_motions

FIXTURES = Path(__file__).parent / "fixtures" / "preview"
LAN, ME, AO_DAI, CHAU_HOA, MAM_CUNG = (
    "chc658mms17r",
    "chc935lk3s4n",
    "prh10w3412vk",
    "presy67z12v1",
    "prvcm6w24lpu",
)
REASONS = {
    "unknown_motion", "no_dummy", "self_target", "target_missing", "target_required",
    "hand_over_needs_person", "pick_up_needs_prop", "target_behind_camera", "hug_needs_person",
    "target_out_of_frame", "hand_over_needs_prop", "lying_down", "cannot_stand_in_car",
    "already_standing", "already_seated", "cannot_hug_in_car", "seated", "target_not_standing",
    "prop_held_by_other", "hand_full", "not_holding_prop", "camera_too_close", "path_blocked",
    "already_facing", "cannot_reach_seated", "out_of_time", "trimmed_to_shot",
}  # fmt: skip


def load(name: str) -> dict[str, Any]:
    doc: dict[str, Any] = json.loads((FIXTURES / name).read_text(encoding="utf-8"))
    return doc


def gift() -> dict[str, Any]:
    return load("lan-minh-trao-qua.json")


def actions(doc: dict[str, Any]) -> list[dict[str, Any]]:
    acts: list[dict[str, Any]] = doc["story"]["shots"][0]["staging"]["actions"]
    return acts


def act(who: str, code: Any, target: Any = None, after: int = 0, **extra: Any) -> dict[str, Any]:
    return {
        "characterId": who, "text": "", "afterLine": after, "propIds": [], "touch": None,
        "motion": code, "targetId": target, **extra,
    }  # fmt: skip


def card_of(doc: dict[str, Any], shot_id: str = "sh_trao", aspect: str = "9:16") -> dict[str, Any]:
    card = build_card(doc, shot_id, aspect)
    assert {w["reason"] for w in card["warnings"]} <= REASONS, card["warnings"]
    return card


def shot_by_order(doc: dict[str, Any], order: int) -> dict[str, Any]:
    return next(s for s in doc["story"]["shots"] if s["order"] == order)


def d8_card(
    order: int, edit: Callable[[dict[str, Any], dict[str, Any]], None] | None = None
) -> Any:
    doc = load("d8-hien-nha.json")
    shot = shot_by_order(doc, order)
    if edit:
        edit(shot, doc)
    return card_of(doc, shot["id"])


def dist(a: list[float], b: list[float]) -> float:
    return math.hypot(a[0] - b[0], a[1] - b[1])


def yaw_to(src: list[float], dst: list[float]) -> float:
    return math.degrees(math.atan2(dst[0] - src[0], -(dst[1] - src[1])))


def gap(a: float, b: float) -> float:
    return abs((a - b + 180.0) % 360.0 - 180.0)


def codes(card: dict[str, Any]) -> list[str]:
    return [m["code"] for m in card["motions"]]


def reasons(card: dict[str, Any]) -> list[tuple[str, str]]:
    return [(w["kind"], w["reason"]) for w in card["warnings"]]


def test_gift_motions_in_order_with_targets_and_no_warning() -> None:
    card = card_of(gift())
    assert [
        (m["characterId"], m["code"], m["target"]["kind"], m["target"]["id"])
        for m in card["motions"]
    ] == [
        ("ch_lan", "walk_to", "person", "ch_minh"),
        ("ch_minh", "turn_to", "person", "ch_lan"),
        ("ch_lan", "hand_over", "person", "ch_minh"),
    ]
    assert card["warnings"] == []


def test_motions_start_at_the_line_marks_of_the_subtitles() -> None:
    card = card_of(gift())
    assert [(s["fromFrame"], s["toFrame"]) for s in card["subtitles"]] == [
        (0, 48), (48, 96), (96, 144),
    ]  # fmt: skip
    walk, turn, hand = card["motions"]
    assert (walk["startSec"], turn["startSec"], hand["startSec"]) == (2.0, 2.0, 4.0)
    assert all(m["startSec"] < m["endSec"] <= 6.0 for m in card["motions"])


def test_walk_to_stops_60_cm_from_the_target_and_turns_to_it() -> None:
    card = card_of(gift())
    dummies = {d["id"]: d for d in card["dummies"]}
    walk = card["motions"][0]
    assert walk["from"]["foot"] == dummies["ch_lan"]["foot"]
    minh = dummies["ch_minh"]["foot"]
    assert dist(walk["to"]["foot"], minh) == pytest.approx(0.6, abs=1e-3)
    assert walk["target"]["position"][:2] == minh
    assert gap(walk["to"]["yawDeg"], yaw_to(walk["to"]["foot"], minh)) < 0.01
    length = dist(walk["from"]["foot"], walk["to"]["foot"])
    assert walk["walkSec"] == pytest.approx(length / 1.2, abs=1e-3)
    assert walk["endSec"] - walk["startSec"] == pytest.approx(walk["walkSec"] + 0.3, abs=1e-3)
    assert walk["to"]["pose"] == "standing" and walk["propId"] is None


def test_turn_to_faces_where_the_target_will_stand() -> None:
    walk, turn, _ = card_of(gift())["motions"]
    assert turn["from"]["foot"] == turn["to"]["foot"] and turn["walkSec"] == 0
    assert gap(turn["to"]["yawDeg"], yaw_to(turn["to"]["foot"], walk["to"]["foot"])) < 0.01


def test_hand_over_passes_the_prop_from_lan_to_minh_mid_motion() -> None:
    card = card_of(gift())
    walk, _, hand = card["motions"]
    prop = next(p for p in card["props"] if p["id"] == "pr_qua")
    assert prop["heldAtStart"] == "ch_lan"
    assert hand["propId"] == "pr_qua" and hand["target"]["id"] == "ch_minh"
    assert hand["startSec"] >= walk["endSec"] and hand["walkSec"] == 0
    assert hand["startSec"] < hand["handoverSec"] < hand["endSec"]


def test_speaking_intervals_follow_the_dialogue() -> None:
    assert card_of(gift())["speaking"] == [
        {"characterId": "ch_lan", "startSec": 0.0, "endSec": 2.0},
        {"characterId": "ch_minh", "startSec": 2.0, "endSec": 4.0},
        {"characterId": "ch_lan", "startSec": 4.0, "endSec": 6.0},
    ]


def test_narrator_and_dummy_less_speakers_do_not_speak() -> None:
    doc = gift()
    doc["story"]["shots"][0]["dialogue"].append(
        {"characterId": None, "text": "Narrated.", "lang": "vi"}
    )
    doc["story"]["shots"][0]["dialogue"].append({"characterId": "ch_ghost", "text": "Boo."})
    card = card_of(doc)
    assert [s["characterId"] for s in card["speaking"]] == ["ch_lan", "ch_minh", "ch_lan"]
    assert len(card["subtitles"]) == 5


def test_no_motion_when_actions_carry_none() -> None:
    card = card_of(load("minh-thu.json"), "sh1")
    assert card["motions"] == [] and card["warnings"] == []
    assert card["speaking"] == [{"characterId": "ch_minh", "startSec": 0.0, "endSec": 5.0}]


def test_motion_text_is_not_read() -> None:
    a, b = gift(), gift()
    for x in actions(b):
        x["text"] = "sit down and then fall"
    assert card_of(a)["motions"] == card_of(b)["motions"]


def test_same_input_same_bytes_and_document_unchanged() -> None:
    doc = gift()
    before = copy.deepcopy(doc)
    assert dumps(card_of(doc)) == dumps(card_of(doc))
    assert doc == before


def test_send_version_keeps_motions_and_speaking() -> None:
    card = card_of(gift())
    send = send_card(card)
    assert send["motions"] == card["motions"] and send["speaking"] == card["speaking"]


@pytest.mark.parametrize(
    ("edit", "reason"),
    [
        (lambda a: a[0].update(motion="dance"), "unknown_motion"),
        (lambda a: a[0].update(characterId="ch_ghost"), "no_dummy"),
        (lambda a: a[0].update(targetId="ch_ghost"), "target_missing"),
        (lambda a: a[1].update(targetId=None), "target_required"),
        (lambda a: a[1].update(targetId="ch_minh"), "self_target"),
        (lambda a: a[2].update(propIds=[]), "hand_over_needs_prop"),
        (lambda a: a[2].update(propIds=["pr_ghost"]), "hand_over_needs_prop"),
        (lambda a: a[2].update(targetId="pr_qua"), "hand_over_needs_person"),
        (lambda a: a[1].update(motion="pick_up", targetId="ch_lan"), "pick_up_needs_prop"),
    ],
)
def test_a_motion_that_cannot_be_performed_is_dropped_with_a_reason(
    edit: Callable[[list[dict[str, Any]]], None], reason: str
) -> None:
    doc = gift()
    edit(actions(doc))
    card = card_of(doc)
    assert len(card["motions"]) <= 2
    assert ("dropped", reason) in reasons(card), card["warnings"]
    assert {"kind", "characterId", "motion", "reason"} == set(card["warnings"][0])


def test_unknown_motion_names_the_code_and_builds_the_rest() -> None:
    doc = gift()
    actions(doc)[0]["motion"] = "dance"
    card = card_of(doc)
    assert card["warnings"] == [
        {"kind": "dropped", "characterId": "ch_lan", "motion": "dance", "reason": "unknown_motion"}
    ]
    assert codes(card) == ["turn_to", "hand_over"]


def test_null_or_missing_motion_is_not_a_motion() -> None:
    doc = gift()
    actions(doc)[0]["motion"] = None
    del actions(doc)[1]["motion"]
    card = card_of(doc)
    assert codes(card) == ["hand_over"] and card["warnings"] == []


def test_wrong_typed_motion_data_does_not_crash() -> None:
    doc = gift()
    acts = actions(doc)
    acts[0]["motion"] = ["walk_to"]
    acts[1]["targetId"] = {"id": "ch_lan"}
    acts[2]["propIds"] = [["pr_qua"]]
    acts.append(act(["ch_lan"], "nod", after="1"))  # type: ignore[arg-type]
    card = card_of(doc)
    assert card["motions"] == []
    assert sorted(r for _, r in reasons(card)) == [
        "hand_over_needs_prop", "no_dummy", "target_missing", "unknown_motion",
    ]  # fmt: skip


def test_after_line_past_the_last_line_clamps_and_silent_lines_take_the_previous_mark() -> None:
    doc = gift()
    shot = doc["story"]["shots"][0]
    shot["dialogue"].insert(1, {"characterId": "ch_thu", "text": "…", "lang": "vi"})
    actions(doc)[:] = [act("ch_lan", "nod", after=2)]
    assert card_of(doc)["motions"][0]["startSec"] == 2.0
    actions(doc)[0]["afterLine"] = 99
    (nod,) = card_of(doc)["motions"]
    assert nod["endSec"] == 6.0


def test_one_character_works_through_its_motions_one_after_another() -> None:
    doc = gift()
    actions(doc).append(act("ch_minh", "nod", after=1))
    turn, nod = [m for m in card_of(doc)["motions"] if m["characterId"] == "ch_minh"]
    assert nod["startSec"] == pytest.approx(turn["endSec"])
    assert nod["endSec"] - nod["startSec"] == pytest.approx(0.8)


def test_a_late_motion_starts_earlier_so_it_ends_by_the_last_frame() -> None:
    doc = gift()
    actions(doc)[2]["afterLine"] = 3
    walk, _, hand = card_of(doc)["motions"]
    assert hand["endSec"] == 6.0 and hand["startSec"] >= walk["endSec"]


def test_a_motion_longer_than_the_shot_is_shortened_when_a_third_of_a_second_fits() -> None:
    doc = gift()
    shot = doc["story"]["shots"][0]
    shot["durationTargetMs"] = 1000
    actions(doc)[:] = [act("ch_lan", "raise_arms")]  # needs 1.6 s of a 1 s shot
    card = card_of(doc)
    (m,) = card["motions"]
    assert m["startSec"] == 0.0 and m["endSec"] == 1.0
    assert reasons(card) == [("shortened", "trimmed_to_shot")]
    assert card["warnings"][0]["motion"] == "raise_arms"


def test_a_motion_with_less_than_a_third_of_a_second_left_is_dropped() -> None:
    doc = gift()
    doc["story"]["shots"][0]["durationTargetMs"] = 1000
    actions(doc)[:] = [act("ch_lan", "raise_arms"), act("ch_lan", "raise_arms")]
    card = card_of(doc)
    assert len(card["motions"]) == 1 and card["motions"][0]["endSec"] == 1.0
    assert reasons(card) == [("shortened", "trimmed_to_shot"), ("dropped", "out_of_time")]


def twelve_codes_doc() -> dict[str, Any]:
    doc = gift()
    shot = doc["story"]["shots"][0]
    shot["id"], shot["durationTargetMs"] = "sh_12", 14000
    doc["story"]["sets"][0]["props"].append({"id": "pr_bong", "name": "ball", "count": 1})
    shot["staging"]["actions"] = [
        act("ch_lan", "walk_to", "ch_minh"),
        act("ch_lan", "reach", "ch_minh", touch={"characterId": "ch_minh", "zone": "shoulder"}),
        act("ch_lan", "hand_over", "ch_minh", propIds=["pr_qua"]),
        act("ch_lan", "point_at", "pr_bong"),
        act("ch_minh", "turn_to", "ch_lan"),
        act("ch_minh", "nod", after=1),
        act("ch_minh", "sit_down", after=2),
        act("ch_minh", "stand_up", after=2),
        act("ch_thu", "run_to", None),
        act("ch_thu", "raise_arms"),
        act("ch_thu", "pick_up", "pr_bong", after=1),
        act("ch_thu", "fall", after=2),
    ]
    return doc


def test_every_code_of_the_closed_list_is_performed_with_the_right_poses() -> None:
    card = card_of(twelve_codes_doc(), "sh_12")
    assert card["warnings"] == []
    assert sorted(codes(card)) == sorted(c for c in MOTION_CODES if c not in ("hug", "walk_away"))
    by = {m["code"]: m for m in card["motions"]}
    assert by["sit_down"]["to"]["pose"] == "seated" and by["sit_down"]["stool"] is not None
    assert by["stand_up"]["from"]["pose"] == "seated" and by["stand_up"]["to"]["pose"] == "standing"
    assert by["fall"]["to"]["pose"] == "fallen"
    assert by["run_to"]["target"]["kind"] == "camera"
    run = by["run_to"]
    assert dist(run["from"]["foot"], run["to"]["foot"]) == pytest.approx(1.5, abs=1e-3)
    assert run["walkSec"] == pytest.approx(1.5 / 2.4, abs=1e-3)
    ball = next(p for p in card["props"] if p["id"] == "pr_bong")
    assert by["pick_up"]["propId"] == "pr_bong" and by["pick_up"]["walkSec"] > 0
    assert dist(by["pick_up"]["to"]["foot"], ball["center"]) < 0.7 and ball["heldAtStart"] is None
    assert by["reach"]["target"]["position"][2] == pytest.approx(0.8 * 1.7, abs=1e-3)
    for who in ("ch_lan", "ch_minh", "ch_thu"):
        own = sorted(
            (m for m in card["motions"] if m["characterId"] == who), key=lambda m: m["startSec"]
        )
        assert all(a["endSec"] <= b["startSec"] + 1e-9 for a, b in pairwise(own))
    assert all(m["endSec"] <= 14.0 for m in card["motions"])


def test_point_at_without_a_target_points_straight_ahead() -> None:
    doc = gift()
    actions(doc)[:] = [act("ch_lan", "point_at")]
    card = card_of(doc)
    (m,) = card["motions"]
    assert m["target"] is None and card["warnings"] == []
    assert m["to"]["yawDeg"] == m["from"]["yawDeg"] and m["to"]["foot"] == m["from"]["foot"]


def test_a_car_seats_everyone_so_sit_stand_up_and_hug_are_refused() -> None:
    doc = gift()
    doc["story"]["sets"][0]["space"] = "car"
    actions(doc)[:] = [act("ch_minh", c) for c in ("sit_down", "stand_up", "nod")]
    card = card_of(doc)
    assert codes(card) == ["nod"]
    assert reasons(card) == [("dropped", "already_seated"), ("dropped", "cannot_stand_in_car")]
    actions(doc)[:] = [act("ch_lan", "hug", "ch_minh")]
    assert reasons(card_of(doc)) == [("dropped", "cannot_hug_in_car")]


def test_pose_rules_for_standing_seated_and_fallen_characters() -> None:
    doc = gift()
    actions(doc)[:] = [act("ch_lan", "stand_up")]
    assert reasons(card_of(doc)) == [("dropped", "already_standing")]
    actions(doc)[:] = [act("ch_lan", "fall"), act("ch_lan", "nod")]
    assert reasons(card_of(doc)) == [("dropped", "lying_down")]
    actions(doc)[:] = [act("ch_lan", "sit_down"), act("ch_lan", "walk_to", "ch_minh")]
    assert reasons(card_of(doc)) == [("dropped", "seated")]
    actions(doc)[:] = [act("ch_lan", "fall"), act("ch_lan", "stand_up")]
    card = card_of(doc)
    assert codes(card) == ["fall", "stand_up"] and card["warnings"] == []


def test_a_seated_character_cannot_reach_what_is_far() -> None:
    doc = gift()
    actions(doc)[:] = [act("ch_lan", "sit_down"), act("ch_lan", "reach", "ch_minh")]
    assert ("dropped", "cannot_reach_seated") in reasons(card_of(doc))


def test_hands_hold_one_prop_and_a_prop_has_one_holder() -> None:
    doc = twelve_codes_doc()
    shot = doc["story"]["shots"][0]
    pick = act("ch_lan", "pick_up", "pr_qua")
    shot["staging"]["actions"] = [pick, act("ch_minh", "pick_up", "pr_qua")]
    assert ("dropped", "prop_held_by_other") in reasons(card_of(doc, "sh_12"))
    shot["staging"]["actions"] = [pick, act("ch_lan", "pick_up", "pr_bong")]
    assert ("dropped", "hand_full") in reasons(card_of(doc, "sh_12"))
    shot["staging"]["actions"] = [
        act("ch_lan", "pick_up", "pr_bong"),
        act("ch_lan", "hand_over", "ch_minh", propIds=["pr_qua"]),
    ]
    assert ("dropped", "not_holding_prop") in reasons(card_of(doc, "sh_12"))
    shot["staging"]["actions"] = [
        act("ch_minh", "pick_up", "pr_bong"),
        act("ch_lan", "hand_over", "ch_minh", propIds=["pr_qua"]),
    ]
    assert ("dropped", "hand_full") in reasons(card_of(doc, "sh_12"))


def test_a_fallen_character_cannot_receive_a_prop() -> None:
    doc = gift()
    actions(doc)[:] = [
        act("ch_minh", "fall"),
        act("ch_lan", "hand_over", "ch_minh", propIds=["pr_qua"]),
    ]
    assert ("dropped", "target_not_standing") in reasons(card_of(doc))


def test_a_pov_viewer_behind_the_camera_cannot_be_reached_or_handed_to() -> None:
    doc = load("minh-thu.json")
    shot = doc["story"]["shots"][0]
    shot["staging"].update(shotType="pov", povId="ch_minh", frame=[])
    shot["staging"]["actions"] = [act("ch_thu", "reach", "ch_minh")]
    assert reasons(card_of(doc, "sh1")) == [("dropped", "target_behind_camera")]
    shot["staging"]["actions"] = [act("ch_thu", "hug", "ch_minh")]
    assert reasons(card_of(doc, "sh1")) == [("dropped", "hug_needs_person")]


def test_track_with_a_motion_skips_the_two_metre_walk() -> None:
    doc = gift()
    doc["story"]["shots"][0]["staging"]["move"] = "track"
    card = card_of(doc)
    lan = next(d for d in card["dummies"] if d["id"] == "ch_lan")
    assert lan["pose"] == "standing" and lan["walk"] is None
    assert card["camera"]["motion"]["params"]["characterId"] == "ch_lan"


def test_walk_to_without_a_target_keeps_the_camera_gap_and_slows_down() -> None:
    doc = load("son-tinh.json")
    shot = shot_by_order(doc, 5)
    card = card_of(doc, shot["id"])
    walk = next(m for m in card["motions"] if m["code"] == "walk_to")
    camera = walk["target"]["cameraPosition"]
    length = dist(walk["from"]["foot"], walk["to"]["foot"])
    assert walk["target"]["kind"] == "camera" and 0.2 <= length < 1.5
    assert dist(walk["to"]["foot"], camera) >= 0.6
    assert walk["speedRatio"] == pytest.approx(0.5)
    assert walk["walkSec"] * 1.2 * walk["speedRatio"] == pytest.approx(length, abs=1e-3)


def test_walk_to_without_a_target_walks_until_the_end_of_the_shot() -> None:
    doc = load("demo-hanh-trinh.json")
    card = card_of(doc, "sh1n062kljd6")
    (walk,) = card["motions"]
    assert walk["startSec"] == 0.0 and walk["endSec"] == pytest.approx(7.0, abs=1e-3)
    assert walk["speedRatio"] == 1.0 and dist(walk["from"]["foot"], walk["to"]["foot"]) > 1.5


def test_the_walker_stops_next_to_the_target_and_not_on_anyone_else() -> None:
    doc = load("son-tinh.json")
    card = card_of(doc, shot_by_order(doc, 3)["id"])
    end = {d["id"]: d["foot"] for d in card["dummies"]}
    for m in sorted(card["motions"], key=lambda m: m["endSec"]):
        end[m["characterId"]] = m["to"]["foot"]
    names = sorted(end)
    assert all(dist(end[a], end[b]) >= 0.5 - 1e-6 for a in names for b in names if a < b)
    walker = card["motions"][-1]
    path = walker["path"]
    assert path[0] == walker["from"]["foot"] and path[-1] == walker["to"]["foot"]
    assert walker["walkSec"] == pytest.approx(
        sum(dist(a, b) for a, b in pairwise(path)) / 1.2, abs=1e-3
    )


def test_a_path_goes_round_the_table() -> None:
    doc = gift()
    doc["story"]["sets"][0]["space"] = "room"
    card = card_of(doc)
    (bx, by, _), (bw, bd, _) = (
        card["stage"]["table"]["center"],
        card["stage"]["table"]["dimensions"],
    )
    path = card["motions"][0]["path"]
    for a, b in pairwise(path):
        for k in range(11):
            x, y = (a[i] + (b[i] - a[i]) * k / 10 for i in (0, 1))
            assert not (
                bx - bw / 2 - 0.2 < x < bx + bw / 2 + 0.2
                and by - bd / 2 - 0.2 < y < by + bd / 2 + 0.2
            )


def test_turn_to_when_already_facing_is_dropped() -> None:
    doc = load("son-tinh.json")
    card = card_of(doc, shot_by_order(doc, 4)["id"])
    assert card["motions"] == [] and ("dropped", "already_facing") in reasons(card)


def test_nod_amplitude_grows_with_the_shot_size() -> None:
    doc = load("son-tinh.json")
    amplitude = {}
    for size in ("CU", "MS", "LS", "EWS"):
        shot = shot_by_order(doc, 1)
        shot["staging"]["size"] = size
        card = card_of(doc, shot["id"])
        amplitude[size] = next(m["amplitude"] for m in card["motions"] if m["code"] == "nod")
    assert amplitude["CU"] < amplitude["MS"] < amplitude["LS"] <= amplitude["EWS"]


def test_hug_walks_to_40_cm_faces_the_partner_and_the_partner_answers() -> None:
    card = d8_card(5)
    assert card["warnings"] == []
    (hug,) = card["motions"]
    lan = next(d for d in card["dummies"] if d["id"] == LAN)
    assert (hug["characterId"], hug["code"], hug["target"]["id"]) == (ME, "hug", LAN)
    assert hug["target"]["position"][2] == pytest.approx(0.8 * 1.7)
    assert dist(hug["to"]["foot"], lan["foot"]) == pytest.approx(0.4, abs=1e-3)
    assert gap(hug["to"]["yawDeg"], yaw_to(hug["to"]["foot"], lan["foot"])) < 0.01
    length = sum(dist(a, b) for a, b in pairwise(hug["path"]))
    assert hug["walkSec"] == pytest.approx(length / 1.2, abs=1e-3)
    assert hug["endSec"] - hug["startSec"] == pytest.approx(hug["walkSec"] + 2.0, abs=1e-3)
    reply = hug["response"]
    assert reply["characterId"] == LAN and reply["fromYawDeg"] == lan["yawDeg"]
    assert gap(reply["toYawDeg"], yaw_to(lan["foot"], hug["to"]["foot"])) < 0.01


def test_hug_waits_for_the_partner_to_finish_and_the_partner_turns_from_there() -> None:
    def edit(shot: dict[str, Any], _doc: dict[str, Any]) -> None:
        acts = shot["staging"]["actions"]
        lan = next(a for a in acts if a["characterId"] == LAN)
        acts.remove(lan)
        acts.insert(0, dict(lan, motion="turn_to", targetId=MAM_CUNG))

    turn, hug = d8_card(5, edit)["motions"]
    assert (turn["characterId"], hug["characterId"]) == (LAN, ME)
    assert hug["startSec"] + hug["walkSec"] >= turn["endSec"] - 1e-6
    assert hug["response"]["fromYawDeg"] == turn["to"]["yawDeg"]


def test_hug_within_tolerance_does_not_step() -> None:
    def edit(shot: dict[str, Any], _doc: dict[str, Any]) -> None:
        shot["staging"]["actions"].insert(0, act(ME, "reach", LAN))

    reach, hug = d8_card(5, edit)["motions"]
    assert dist(reach["to"]["foot"], hug["target"]["position"]) == pytest.approx(0.55, abs=1e-3)
    assert hug["walkSec"] == 0.0 and hug["to"]["foot"] == hug["from"]["foot"]


@pytest.mark.parametrize("case", ["lan_seated", "me_seated", "in_car", "lan_fallen"])
def test_hug_is_refused_when_someone_is_seated_fallen_or_in_a_car(case: str) -> None:
    def edit(shot: dict[str, Any], doc: dict[str, Any]) -> None:
        if case == "in_car":
            next(s for s in doc["story"]["sets"] if s["id"] == shot["staging"]["setId"])[
                "space"
            ] = "car"
            return
        who, code = {
            "lan_seated": (LAN, "sit_down"),
            "me_seated": (ME, "sit_down"),
            "lan_fallen": (LAN, "fall"),
        }[case]
        shot["staging"]["actions"].insert(0, act(who, code))

    card = d8_card(5, edit)
    assert "hug" not in codes(card)
    assert any(w["motion"] == "hug" and w["characterId"] == ME for w in card["warnings"])


@pytest.mark.parametrize(
    ("target", "reason"),
    [
        (None, "target_required"),
        (ME, "self_target"),
        (AO_DAI, "hug_needs_person"),
        ("ch_ghost", "target_missing"),
    ],
)
def test_hug_needs_another_person_as_target(target: Any, reason: str) -> None:
    def edit(shot: dict[str, Any], _doc: dict[str, Any]) -> None:
        next(a for a in shot["staging"]["actions"] if a["characterId"] == ME)["targetId"] = target

    card = d8_card(5, edit)
    assert card["motions"] == [] and reasons(card) == [("dropped", reason)]


def test_hug_target_outside_the_frame_is_dropped() -> None:
    def edit(shot: dict[str, Any], _doc: dict[str, Any]) -> None:
        shot["staging"]["shotType"] = "single"
        shot["staging"]["frame"] = [h for h in shot["staging"]["frame"] if h["characterId"] == ME]

    assert reasons(d8_card(5, edit)) == [("dropped", "target_out_of_frame")]


def test_walk_away_backs_off_then_leaves_until_the_end_of_the_shot() -> None:
    card = d8_card(6)
    assert card["warnings"] == []
    (away,) = card["motions"]
    camera = away["target"]["cameraPosition"]
    assert away["target"]["kind"] == "camera" and away["target"]["id"] is None
    p0, p1 = away["path"][0], away["path"][1]
    assert p0 == away["from"]["foot"] and away["path"][-1] == away["to"]["foot"]
    assert dist(p0, p1) == pytest.approx(0.5, abs=1e-3)
    assert away["backSec"] == pytest.approx(0.5 / 0.6, abs=1e-3)
    duration = shot_by_order(load("d8-hien-nha.json"), 6)["durationTargetMs"] / 1000
    assert away["startSec"] == 0.0 and away["endSec"] == pytest.approx(duration, abs=1e-3)
    assert away["endSec"] - away["startSec"] == pytest.approx(
        away["backSec"] + 0.3 + away["walkSec"], abs=1e-3
    )
    away_from_camera = [dist(p, camera) for p in away["path"]]
    assert all(b > a for a, b in pairwise(away_from_camera))
    assert away["to"]["pose"] == "standing" and away["speedRatio"] == 1.0


def test_walk_away_stays_on_the_stage() -> None:
    def edit(shot: dict[str, Any], doc: dict[str, Any]) -> None:
        next(s for s in doc["story"]["sets"] if s["id"] == shot["staging"]["setId"])["space"] = (
            "room"
        )

    card = d8_card(6, edit)
    (away,) = card["motions"]
    (cx, cy), width, depth = card["stage"]["center"], card["stage"]["width"], card["stage"]["depth"]
    for x, y in away["path"]:
        assert cx - width / 2 + 0.3 - 1e-6 <= x <= cx + width / 2 - 0.3 + 1e-6
        assert cy - depth / 2 + 0.3 - 1e-6 <= y <= cy + depth / 2 - 0.3 + 1e-6
    assert 0.5 <= away["speedRatio"] <= 1.0


@pytest.mark.parametrize("target", [ME, CHAU_HOA])
def test_walk_away_from_a_person_or_a_prop_gets_further_every_step(target: str) -> None:
    def edit(shot: dict[str, Any], _doc: dict[str, Any]) -> None:
        for a in shot["staging"]["actions"]:
            a["motion"], a["targetId"] = None, None
        lan = next(a for a in shot["staging"]["actions"] if a["characterId"] == LAN)
        lan["motion"], lan["targetId"] = "walk_away", target

    (away,) = d8_card(5, edit)["motions"]
    goal = away["target"]["position"][:2]
    assert away["target"]["id"] == target
    assert dist(away["path"][0], away["path"][1]) == pytest.approx(0.5, abs=1e-3)
    far = [dist(p, goal) for p in away["path"]]
    assert all(b > a for a, b in pairwise(far))
    assert away["endSec"] == pytest.approx(6.0, abs=1e-3)


def test_walk_away_never_passes_through_the_camera() -> None:
    def edit(shot: dict[str, Any], _doc: dict[str, Any]) -> None:
        shot["staging"]["actions"][0]["targetId"] = AO_DAI

    (away,) = d8_card(6, edit)["motions"]
    camera = away["target"]["cameraPosition"]
    for a, b in pairwise(away["path"]):
        for k in range(21):
            p = [a[i] + (b[i] - a[i]) * k / 20 for i in (0, 1)]
            assert dist(p, camera) >= 0.6 - 1e-3


@pytest.mark.parametrize("before", ["sit_down", "fall"])
def test_walk_away_is_refused_when_seated_or_fallen(before: str) -> None:
    def edit(shot: dict[str, Any], _doc: dict[str, Any]) -> None:
        shot["staging"]["actions"].insert(0, act(LAN, before))

    card = d8_card(6, edit)
    assert codes(card) == [before]
    assert any(w["motion"] == "walk_away" for w in card["warnings"])


def stand_alone(
    stage: dict[str, Any],
    code: str,
    others: dict[str, list[float]] | None = None,
    camera: dict[str, Any] | None = None,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    dummies = {
        who: {
            "foot": foot,
            "yawDeg": 0.0,
            "pose": "standing",
            "z0": 0.0,
            "height": 1.7,
            "inFrame": True,
        }
        for who, foot in {"a": [0.0, 0.0], **(others or {})}.items()
    }
    staging = {"actions": [act("a", code)]}
    motions, _, warnings = build_motions(
        staging,
        dummies,
        {},
        None,
        [],
        [],
        stage,
        [0],
        {},
        120,
        "room",
        lambda x, y: (0.0, -1.0),
        camera,
        "MS",
        None,
    )
    return motions, warnings


def test_walk_away_with_no_room_to_move_is_dropped() -> None:
    pocket = {"kind": "empty_ground", "width": 0.8, "depth": 0.8}
    motions, warnings = stand_alone(pocket, "walk_away")
    assert motions == [] and [w["reason"] for w in warnings] == ["path_blocked"]
    corridor = {"kind": "empty_ground", "width": 0.8, "depth": 6.6}
    motions, warnings = stand_alone(corridor, "walk_away", others={"b": [0.0, 0.6]})
    assert motions == [] and [w["reason"] for w in warnings] == ["path_blocked"]


def test_walk_away_along_a_clear_corridor() -> None:
    corridor = {"kind": "empty_ground", "width": 0.8, "depth": 6.6}
    (away,), warnings = stand_alone(corridor, "walk_away")
    assert warnings == [] and away["path"][1] == [0.0, 0.5] and away["to"]["foot"][1] > 2.5


def test_walk_to_without_a_target_is_dropped_when_the_camera_is_on_top_of_the_walker() -> None:
    camera = {
        "position": (0.0, -0.7, 1.5), "direction": (0.0, 1.0, 0.0), "focalMm": 35.0,
        "sensorMm": 36.0, "aspect": 0.574, "marginV": 0.0, "marginH": 0.0,
    }  # fmt: skip
    stage = {"kind": "empty_ground", "width": 30.0, "depth": 30.0}
    motions, warnings = stand_alone(stage, "walk_to", camera=camera)
    assert motions == [] and [w["reason"] for w in warnings] == ["camera_too_close"]


def test_every_warning_reason_of_the_closed_list_has_a_case() -> None:
    tree = ast.parse(Path(__file__).read_text(encoding="utf-8"))
    listed = {
        id(node)
        for stmt in tree.body
        if isinstance(stmt, ast.Assign)
        and any(isinstance(t, ast.Name) and t.id == "REASONS" for t in stmt.targets)
        for node in ast.walk(stmt)
    }
    expected = {
        node.value
        for node in ast.walk(tree)
        if isinstance(node, ast.Constant) and isinstance(node.value, str) and id(node) not in listed
    }
    assert not REASONS - expected, sorted(REASONS - expected)
