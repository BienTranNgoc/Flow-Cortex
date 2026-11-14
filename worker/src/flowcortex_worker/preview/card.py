"""Staging card of one shot: a pure function of the project document, the shot id and the aspect.

The card holds everything the Blender scene needs to draw the shot, so the scene never reads the
project document. No clock, no network, seeded randomness only; free text the member typed is
copied to labels and subtitles, never interpreted.
"""

from __future__ import annotations

import copy
import json
import re
from typing import Any

from flowcortex_worker.preview.camera import (
    estimate_camera,
    heading_from_gaze,
    size_step,
    toward_camera,
)
from flowcortex_worker.preview.motions import build_motions
from flowcortex_worker.preview.stage import (
    build_stage,
    extras_for,
    fixed_pieces,
    nfc,
    place_props_camera_side,
    prop_blocks,
    pt,
    rnd,
    seat_cast,
    stage_points,
    walk_heading,
    yaw_toward,
)
from flowcortex_worker.preview.vocabulary import (
    ANGLES,
    CAMERA_MIN_DISTANCE_M,
    CARD_KIND,
    CRANE_LIFT_M,
    DEFAULT_COLOR,
    DOLLY_FACTOR,
    DRONE_DRIFT_M,
    DRONE_HEIGHT_M,
    DRONE_HEIGHT_OTHER_M,
    DRONE_PITCH_DEG,
    ESTABLISHING_PITCH_MIN_DEG,
    EYE_PITCH_DEG,
    FOLLOW_WALK_M,
    FPS,
    FRAME_BY_ASPECT,
    GROUP_MARGIN,
    GROUP_MARGIN_DEFAULT,
    GROUP_PITCH_DEG,
    HANDHELD_PARAMS,
    HUMAN_HEIGHT_M,
    INSERT_WIDTH_M,
    MOTION_BY_MOVE,
    MOVES,
    ORBIT_DEG,
    PAN_TILT_DEG,
    PLACEMENT_BY_SHOT_TYPE,
    POV_EYE_RATIO,
    POV_GROUND_EYE_M,
    SCREEN_X,
    SENSOR_MM_BY_ASPECT,
    SHOT_TYPES,
    SIZE_BOTTOM_RATIO,
    SIZE_FOCAL_MM,
    SIZES,
    SPACES,
    TRUCK_M,
)

Card = dict[str, Any]

FOLLOWING_MOTIONS = ("dolly", "crane", "orbit")

_ARRAY_OF_NUMBERS = re.compile(r"\[\s*(-?[\d.]+(?:,\s*-?[\d.]+)*)\s*\]")


def _expect(value: Any, kind: type | tuple[type, ...], label: Any, path: str, what: str) -> None:
    if not isinstance(value, kind):
        raise ValueError(f"Shot {label}: {path} must be {what}, got {type(value).__name__}")


def _optional(
    owner: dict[str, Any], key: str, kind: type | tuple[type, ...], label: Any, path: str, what: str
) -> None:
    if owner.get(key) is not None:
        _expect(owner[key], kind, label, path, what)


def _choose(
    value: Any, allowed: tuple[str, ...], field: str, order: Any, required: bool = True
) -> Any:
    if value is None:
        if required:
            raise ValueError(f"Shot {order}: {field} is required")
        return None
    if not isinstance(value, str) or value not in allowed:
        raise ValueError(f"Shot {order}: {field} '{value}' is not one of {', '.join(allowed)}")
    return value


def _find_shot(doc: Any, shot_id: str) -> dict[str, Any]:
    if not isinstance(doc, dict):
        raise ValueError("The document must be a JSON object")
    story = doc.get("story")
    if not isinstance(story, dict):
        raise ValueError("The document has no story object")
    shots = story.get("shots")
    if not isinstance(shots, list):
        raise ValueError("story.shots must be a list")
    for shot in shots:
        if isinstance(shot, dict) and shot.get("id") == shot_id:
            return shot
    raise ValueError(f"No shot '{shot_id}' in the document")


