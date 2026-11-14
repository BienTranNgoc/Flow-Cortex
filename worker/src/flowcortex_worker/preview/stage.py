"""Stage, fixed pieces, props, seating and extras of the staging card, plus its planar geometry.

A dummy has its origin at the feet and faces -Y when its yaw is 0; a positive yaw turns
counter-clockwise seen from above.
"""

from __future__ import annotations

import copy
import math
import random
import unicodedata
from collections.abc import Callable, Iterator
from typing import Any

from flowcortex_worker.preview.vocabulary import (
    EXTRAS_COLOR,
    EXTRAS_COUNT,
    EXTRAS_SEED,
    FIXED_PIECE_RING_RADIUS_M,
    FIXED_PIECE_SIZE_M,
    FOLLOW_WALK_M,
    HIP_RATIO,
    HUMAN_HEIGHT_M,
    OPEN_GROUND_HALF_WIDTH_M,
    PERSON_CLEARANCE_M,
    PROP_CAMERA_SKEW_DEG,
    PROP_DISTANCE_M,
    PROP_SIDE_SPACING_M,
    PROP_SIZE_M,
    SEAT_HEIGHT_M,
    STAGE_EDGE_MARGIN_M,
    TABLE,
    TABLE_AVOID_MARGIN_M,
    TABLE_EDGE_INSET_M,
    TABLE_EDGE_M,
    TWO_PERSON_SPACING_M,
    WALK_CLEARANCE_M,
)

Vec2 = tuple[float, float]
Dummy = dict[str, Any]

PROP_AIMING_MOTIONS = ("reach", "pick_up", "point_at", "turn_to", "walk_to", "run_to")


def rnd(x: float) -> float:
    """Round to 4 decimals so a card serializes identically everywhere (-0.0 becomes 0.0)."""
    return round(x, 4) + 0.0


def pt(*xs: float) -> list[float]:
    return [rnd(x) for x in xs]


def yaw_toward(fx: float, fy: float) -> float:
    return math.degrees(math.atan2(fx, -fy))


def heading(yaw_deg: float) -> Vec2:
    y = math.radians(yaw_deg)
    return math.sin(y), -math.cos(y)


def rotate(v: Vec2, deg: float) -> Vec2:
    a = math.radians(deg)
    return v[0] * math.cos(a) - v[1] * math.sin(a), v[0] * math.sin(a) + v[1] * math.cos(a)


def unit(dx: float, dy: float) -> tuple[float, float, float]:
    """Unit vector and length; the zero vector for a length under 1 nm."""
    d = math.hypot(dx, dy)
    return (dx / d, dy / d, d) if d > 1e-9 else (0.0, 0.0, 0.0)


def nfc(text: str) -> str:
    return unicodedata.normalize("NFC", text)


def build_stage(space: str | None, has_props: bool) -> dict[str, Any]:
    if space == "room":
        return {
            "space": "room",
            "kind": "room_box",
            "width": 6.0,
            "depth": 5.0,
            "height": 3.0,
            "center": [0.0, 0.5],
            "table": copy.deepcopy(TABLE) if has_props else None,
        }
    if space == "car":
        return {
            "space": "car",
            "kind": "car",
            "seats": [{"center": [-0.4, 0.0]}, {"center": [0.4, 0.0]}],
            "steeringWheel": {"center": [0.4, -0.45, 0.95], "radius": 0.19},
        }
    if space == "outdoor":
        return {"space": "outdoor", "kind": "ground", "width": 30.0, "depth": 30.0}
    if space == "studio":
        return {
            "space": "studio",
            "kind": "cyclorama",
            "width": 8.0,
            "depth": 5.0,
            "cycloramaRadius": 1.5,
            "height": 4.0,
        }
    return {"space": None, "kind": "empty_ground", "width": 30.0, "depth": 30.0}


