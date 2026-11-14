"""Send version of the staging card: what is stripped, what is kept, input left untouched."""

from __future__ import annotations

import copy
import json
from typing import Any

from test_preview_card import FIXTURES, minh_thu, to_english, without_text

from flowcortex_worker.preview import build_card, dumps, send_card


def preview(mutate: Any = None) -> dict[str, Any]:
    doc = minh_thu()
    if mutate:
        mutate(doc)
    return build_card(doc, "sh1", "9:16")


def test_golden_send_card_through_the_glossary() -> None:
    golden = json.loads((FIXTURES / "minh-thu-1.gui-di.card.json").read_text(encoding="utf-8"))
    assert without_text(send_card(preview())) == without_text(to_english(golden))


def test_labels_and_subtitles_are_stripped() -> None:
    send = send_card(preview())
    assert send["send"] is True
    assert [d["label"] for d in send["dummies"]] == [None, None]
    assert [p["label"] for p in send["fixedPieces"]] == [None, None]
    assert [p["label"] for p in send["props"]] == [None]
    assert send["subtitles"] == []


def test_extras_are_removed_for_a_crowd_shot_but_the_declared_level_stays() -> None:
    def crowd(doc: dict[str, Any]) -> None:
        doc["story"]["shots"][0]["staging"]["extras"] = "crowd"

    card = preview(crowd)
    assert len(card["extras"]["people"]) > 0
    send = send_card(card)
    assert send["extras"]["people"] == [] and send["extras"]["level"] == "crowd"


def test_walls_are_off_and_floor_and_table_are_kept() -> None:
    send = send_card(preview())
    assert send["stage"]["flatPanels"] is False
    assert send["stage"]["kind"] == "room_box" and send["stage"]["table"] is not None


def test_camera_cast_blocks_and_frame_are_unchanged() -> None:
    card = preview()
    send = send_card(card)
    for key in ("kind", "shot", "frame", "axis", "camera", "motions", "speaking"):
        assert send[key] == card[key], key

    def unlabelled(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
        return [{k: v for k, v in i.items() if k != "label"} for i in items]

    for key in ("dummies", "fixedPieces", "props"):
        assert unlabelled(send[key]) == unlabelled(card[key]), key
    assert {k: v for k, v in send["stage"].items() if k != "flatPanels"} == card["stage"]


def test_input_card_is_unchanged_and_the_send_version_deterministic() -> None:
    card = preview()
    before = copy.deepcopy(card)
    assert dumps(send_card(card)) == dumps(send_card(card))
    assert card == before