def _check_shape(
    doc: dict[str, Any], shot: dict[str, Any], staging: dict[str, Any], label: Any
) -> None:
    """Refuse a wrong-typed container before the builder reads it, naming the shot and the field."""
    _optional(doc, "characters", list, label, "characters", "a list")
    _optional(doc["story"], "sets", list, label, "story.sets", "a list")
    _optional(shot, "characterIds", list, label, "characterIds", "a list")
    _optional(shot, "dialogue", list, label, "dialogue", "a list")
    _optional(shot, "title", str, label, "title", "a string")
    for i, line in enumerate(shot.get("dialogue") or []):
        _expect(line, dict, label, f"dialogue[{i}]", "an object")
        _optional(line, "text", str, label, f"dialogue[{i}].text", "a string")
        _optional(line, "characterId", str, label, f"dialogue[{i}].characterId", "a string")
    for i, m in enumerate(shot.get("characterIds") or []):
        _expect(m, str, label, f"characterIds[{i}]", "a string")
    _optional(staging, "frame", list, label, "staging.frame", "a list")
    _optional(staging, "actions", list, label, "staging.actions", "a list")
    _optional(staging, "extras", str, label, "staging.extras", "a string")
    _optional(staging, "povId", str, label, "staging.povId", "a string")
    for i, row in enumerate(staging.get("frame") or []):
        _expect(row, dict, label, f"staging.frame[{i}]", "an object")
        _optional(row, "characterId", str, label, f"staging.frame[{i}].characterId", "a string")
    for i, action in enumerate(staging.get("actions") or []):
        _expect(action, dict, label, f"staging.actions[{i}]", "an object")


def _check_set(chosen: dict[str, Any] | None, label: Any) -> None:
    if chosen is None:
        return
    _optional(chosen, "props", list, label, "set.props", "a list")
    _optional(chosen, "fixedPieces", list, label, "set.fixedPieces", "a list")
    _optional(chosen, "leftCharacterId", str, label, "set.leftCharacterId", "a string")
    for i, p in enumerate(chosen.get("props") or []):
        _expect(p, dict, label, f"set.props[{i}]", "an object")


def split_dialogue(
    shot: dict[str, Any], frame_count: int
) -> tuple[list[dict[str, Any]], dict[int, tuple[str | None, int, int]], list[int]]:
    """Subtitles, per-line (speaker, from, to) frames and the end mark of each line.

    Spoken lines (those holding a letter or digit) split the shot's frames evenly in order. A silent
    line has no frames of its own and takes the end mark of the spoken line before it; mark 0 is the
    start of the shot, mark k+1 the end of line k.
    """
    lines = shot.get("dialogue") or []
    spoken = [(i, c) for i, c in enumerate(lines) if _is_spoken(c.get("text"))]
    subtitles = []
    spans: dict[int, tuple[str | None, int, int]] = {}
    for j, (i, c) in enumerate(spoken):
        start, end = j * frame_count // len(spoken), (j + 1) * frame_count // len(spoken)
        speaker = c.get("characterId")
        subtitles.append(
            {
                "speakerId": speaker,
                "text": nfc(c["text"]).strip(),
                "fromFrame": start,
                "toFrame": end,
            }
        )
        spans[i] = (speaker, start, end)
    marks, last = [0], 0
    for i in range(len(lines)):
        last = spans[i][2] if i in spans else last
        marks.append(last)
    return subtitles, spans, marks


def _is_spoken(text: Any) -> bool:
    return isinstance(text, str) and any(ch.isalnum() for ch in nfc(text))


def _color(characters: dict[str, Any], who: str) -> list[int]:
    color = characters.get(who, {}).get("clayColor")
    if (
        isinstance(color, list)
        and len(color) == 3
        and all(isinstance(c, int) and 0 <= c <= 255 for c in color)
    ):
        return list(color)
    return list(DEFAULT_COLOR)


def _motion_params(
    move: str,
    follows_subject: bool,
    size: str | None,
    angle: str,
    space: str | None,
    placement_params: dict[str, Any],
    tracked: str | None,
) -> dict[str, Any]:
    function = MOTION_BY_MOVE[move]
    if function == "static":
        return {}
    if function == "dolly":
        inward = move == "push_in"
        if follows_subject and size is not None:
            return {
                "inward": inward,
                "sizeFrom": size,
                "sizeTo": size_step(size, -1 if inward else 1),
            }
        return {"inward": inward, "factor": DOLLY_FACTOR[move]}
    if function in ("pan", "tilt"):
        return {"degrees": PAN_TILT_DEG}
    if function == "handheld":
        return dict(HANDHELD_PARAMS)
    if function == "follow":
        return {"characterId": tracked, "distanceM": FOLLOW_WALK_M}
    if function == "truck":
        return {"distanceM": TRUCK_M}
    if function == "crane":
        if follows_subject and size is not None:
            return {
                "sizeFrom": size,
                "sizeTo": size_step(size, 2),
                "angleFrom": angle,
                "angleTo": "overhead",
            }
        return {"liftM": CRANE_LIFT_M}
    if function == "drone":
        return {
            "heightM": DRONE_HEIGHT_M.get(space, DRONE_HEIGHT_OTHER_M),
            "driftM": DRONE_DRIFT_M,
            "pitchDeg": DRONE_PITCH_DEG,
        }
    if follows_subject:
        heading = placement_params["headingDeg"]
        return {
            "headingFromDeg": rnd(heading - ORBIT_DEG / 2),
            "headingToDeg": rnd(heading + ORBIT_DEG / 2),
        }
    return {"degrees": ORBIT_DEG}