def walkable_area(stage: dict[str, Any]) -> tuple[float, float, float, float] | None:
    """Box (x0, x1, y0, y1) a dummy's feet may reach; None inside a car, where nobody walks."""
    kind, margin = stage["kind"], STAGE_EDGE_MARGIN_M
    if kind == "room_box":
        cx, cy = stage["center"]
        return (
            cx - stage["width"] / 2 + margin,
            cx + stage["width"] / 2 - margin,
            cy - stage["depth"] / 2 + margin,
            cy + stage["depth"] / 2 - margin,
        )
    if kind == "cyclorama":
        return (
            -stage["width"] / 2 + margin,
            stage["width"] / 2 - margin,
            -stage["depth"] / 2 + margin,
            stage["depth"] / 2 - stage["cycloramaRadius"] - margin,
        )
    if kind in ("ground", "empty_ground"):
        return (
            -stage["width"] / 2 + margin,
            stage["width"] / 2 - margin,
            -stage["depth"] / 2 + margin,
            stage["depth"] / 2 - margin,
        )
    return None


def fixed_pieces(names: list[str], space: str | None) -> list[dict[str, Any]]:
    """One grey block per named piece, spread over the back half-circle."""
    kept = [nfc(n) for n in names if isinstance(n, str) and n.strip()]
    radius = FIXED_PIECE_RING_RADIUS_M.get(space, FIXED_PIECE_RING_RADIUS_M[None])
    out = []
    for i, label in enumerate(kept):
        angle = 90.0 if len(kept) == 1 else 150.0 - i * 120.0 / (len(kept) - 1)
        a = math.radians(angle)
        out.append(
            {
                "label": label,
                "center": pt(radius * math.cos(a), radius * math.sin(a), FIXED_PIECE_SIZE_M[2] / 2),
                "dimensions": list(FIXED_PIECE_SIZE_M),
            }
        )
    return out


def prop_blocks(props: list[dict[str, Any]], stage: dict[str, Any]) -> list[dict[str, Any]]:
    """One small block per prop: on the table of a room with props, else on the ground."""
    usable = [p for p in props if isinstance(p, dict) and p.get("id")]
    table = stage.get("table")
    n = len(usable)
    if table:
        step = min(0.2, 0.8 / max(1, n))
        y, z = table["center"][1], table["dimensions"][2] + PROP_SIZE_M[2] / 2
    else:
        step = 0.35
        y, z = 0.6, PROP_SIZE_M[2] / 2
    out = []
    for i, p in enumerate(usable):
        x = (i - (n - 1) / 2) * step
        out.append(
            {
                "id": p["id"],
                "label": nfc(str(p.get("name") or p["id"])),
                "count": p.get("count"),
                "center": pt(x, y, z),
                "dimensions": list(PROP_SIZE_M),
                "heldAtStart": None,
            }
        )
    return out


def _trial_directions(d: Vec2) -> Iterator[Vec2]:
    """The direction itself, then rotated by 30 degree steps alternating sides."""
    yield d
    for deg in range(30, 181, 30):
        yield rotate(d, deg)
        if deg < 180:
            yield rotate(d, -deg)


def place_props_camera_side(
    props: list[dict[str, Any]],
    stage: dict[str, Any],
    actions: list[Any],
    line_ends: list[int],
    dummies: dict[str, Dummy],
    camera: dict[str, Any] | None,
    toward_camera: Callable[[float, float], Vec2],
) -> None:
    """Move each prop a character uses to that character's camera side, in place.

    A prop is "used" by the first character (by line mark, then action order) whose aiming motion
    targets it. Only characters inside the frame block a spot; a trial direction must stay on the
    camera side and on the same side of the camera axis as the character's facing. In a room with a
    table the table moves beside the first user and the used props sit on its near edge.
    """
    by_id = {p["id"]: p for p in props}
    uses = []
    for i, a in enumerate(actions):
        if (
            isinstance(a, dict)
            and isinstance(a.get("motion"), str)
            and a["motion"] in PROP_AIMING_MOTIONS
            and isinstance(a.get("targetId"), str)
            and a["targetId"] in by_id
            and isinstance(a.get("characterId"), str)
            and a["characterId"] in dummies
        ):
            k = a.get("afterLine")
            k = (
                min(k, len(line_ends) - 1)
                if isinstance(k, int) and not isinstance(k, bool) and k >= 0
                else 0
            )
            uses.append((line_ends[k], i, a))
    user_of: dict[str, str] = {}
    for _, _, a in sorted(uses, key=lambda v: (v[0], v[1])):
        user_of.setdefault(a["targetId"], a["characterId"])
    if not user_of:
        return

    def to_camera(who: str) -> Vec2:
        x, y = dummies[who]["foot"]
        if camera is not None:
            cx, cy, d = unit(camera["position"][0] - x, camera["position"][1] - y)
            if d:
                return cx, cy
        return toward_camera(x, y)

    def facing_dir(who: str) -> Vec2:
        c = to_camera(who)
        f = heading(dummies[who]["yawDeg"])
        bx, by, db = unit(f[0] + c[0], f[1] + c[1])
        return (bx, by) if db > 0.1 else c

    def usable_directions(who: str, base: Vec2) -> list[Vec2]:
        c, f = to_camera(who), heading(dummies[who]["yawDeg"])
        cross = c[0] * f[1] - c[1] * f[0]

        def ok(d: Vec2) -> bool:
            on_camera_side = (
                d[0] * c[0] + d[1] * c[1] >= math.cos(math.radians(PROP_CAMERA_SKEW_DEG)) - 1e-9
            )
            same_side = abs(cross) < 1e-6 or (c[0] * d[1] - c[1] * d[0]) * cross >= -1e-9
            return on_camera_side and same_side

        return [d for d in _trial_directions(base) if ok(d)] or [base]

    in_frame = {m: n for m, n in dummies.items() if n.get("inFrame", True)}

    def clear(p: Vec2, who: str) -> bool:
        return all(
            math.hypot(p[0] - n["foot"][0], p[1] - n["foot"][1]) >= PERSON_CLEARANCE_M - 1e-9
            for m, n in in_frame.items()
            if m != who
        )

    by_user: dict[str, list[dict[str, Any]]] = {}
    for p in props:
        if p["id"] in user_of:
            by_user.setdefault(user_of[p["id"]], []).append(p)
    table = stage.get("table")
    if table:
        _place_on_table(
            props, table, user_of, by_user, dummies, in_frame, usable_directions, facing_dir
        )
        return
    for who, ps in by_user.items():
        x, y = dummies[who]["foot"]
        base = facing_dir(who)
        for j, p in enumerate(ps):
            off = (j - (len(ps) - 1) / 2) * PROP_SIDE_SPACING_M
            spot = None
            for d in usable_directions(who, base):
                q = (
                    x + d[0] * PROP_DISTANCE_M - d[1] * off,
                    y + d[1] * PROP_DISTANCE_M + d[0] * off,
                )
                if clear(q, who):
                    spot = q
                    break
            if spot is None:
                spot = (
                    x + base[0] * PROP_DISTANCE_M - base[1] * off,
                    y + base[1] * PROP_DISTANCE_M + base[0] * off,
                )
            p["center"] = pt(spot[0], spot[1], PROP_SIZE_M[2] / 2)