def build_card(doc: Any, shot_id: str, aspect: str) -> Card:
    """The staging card of shot `shot_id` at `aspect`.

    Raises ValueError, naming the shot order and the field, for a document that cannot be staged.
    """
    if aspect not in FRAME_BY_ASPECT:
        raise ValueError(f"Aspect '{aspect}' is not supported; use {', '.join(FRAME_BY_ASPECT)}")
    width, height = FRAME_BY_ASPECT[aspect]
    frame: dict[str, Any] = {
        "aspect": aspect,
        "width": width,
        "height": height,
        "fps": FPS,
        "sensorMm": SENSOR_MM_BY_ASPECT[aspect],
    }
    shot = _find_shot(doc, shot_id)
    order = shot.get("order")
    label = order if order is not None else shot_id
    staging = shot.get("staging")
    if not isinstance(staging, dict):
        raise ValueError(f"Shot {label}: staging is missing")
    _check_shape(doc, shot, staging, label)
    shot_type = _choose(staging.get("shotType"), SHOT_TYPES, "shotType", label)
    move = _choose(staging.get("move"), MOVES, "move", label)
    size = _choose(staging.get("size"), SIZES, "size", label, required=shot_type != "pov")
    stored_angle = _choose(staging.get("angle"), ANGLES, "angle", label, required=False)
    angle = stored_angle or "eye"
    duration_ms = shot.get("durationTargetMs")
    if (
        not isinstance(duration_ms, int | float)
        or isinstance(duration_ms, bool)
        or duration_ms <= 0
    ):
        raise ValueError(f"Shot {label}: durationTargetMs must be a positive number")
    try:
        frame_count = max(1, round(duration_ms * FPS / 1000))
    except (OverflowError, ValueError):
        raise ValueError(
            f"Shot {label}: durationTargetMs must be a finite number whose frame count fits"
        ) from None
    frame["frameCount"] = frame_count

    characters = {
        c["id"]: c for c in doc.get("characters") or [] if isinstance(c, dict) and "id" in c
    }
    story = doc["story"]
    chosen_set = next(
        (
            s
            for s in story.get("sets") or []
            if isinstance(s, dict) and s.get("id") == staging.get("setId")
        ),
        None,
    )
    _check_set(chosen_set, label)
    space = _choose((chosen_set or {}).get("space"), SPACES, "space", label, required=False)
    props = (chosen_set or {}).get("props") or []
    stage = build_stage(space, has_props=bool(props))
    fixed = fixed_pieces((chosen_set or {}).get("fixedPieces") or [], space)
    prop_list = prop_blocks(props, stage)

    rows = [r for r in staging.get("frame") or [] if r.get("characterId")]
    framed = [r["characterId"] for r in rows]
    cast: list[str] = []
    for m in shot.get("characterIds") or []:
        if characters.get(m, {}).get("kind") == "character" and m not in cast:
            cast.append(m)
    pov_id = staging.get("povId") if shot_type == "pov" else None
    if shot_type == "pov" and pov_id not in cast:
        raise ValueError(f"Shot {label}: a pov shot needs povId to be one of the shot's characters")
    built = [m for m in cast if m in framed] if shot_type == "establishing" else list(cast)
    for m in framed:
        if m not in built:
            raise ValueError(f"Shot {label}: {m} is framed but does not belong to the shot")
    spots, axis = seat_cast(built, (chosen_set or {}).get("leftCharacterId"), space)
    row_of = {r["characterId"]: r for r in rows}

    subject = next((r["characterId"] for r in rows if r.get("role") == "subject"), None)
    tracked = None
    if move == "track" and space != "car":
        tracked = subject or (framed[0] if framed else None)
    has_motion = {
        a.get("characterId")
        for a in staging.get("actions") or []
        if a.get("motion") is not None and isinstance(a.get("characterId"), str)
    }
    walker = tracked if tracked not in has_motion else None
    others = [(p[0], p[1]) for m, p in spots.items() if m != walker]
    table_boxes = []
    if stage.get("table"):
        (tx, ty, _), (tw, td, _) = stage["table"]["center"], stage["table"]["dimensions"]
        table_boxes.append(
            (tx - tw / 2 - 0.15, tx + tw / 2 + 0.15, ty - td / 2 - 0.15, ty + td / 2 + 0.15)
        )

    dummy_list: list[dict[str, Any]] = []
    for m in built:
        if m == pov_id:
            continue
        x, y, yaw, z0 = spots[m]
        entry = {
            "id": m,
            "label": nfc(str(characters.get(m, {}).get("name") or m)),
            "color": _color(characters, m),
            "foot": pt(x, y),
            "yawDeg": rnd(yaw),
            "z0": rnd(z0),
            "height": HUMAN_HEIGHT_M,
            "pose": "seated" if space == "car" else "standing",
            "inFrame": m in framed,
            "role": row_of.get(m, {}).get("role"),
            "walk": None,
        }
        if m == walker:
            d = walk_heading(spots[m], others, table_boxes)
            entry["pose"] = "walking"
            entry["yawDeg"] = rnd(yaw_toward(*d))
            entry["walk"] = {
                "from": pt(x, y),
                "to": pt(x + d[0] * FOLLOW_WALK_M, y + d[1] * FOLLOW_WALK_M),
            }
        dummy_list.append(entry)
    dummies = {d["id"]: d for d in dummy_list}

    placement = _placement(
        shot_type,
        size,
        angle,
        label,
        rows,
        row_of,
        subject,
        dummies,
        spots,
        pov_id,
        prop_list,
        stage,
        fixed,
    )
    follows_subject = placement["function"] == "single"
    motion_function = MOTION_BY_MOVE[move]
    motion = {
        "function": motion_function,
        "params": _motion_params(
            move, follows_subject, size, angle, space, placement["params"], tracked
        ),
    }

    subtitles, spans, line_ends = split_dialogue(shot, frame_count)
    to_camera = toward_camera(placement, dummies, spots)
    estimate = estimate_camera(placement, dummies, frame, motion_function)
    place_props_camera_side(
        prop_list, stage, staging.get("actions") or [], line_ends, dummies, estimate, to_camera
    )
    if shot_type == "insert" and prop_list:
        placement["params"]["center"] = list(prop_list[0]["center"])

    if pov_id is not None:
        x, y, yaw, _ = spots[pov_id]
        origin, facing = (x, y), yaw
    else:
        origin, facing = (0.0, 0.0), 180.0
    obstacles = [(d["foot"][0], d["foot"][1]) for d in dummy_list] + [
        (p["center"][0], p["center"][1]) for p in fixed + prop_list
    ]
    if stage.get("table"):
        obstacles.append((stage["table"]["center"][0], stage["table"]["center"][1]))
    extras = extras_for(staging.get("extras"), origin, facing, space, obstacles)

    if motion_function == "follow":
        follower = tracked
    elif follows_subject and motion_function in FOLLOWING_MOTIONS:
        follower = placement["params"]["characterId"]
    else:
        follower = None
    motions, speaking, warnings = build_motions(
        staging,
        dummies,
        spots,
        pov_id,
        prop_list,
        fixed,
        stage,
        line_ends,
        spans,
        frame_count,
        space,
        to_camera,
        estimate,
        size,
        follower,
    )
    return {
        "kind": CARD_KIND,
        "shot": {
            "id": shot["id"],
            "order": order,
            "title": nfc(str(shot.get("title") or "")).strip(),
            "shotType": shot_type,
            "size": size,
            "move": move,
            "angle": stored_angle,
            "extras": staging.get("extras"),
            "povId": pov_id,
        },
        "frame": frame,
        "stage": stage,
        "fixedPieces": fixed,
        "props": prop_list,
        "axis": axis,
        "dummies": dummy_list,
        "extras": extras,
        "camera": {
            "placement": placement,
            "motion": motion,
            "minDistanceM": CAMERA_MIN_DISTANCE_M,
        },
        "motions": motions,
        "speaking": speaking,
        "subtitles": subtitles,
        "warnings": warnings,
    }