def _place_on_table(
    props: list[dict[str, Any]],
    table: dict[str, Any],
    user_of: dict[str, str],
    by_user: dict[str, list[dict[str, Any]]],
    dummies: dict[str, Dummy],
    in_frame: dict[str, Dummy],
    usable_directions: Callable[[str, Vec2], list[Vec2]],
    facing_dir: Callable[[str], Vec2],
) -> None:
    first_user = next(user_of[p["id"]] for p in props if p["id"] in user_of)
    x, y = dummies[first_user]["foot"]
    nx, ny = table["dimensions"][0] / 2, table["dimensions"][1] / 2

    def gap_to_table(cx: float, cy: float) -> float:
        return math.hypot(max(cx - nx - x, 0.0, x - cx - nx), max(cy - ny - y, 0.0, y - cy - ny))

    center: tuple[float, float] | None = None
    first_dir: Vec2 | None = None
    for d in usable_directions(first_user, facing_dir(first_user)):
        lo, hi = TABLE_EDGE_M, TABLE_EDGE_M + nx + ny
        for _ in range(40):
            mid = (lo + hi) / 2
            lo, hi = (
                (mid, hi)
                if gap_to_table(x + d[0] * mid, y + d[1] * mid) < TABLE_EDGE_M
                else (lo, mid)
            )
        t = (x + d[0] * hi, y + d[1] * hi)
        if all(
            not (
                t[0] - nx - TABLE_AVOID_MARGIN_M < n["foot"][0] < t[0] + nx + TABLE_AVOID_MARGIN_M
                and t[1] - ny - TABLE_AVOID_MARGIN_M
                < n["foot"][1]
                < t[1] + ny + TABLE_AVOID_MARGIN_M
            )
            for n in in_frame.values()
        ):
            center, first_dir = t, d
            break
    if center is None:
        center = (table["center"][0], table["center"][1])
    cx0, cy0 = center
    dx, dy = cx0 - table["center"][0], cy0 - table["center"][1]
    table["center"] = pt(cx0, cy0, table["center"][2])
    for p in props:
        if p["id"] not in user_of:
            p["center"] = pt(p["center"][0] + dx, p["center"][1] + dy, p["center"][2])

    def enter_table(ux: float, uy: float, r: Vec2) -> Vec2 | None:
        """Where the ray from (ux, uy) along r meets the table top, inset from the edge."""
        t0, t1 = -math.inf, math.inf
        for o, dd, lo, hi in (
            (ux, r[0], cx0 - nx, cx0 + nx),
            (uy, r[1], cy0 - ny, cy0 + ny),
        ):
            if abs(dd) < 1e-12:
                if not lo <= o <= hi:
                    return None
                continue
            a_, b_ = (lo - o) / dd, (hi - o) / dd
            t0, t1 = max(t0, min(a_, b_)), min(t1, max(a_, b_))
        if t0 > t1 or t1 < 0:
            return None
        t = max(t0, 0.0) + TABLE_EDGE_INSET_M
        return (
            max(cx0 - nx + TABLE_EDGE_INSET_M, min(cx0 + nx - TABLE_EDGE_INSET_M, ux + r[0] * t)),
            max(cy0 - ny + TABLE_EDGE_INSET_M, min(cy0 + ny - TABLE_EDGE_INSET_M, uy + r[1] * t)),
        )

    for who, ps in by_user.items():
        ux, uy = dummies[who]["foot"]
        ray = (
            first_dir
            if who == first_user and first_dir is not None
            else usable_directions(who, facing_dir(who))[0]
        )
        g = enter_table(ux, uy, ray)
        if g is None:
            g = (
                max(cx0 - nx + TABLE_EDGE_INSET_M, min(cx0 + nx - TABLE_EDGE_INSET_M, ux)),
                max(cy0 - ny + TABLE_EDGE_INSET_M, min(cy0 + ny - TABLE_EDGE_INSET_M, uy)),
            )
        gx, gy = g
        vx, vy, _ = unit(gx - ux, gy - uy)
        for j, p in enumerate(ps):
            off = (j - (len(ps) - 1) / 2) * PROP_SIDE_SPACING_M
            px = max(
                cx0 - nx + TABLE_EDGE_INSET_M, min(cx0 + nx - TABLE_EDGE_INSET_M, gx - vy * off)
            )
            py = max(
                cy0 - ny + TABLE_EDGE_INSET_M, min(cy0 + ny - TABLE_EDGE_INSET_M, gy + vx * off)
            )
            p["center"] = pt(px, py, p["center"][2])


def seat_cast(
    ids: list[str], left_id: str | None, space: str | None
) -> tuple[dict[str, tuple[float, float, float, float]], dict[str, str] | None]:
    """{id: (x, y, yaw, z0)} and the left/right axis when exactly two people stand.

    The left character stands west (frame left); two people face each other 1.2 m apart, three or
    more form an arc facing its centre; in a car everyone sits.
    """
    if left_id in ids:
        ids = [left_id] + [i for i in ids if i != left_id]
    n = len(ids)
    axis = {"left": ids[0], "right": ids[1]} if n == 2 else None
    if space == "car":
        z0 = SEAT_HEIGHT_M - HIP_RATIO * HUMAN_HEIGHT_M
        seats = [(0.4, 0.0)] if n == 1 else [(-0.4, 0.0), (0.4, 0.0)]
        seats += [(-0.45 + 0.45 * (k % 3), 0.9) for k in range(max(0, n - 2))]
        return {m: (x, y, 0.0, z0) for m, (x, y) in zip(ids, seats, strict=False)}, axis
    if n == 1:
        return {ids[0]: (0.0, 0.0, 0.0, 0.0)}, axis
    if n == 2:
        h = TWO_PERSON_SPACING_M / 2
        return {
            ids[0]: (-h, 0.0, yaw_toward(1, 0), 0.0),
            ids[1]: (h, 0.0, yaw_toward(-1, 0), 0.0),
        }, axis
    r = max(1.5, 0.5 * n)
    spots = {}
    for i, m in enumerate(ids):
        a = math.radians(150.0 - i * 120.0 / (n - 1))
        x, y = r * math.cos(a), r * math.sin(a)
        spots[m] = (x, y, yaw_toward(-x, -y), 0.0)
    return spots, axis


def walk_heading(
    spot: tuple[float, float, float, float],
    others: list[Vec2],
    blockers: list[tuple[float, float, float, float]],
) -> Vec2:
    """Direction of the subject's 2 m walk: straight ahead, else a left turn, a right turn, back.

    A direction is refused when its path passes within 0.7 m of another person or inside a blocker
    box; when none is clear the subject walks straight ahead.
    """
    f = heading(spot[2])
    for deg in (0, 90, -90, 180):
        d = rotate(f, deg)
        ok = True
        for k in range(1, 21):
            px, py = (
                spot[0] + d[0] * FOLLOW_WALK_M * k / 20,
                spot[1] + d[1] * FOLLOW_WALK_M * k / 20,
            )
            if any(math.hypot(px - ox, py - oy) < WALK_CLEARANCE_M for ox, oy in others) or any(
                x0 <= px <= x1 and y0 <= py <= y1 for x0, x1, y0, y1 in blockers
            ):
                ok = False
                break
        if ok:
            return d
    return f


def extras_for(
    level: str | None,
    origin: Vec2,
    facing_yaw: float,
    space: str | None,
    obstacles: list[Vec2],
) -> dict[str, Any]:
    """Unlabelled grey dummies in the background strip ahead of the camera, fixed seed."""
    count = EXTRAS_COUNT.get(level or "none", 0)
    base: dict[str, Any] = {"level": level, "color": list(EXTRAS_COLOR), "height": HUMAN_HEIGHT_M}
    if not count:
        return {**base, "people": []}
    reach, lateral = {"room": ((1.3, 2.6), 2.6), "studio": ((1.2, 2.0), 3.0)}.get(
        space or "", ((3.0, 9.0), 7.0)
    )
    ahead = heading(facing_yaw)
    right = (ahead[1], -ahead[0])
    rd = random.Random(EXTRAS_SEED)
    spots: list[Vec2] = []
    yaws = []
    tries = 0
    while len(spots) < count and tries < 4000:
        tries += 1
        a, b = rd.uniform(*reach), rd.uniform(-lateral, lateral)
        x = origin[0] + ahead[0] * a + right[0] * b
        y = origin[1] + ahead[1] * a + right[1] * b
        if any(math.hypot(x - ox, y - oy) < 0.55 for ox, oy in obstacles + spots):
            continue
        spots.append((x, y))
        yaws.append(rd.uniform(-180.0, 180.0))
    return {
        **base,
        "people": [
            {"foot": pt(x, y), "yawDeg": rnd(w)} for (x, y), w in zip(spots, yaws, strict=True)
        ],
    }


def stage_points(
    stage: dict[str, Any], size: str | None, fixed: list[dict[str, Any]]
) -> list[list[float]]:
    """Points an establishing shot must frame: stage corners (floor, top) and fixed pieces."""
    kind = stage["kind"]
    if kind == "room_box":
        x0, x1, y0, y1, z1 = -3.0, 3.0, -2.0, 3.0, 3.0
    elif kind == "car":
        x0, x1, y0, y1, z1 = -0.9, 0.9, -0.9, 1.3, 1.4
    elif kind == "cyclorama":
        x0, x1, y0, y1, z1 = -4.0, 4.0, -2.5, 2.5, 2.0
    else:
        h = OPEN_GROUND_HALF_WIDTH_M.get(size or "LS", 6.0)
        x0, x1, y0, y1, z1 = -h, h, -h, h, 2.0
    pts = [pt(x, y, z) for z in (0.0, z1) for x in (x0, x1) for y in (y0, y1)]
    return pts + [list(p["center"]) for p in fixed]