def _placement(
    shot_type: str,
    size: str | None,
    angle: str,
    label: Any,
    rows: list[dict[str, Any]],
    row_of: dict[str, dict[str, Any]],
    subject: str | None,
    dummies: dict[str, dict[str, Any]],
    spots: dict[str, Any],
    pov_id: str | None,
    props: list[dict[str, Any]],
    stage: dict[str, Any],
    fixed: list[dict[str, Any]],
) -> dict[str, Any]:
    function = PLACEMENT_BY_SHOT_TYPE[shot_type]
    params: dict[str, Any]
    if shot_type == "single":
        if [r.get("role") for r in rows].count("subject") != 1:
            raise ValueError(f"Shot {label}: a single shot needs exactly one framed subject")
        assert subject is not None
        heading_deg, look = heading_from_gaze(row_of[subject], dummies[subject]["yawDeg"])
        position = row_of[subject].get("position")
        params = {
            "characterId": subject,
            "size": size,
            "angle": angle,
            "headingDeg": rnd(heading_deg),
            "look": look,
            "screenX": SCREEN_X.get(position, 0.0) if isinstance(position, str) else 0.0,
            "focalMm": None,
        }
    elif shot_type == "over_shoulder":
        foreground = next(
            (r["characterId"] for r in rows if r.get("role") == "over_shoulder"), None
        )
        if subject is None or foreground is None:
            raise ValueError(
                f"Shot {label}: an over_shoulder shot needs a subject and an over_shoulder "
                "character in frame"
            )
        a, b = dummies[foreground]["foot"], dummies[subject]["foot"]
        params = {
            "foregroundId": foreground,
            "subjectId": subject,
            "size": size,
            "side": 1 if b[0] - a[0] < 0 else -1,
        }
    elif shot_type in ("two_shot", "group"):
        if not rows:
            raise ValueError(f"Shot {label}: a {shot_type} shot needs a character in frame")
        assert size is not None
        points: list[list[float]] = []
        for r in rows:
            n = dummies[r["characterId"]]
            x, y = n["foot"]
            top, bottom = n["z0"] + n["height"], n["z0"] + SIZE_BOTTOM_RATIO[size] * n["height"]
            points += [
                pt(x, y, top),
                pt(x, y, bottom),
                pt(x - 0.25, y, (top + bottom) / 2),
                pt(x + 0.25, y, (top + bottom) / 2),
            ]
        params = {
            "points": points,
            "azimuthDeg": 0.0,
            "pitchDeg": GROUP_PITCH_DEG[angle],
            "margin": GROUP_MARGIN.get(size, GROUP_MARGIN_DEFAULT),
            "focalMm": SIZE_FOCAL_MM[size],
        }
    elif shot_type == "insert":
        assert size is not None
        if props:
            center, target = list(props[0]["center"]), props[0]["id"]
        elif stage.get("table"):
            table = stage["table"]
            center, target = (
                pt(table["center"][0], table["center"][1], table["dimensions"][2]),
                "table",
            )
        else:
            center, target = [0.0, 0.0, 0.0], "stage_center"
        params = {
            "center": center,
            "target": target,
            "width": INSERT_WIDTH_M[size],
            "focalMm": 50,
            "upDeg": 0.0,
        }
    elif shot_type == "establishing":
        assert size is not None
        params = {
            "points": stage_points(stage, size, fixed),
            "azimuthDeg": 0.0,
            "pitchDeg": max(GROUP_PITCH_DEG[angle], ESTABLISHING_PITCH_MIN_DEG),
            "margin": 0.04,
            "focalMm": SIZE_FOCAL_MM[size],
        }
    else:
        assert pov_id is not None
        x, y, yaw, z0 = spots[pov_id]
        eye_z = POV_GROUND_EYE_M if angle == "worm" else z0 + POV_EYE_RATIO * HUMAN_HEIGHT_M
        params = {
            "characterId": pov_id,
            "eye": pt(x, y, eye_z),
            "yawDeg": rnd(yaw),
            "pitchDeg": EYE_PITCH_DEG[angle],
            "focalMm": 24,
        }
    return {"function": function, "params": params}


def send_card(card: Card) -> Card:
    """The send version of a preview card: no labels, extras, subtitles or large flat panels.

    Keeps the camera, main cast, motions, speaking intervals, blocks, table, floor and frame. The
    input card is left unchanged.
    """
    send = copy.deepcopy(card)
    send["send"] = True
    for entry in send["dummies"] + send["fixedPieces"] + send["props"]:
        entry["label"] = None
    send["extras"]["people"] = []
    send["subtitles"] = []
    send["stage"]["flatPanels"] = False
    return send


def dumps(card: Card) -> str:
    """UTF-8 JSON: non-ASCII unescaped, indent 1, numeric arrays on one line, one final newline."""
    text = json.dumps(card, ensure_ascii=False, indent=1)
    flat = _ARRAY_OF_NUMBERS.sub(
        lambda m: "[" + ", ".join(x.strip() for x in m.group(1).split(",")) + "]", text
    )
    return flat + "\n"
