"""Dummy motions, speaking intervals and warnings of a staging card.

Motions come from the shot's actions that carry a `motion` code; the action text is never read.
Each character works through its motions in order, a motion starts at its `afterLine` mark and no
motion ends after the last frame. A motion that cannot be performed is dropped and one that does not
fit is shortened, each with a warning `{kind, characterId, motion, reason}`; the card is still
built.
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass
from itertools import pairwise
from typing import Any

from flowcortex_worker.preview.camera import project
from flowcortex_worker.preview.stage import (
    heading,
    pt,
    rnd,
    rotate,
    unit,
    walkable_area,
    yaw_toward,
)
from flowcortex_worker.preview.vocabulary import (
    FPS,
    HIP_RATIO,
    HUMAN_HEIGHT_M,
    POV_EYE_RATIO,
    SEAT_HEIGHT_M,
)

Vec2 = tuple[float, float]
Obstacle = tuple[Any, ...]
Motion = dict[str, Any]
Warning = dict[str, Any]

MOTION_CODES = (
    "walk_to",
    "run_to",
    "turn_to",
    "sit_down",
    "stand_up",
    "reach",
    "pick_up",
    "hand_over",
    "point_at",
    "raise_arms",
    "nod",
    "fall",
    "hug",
    "walk_away",
)
TARGET_REQUIRED = ("turn_to", "reach", "pick_up", "hand_over", "hug")
TARGET_IGNORED = ("sit_down", "stand_up", "raise_arms", "nod", "fall")
WALK_SPEED, RUN_SPEED = 1.2, 2.4
STOP_DISTANCE_M = 0.6
CAMERA_ADVANCE_M = 1.5
TURN_SEC = 0.3
GESTURE_SEC = {
    "sit_down": 1.2,
    "stand_up": 1.2,
    "reach": 1.2,
    "pick_up": 1.6,
    "hand_over": 1.6,
    "point_at": 1.6,
    "raise_arms": 1.6,
    "nod": 0.8,
    "fall": 1.0,
    "hug": 2.0,
}
REACH_DISTANCE_M = {"reach": 0.55, "pick_up": 0.35, "hand_over": 0.75}
REACH_TOLERANCE_M = 0.15
HANDOVER_RATIO = {"pick_up": 0.45, "hand_over": 0.5}
MIN_FIT_SEC = 0.3
TOUCH_HEIGHT = {"hand": 0.45, "arm": 0.62, "shoulder": 0.80, "other": 0.70}
CHEST_HEIGHT = 0.70
HANDOVER_HEIGHT = 0.60
HELD_PROP_HEIGHT_M = 1.0
STOOL_SIZE_M = [0.4, 0.4, 0.4]
FALLEN_ORIGIN_Z_M = 0.13
CAMERA_SAFETY_M = 0.6
FRAME_EDGE = 0.04
MIN_WALK_M = 0.2
PERSON_CLEARANCE_M = 0.5
TARGET_CLEARANCE_M = 0.58
BLOCK_PADDING_M = 0.25
ALREADY_FACING_DEG = 15.0
NOD_AMPLITUDE = {
    "ECU": 0.2,
    "CU": 0.25,
    "MCU": 0.32,
    "MS": 0.45,
    "MLS": 0.52,
    "LS": 0.62,
    "EWS": 0.7,
}
MIN_SPEED_RATIO = 0.5
HUG_DISTANCE_M = 0.4
BACK_STEP_M = 0.5
BACK_SPEED = 0.6
TURN_AWAY_SEC = 0.3
HEAD_HEIGHT_RATIO = 0.925
ROTATION_STEP_DEG = 15


@dataclass
class _Leg:
    """What one motion does between its start pose and its end pose."""

    dest: Vec2
    yaw: float
    walk_sec: float = 0.0
    path: list[list[float]] | None = None
    speed_ratio: float = 1.0
    duration: float = 0.0
    target: dict[str, Any] | None = None
    back_sec: float = 0.0


def _angle_gap(a: float, b: float) -> float:
    return abs((a - b + 180.0) % 360.0 - 180.0)


def _clearance(p: Vec2, obstacles: list[Obstacle]) -> float:
    """Smallest gap between a point and the obstacles (negative = inside one).

    An obstacle is ("round", x, y, radius) or ("box", x0, x1, y0, y1).
    """
    gap = math.inf
    for o in obstacles:
        if o[0] == "round":
            gap = min(gap, math.hypot(p[0] - o[1], p[1] - o[2]) - o[3])
        else:
            dx, dy = max(o[1] - p[0], 0.0, p[0] - o[2]), max(o[3] - p[1], 0.0, p[1] - o[4])
            outside = math.hypot(dx, dy)
            gap = min(
                gap,
                outside
                if outside > 0
                else -min(p[0] - o[1], o[2] - p[0], p[1] - o[3], o[4] - p[1]),
            )
    return gap


def _is_clear(path: list[Vec2], obstacles: list[Obstacle]) -> bool:
    """A polyline that never enters an obstacle, probed every 5 cm."""
    for a, b in pairwise(path):
        n = max(1, int(math.hypot(b[0] - a[0], b[1] - a[1]) / 0.05))
        for k in range(n + 1):
            probe = (a[0] + (b[0] - a[0]) * k / n, a[1] + (b[1] - a[1]) * k / n)
            if _clearance(probe, obstacles) < 0:
                return False
    return True


def _plan_path(p0: Vec2, p1: Vec2, obstacles: list[Obstacle]) -> tuple[list[list[float]], bool]:
    """Straight when clear, else a quadratic arc round the less obstructed side, else straight.

    An obstacle already containing the start does not count: the walker steps out of it. Returns
    the path and whether it is clear.
    """
    obstacles = [o for o in obstacles if _clearance(p0, [o]) >= 0]
    if not obstacles or _is_clear([p0, p1], obstacles):
        return [pt(*p0), pt(*p1)], True
    ux, uy, _ = unit(p1[0] - p0[0], p1[1] - p0[1])
    nx, ny = -uy, ux
    gx, gy = (p0[0] + p1[0]) / 2, (p0[1] + p1[1]) / 2
    samples = [
        (p0[0] + (p1[0] - p0[0]) * k / 20, p0[1] + (p1[1] - p0[1]) * k / 20) for k in range(21)
    ]
    nearest = min(samples, key=lambda p: _clearance(p, obstacles))
    centers = [o for o in obstacles if o[0] == "round"] + [
        ("round", (o[1] + o[2]) / 2, (o[3] + o[4]) / 2, 0.0) for o in obstacles if o[0] == "box"
    ]
    blocker = min(centers, key=lambda o: math.hypot(nearest[0] - o[1], nearest[1] - o[2]))
    left = (blocker[1] - gx) * nx + (blocker[2] - gy) * ny > 0
    for bulge in (0.4, 0.7, 1.0, 1.4, 1.9, 2.5, 3.2):
        for side in (-1, 1) if left else (1, -1):
            qx, qy = gx + nx * bulge * side, gy + ny * bulge * side
            curve = [
                (
                    (1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * qx + t**2 * p1[0],
                    (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * qy + t**2 * p1[1],
                )
                for t in (i / 16 for i in range(17))
            ]
            if _is_clear(curve, obstacles):
                return [pt(*q) for q in curve], True
    return [pt(*p0), pt(*p1)], False


def _path_length(path: list[list[float]]) -> float:
    return sum(math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in pairwise(path))


def _stop_point(
    center: Vec2,
    radius: float,
    start: Vec2,
    obstacles: list[Obstacle],
    path_obstacles: list[Obstacle],
) -> Vec2:
    """The point `radius` from `center` on the walker's side.

    When blocked, or when no clear path leads there from `start`, it turns round `center` in 15
    degree steps (nearest to the walker first): the first spot with a path, else the first free
    spot, else the straight-line spot.
    """
    ux, uy, d = unit(start[0] - center[0], start[1] - center[1])
    if not d:
        ux, uy = 0.0, -1.0
    trials: list[Vec2] = []
    for deg in range(0, 181, ROTATION_STEP_DEG):
        pair = []
        for sign in (1, -1) if deg else (1,):
            vx, vy = rotate((ux, uy), sign * deg)
            pair.append((center[0] + vx * radius, center[1] + vy * radius))
        trials += sorted(pair, key=lambda p: math.hypot(p[0] - start[0], p[1] - start[1]))
    free = [p for p in trials if _clearance(p, obstacles) >= 0]
    for p in free:
        if _plan_path(start, p, path_obstacles)[1]:
            return p
    return free[0] if free else trials[0]


def speaking_intervals(
    spans: dict[int, tuple[str | None, int, int]], dummies: dict[str, Any]
) -> list[dict[str, Any]]:
    """Seconds each spoken line is delivered by a character that has a dummy."""
    return [
        {"characterId": who, "startSec": rnd(start / FPS), "endSec": rnd(end / FPS)}
        for _, (who, start, end) in sorted(spans.items())
        if isinstance(who, str) and who in dummies
    ]


def _origin_z(pose: str, state: dict[str, Any]) -> float:
    """Height of a dummy's origin: standing keeps the card's z0, seated puts the hip on the seat."""
    if pose == "seated":
        return float(SEAT_HEIGHT_M - HIP_RATIO * state["height"])
    if pose == "fallen":
        return FALLEN_ORIGIN_Z_M
    return float(state["z0"])


def _head_in_frame(camera: dict[str, Any], x: float, y: float, z0: float, height: float) -> bool:
    sx, sy, depth = project(camera, (x, y, z0 + HEAD_HEIGHT_RATIO * height))
    mh, mv = camera["marginH"], camera["marginV"]
    return (
        depth > 0
        and FRAME_EDGE + mh <= sx <= 1 - FRAME_EDGE - mh
        and FRAME_EDGE + mv <= sy <= 1 - FRAME_EDGE - mv
    )


class _Planner:
    def __init__(
        self,
        actions: list[Any],
        dummies: dict[str, dict[str, Any]],
        spots: dict[str, Any],
        pov_id: str | None,
        props: list[dict[str, Any]],
        blocks: list[Obstacle],
        line_ends: list[int],
        frame_count: int,
        space: str | None,
        to_camera: Any,
        camera: dict[str, Any] | None,
        size: str | None,
        follower: str | None,
        walkable: tuple[float, float, float, float] | None,
    ) -> None:
        self.dummies, self.spots, self.pov_id = dummies, spots, pov_id
        self.props = {p["id"]: p for p in props}
        self.blocks, self.space = blocks, space
        self.to_camera, self.camera, self.size = to_camera, camera, size
        self.follower, self.walkable = follower, walkable
        self.length = frame_count / FPS
        self.state: dict[str, dict[str, Any]] = {
            m: {
                "foot": list(d["foot"]),
                "yaw": d["yawDeg"],
                "pose": "seated" if d["pose"] == "seated" else "standing",
                "free": 0.0,
                "z0": d["z0"],
                "height": d["height"],
            }
            for m, d in dummies.items()
        }
        self.hand: dict[str, str | None] = {m: None for m in self.state}
        self.held: dict[str, str | None] = {p: None for p in self.props}
        self.ever_held: set[str] = set()
        self.ever_carried: set[str] = set()
        self.jobs: list[tuple[float, int, dict[str, Any]]] = []
        for i, a in enumerate(actions):
            if isinstance(a, dict) and a.get("motion") is not None:
                k = a.get("afterLine")
                k = (
                    min(k, len(line_ends) - 1)
                    if isinstance(k, int) and not isinstance(k, bool) and k >= 0
                    else 0
                )
                self.jobs.append((line_ends[k] / FPS, i, a))
        self.jobs.sort(key=lambda v: (v[0], v[1]))
        self.motions: list[Motion] = []
        self.warnings: list[Warning] = []

    def obstacles(self, who: str, target: str | None = None) -> list[Obstacle]:
        """Other people (not the walker or target) and the blocks, minus a prop's own block."""
        out: list[Obstacle] = [
            ("round", s["foot"][0], s["foot"][1], PERSON_CLEARANCE_M)
            for m, s in self.state.items()
            if m not in (who, target)
        ]
        if target in self.props:
            x, y = self.props[target]["center"][:2]
            return out + [b for b in self.blocks if not (b[1] <= x <= b[2] and b[3] <= y <= b[4])]
        return out + list(self.blocks)

    def inside(self, x: float, y: float) -> bool:
        w = self.walkable
        return w is None or (w[0] - 1e-9 <= x <= w[1] + 1e-9 and w[2] - 1e-9 <= y <= w[3] + 1e-9)

    def body_point(self, who: str, ratio: float) -> list[float]:
        s = self.state[who]
        if s["pose"] == "fallen":
            z = FALLEN_ORIGIN_Z_M
        else:
            z = _origin_z(s["pose"], s) + ratio * s["height"]
        return pt(s["foot"][0], s["foot"][1], z)

    def camera_xy(self) -> list[float] | None:
        return pt(*self.camera["position"]) if self.camera else None

    def drop(self, who: Any, code: Any, reason: str) -> None:
        self.warnings.append(
            {
                "kind": "dropped",
                "characterId": who if isinstance(who, str) else None,
                "motion": code if isinstance(code, str) else json.dumps(code, ensure_ascii=False),
                "reason": reason,
            }
        )

    def next_start(self, index: int, who: str) -> float:
        return next(
            (j[0] for j in self.jobs[index + 1 :] if j[2].get("characterId") == who), self.length
        )

    def resolve_target(
        self, code: str, who: str, action: dict[str, Any]
    ) -> tuple[dict[str, Any] | None, str | None]:
        """The motion's target, or the reason it cannot be used."""
        target = None if code in TARGET_IGNORED else action.get("targetId")
        if target is None:
            return None, None
        if not isinstance(target, str):
            target = json.dumps(target, ensure_ascii=False)
        touch = action.get("touch")
        zone = touch.get("zone") if isinstance(touch, dict) else None
        ratio = {
            "reach": TOUCH_HEIGHT.get(zone, TOUCH_HEIGHT["other"])
            if isinstance(zone, str)
            else TOUCH_HEIGHT["other"],
            "hand_over": HANDOVER_HEIGHT,
            "walk_to": 0.0,
            "run_to": 0.0,
            "hug": TOUCH_HEIGHT["shoulder"],
            "walk_away": 0.0,
        }.get(code, CHEST_HEIGHT)
        if target == who:
            return None, "self_target"
        if target in self.state:
            return {
                "kind": "person",
                "id": target,
                "position": self.body_point(target, ratio),
            }, None
        if target == self.pov_id and target in self.spots:
            x, y, _, z0 = self.spots[target]
            return {
                "kind": "person",
                "id": target,
                "position": pt(x, y, z0 + POV_EYE_RATIO * HUMAN_HEIGHT_M),
                "behindCamera": True,
            }, None
        if target in self.props:
            holder = self.held[target]
            position = (
                list(self.props[target]["center"])
                if holder is None
                else pt(*self.state[holder]["foot"], HELD_PROP_HEIGHT_M)
            )
            return {"kind": "prop", "id": target, "position": position}, None
        return None, "target_missing"

    def check_target(self, code: str, target: dict[str, Any] | None) -> str | None:
        if code in TARGET_REQUIRED and target is None:
            return "target_required"
        if target is None:
            return None
        person = target["kind"] == "person" and not target.get("behindCamera")
        checks = {
            "hand_over": (not person, "hand_over_needs_person"),
            "pick_up": (target["kind"] != "prop", "pick_up_needs_prop"),
            "reach": (bool(target.get("behindCamera")), "target_behind_camera"),
            "hug": (not person, "hug_needs_person"),
        }
        bad, reason = checks.get(code, (False, ""))
        if bad:
            return reason
        if code == "hug" and not self.dummies[target["id"]]["inFrame"]:
            return "target_out_of_frame"
        return None

    def check_pose(self, code: str, who: str, target: dict[str, Any] | None) -> str | None:
        pose = self.state[who]["pose"]
        if pose == "fallen" and code != "stand_up":
            return "lying_down"
        if code == "stand_up" and self.space == "car":
            return "cannot_stand_in_car"
        if code == "stand_up" and pose == "standing":
            return "already_standing"
        if code == "sit_down" and pose == "seated":
            return "already_seated"
        if code == "hug" and self.space == "car":
            return "cannot_hug_in_car"
        if code in ("walk_to", "run_to", "fall", "hug", "walk_away") and pose == "seated":
            return "seated"
        if code == "hug" and target and self.state[target["id"]]["pose"] in ("seated", "fallen"):
            return "target_not_standing"
        return None

    def check_hands(
        self, code: str, who: str, target: dict[str, Any] | None, prop: str | None
    ) -> tuple[str | None, bool]:
        """Reason the hands cannot do it, and whether `who` already held the prop at the start."""
        if code == "pick_up" and target:
            if self.held[target["id"]] is not None:
                return "prop_held_by_other", False
            if self.hand[who] is not None:
                return "hand_full", False
        if code == "hand_over" and target and prop:
            if self.held[prop] not in (who, None):
                return "prop_held_by_other", False
            held_from_start = self.held[prop] is None
            if held_from_start and (
                prop in self.ever_held or self.hand[who] is not None or who in self.ever_carried
            ):
                return "not_holding_prop", False
            if self.hand[target["id"]] is not None:
                return "hand_full", False
            if self.state[target["id"]]["pose"] == "fallen":
                return "target_not_standing", False
            return None, held_from_start
        return None, False

    def run(self) -> None:
        for index, (at, _, action) in enumerate(self.jobs):
            self.plan_one(index, at, action)

    def plan_one(self, index: int, at: float, action: dict[str, Any]) -> None:
        code, who = action.get("motion"), action.get("characterId")
        if code not in MOTION_CODES:
            self.drop(who, code, "unknown_motion")
            return
        if not isinstance(who, str) or who not in self.state:
            self.drop(who, code, "no_dummy")
            return
        target, reason = self.resolve_target(code, who, action)
        reason = reason or self.check_target(code, target)
        prop = None
        if not reason and code == "hand_over":
            ids = action.get("propIds")
            prop = ids[0] if isinstance(ids, list) and ids and isinstance(ids[0], str) else None
            if prop not in self.props:
                prop, reason = None, "hand_over_needs_prop"
        reason = reason or self.check_pose(code, who, target)
        held_from_start = False
        if not reason:
            reason, held_from_start = self.check_hands(code, who, target, prop)
        if reason:
            self.drop(who, code, reason)
            return
        s = self.state[who]
        leg = self.leg(code, who, target, index, at)
        if isinstance(leg, str):
            self.drop(who, code, leg)
            return
        self.commit(code, who, s, leg, at, prop, held_from_start)

    def leg(
        self, code: str, who: str, target: dict[str, Any] | None, index: int, at: float
    ) -> _Leg | str:
        s = self.state[who]
        x0, y0 = s["foot"]
        tx, ty = (target["position"][0], target["position"][1]) if target else (x0, y0)
        ux, uy, dist = unit(tx - x0, ty - y0)
        target_id = target["id"] if target else None
        obstacles = self.obstacles(who, target_id)
        path_obstacles = obstacles + (
            [("round", tx, ty, TARGET_CLEARANCE_M)] if target_id in self.state else []
        )
        if code in ("walk_to", "run_to"):
            return self.leg_walk(code, who, target, index, at, dist, obstacles, path_obstacles)
        if code == "hug":
            return self.leg_hug(who, target, dist, obstacles)
        if code == "walk_away":
            return self.leg_walk_away(who, target, index, at, obstacles)
        if code == "turn_to":
            yaw = yaw_toward(ux, uy) if dist > 1e-9 else s["yaw"]
            if _angle_gap(yaw, s["yaw"]) < ALREADY_FACING_DEG:
                return "already_facing"
            turn = 0.4 + 0.6 * _angle_gap(yaw, s["yaw"]) / 180.0
            return _Leg((x0, y0), yaw, duration=turn, target=target)
        if code in ("reach", "pick_up", "hand_over", "point_at"):
            return self.leg_gesture(code, who, target, dist, obstacles, path_obstacles)
        return _Leg((x0, y0), s["yaw"], duration=GESTURE_SEC[code])

    def leg_walk(
        self,
        code: str,
        who: str,
        target: dict[str, Any] | None,
        index: int,
        at: float,
        dist: float,
        obstacles: list[Obstacle],
        path_obstacles: list[Obstacle],
    ) -> _Leg | str:
        s = self.state[who]
        x0, y0 = s["foot"]
        speed = WALK_SPEED if code == "walk_to" else RUN_SPEED
        dest: Vec2 = (x0, y0)
        yaw, time_for_walk, fill = s["yaw"], 0.0, False
        if target is None:
            # Walk towards the camera until the walker's next motion or the end of the shot, but
            # keep the camera gap and the head in frame, unless the camera follows the walker.
            if self.camera:
                vx, vy, d0 = unit(self.camera["position"][0] - x0, self.camera["position"][1] - y0)
            else:
                vx, vy, d0 = 0.0, 0.0, 0.0
            if not d0:
                (vx, vy), d0 = self.to_camera(x0, y0), math.inf
            follows = self.follower == who
            z = _origin_z(s["pose"], s)
            keep_in_frame = (
                not follows
                and self.camera is not None
                and _head_in_frame(self.camera, x0, y0, z, s["height"])
            )
            time_for_walk = max(
                0.0, min(self.next_start(index, who), self.length) - max(at, s["free"]) - TURN_SEC
            )
            fill = speed * time_for_walk > CAMERA_ADVANCE_M
            reach = max(CAMERA_ADVANCE_M, speed * time_for_walk)
            if not follows:
                reach = min(reach, d0 - CAMERA_SAFETY_M)
            while reach >= MIN_WALK_M:
                ex, ey = x0 + vx * reach, y0 + vy * reach
                if (
                    (
                        not keep_in_frame
                        or (
                            self.camera is not None
                            and _head_in_frame(self.camera, ex, ey, z, s["height"])
                        )
                    )
                    and _clearance((ex, ey), obstacles) >= 0
                    and self.inside(ex, ey)
                ):
                    break
                reach = round(reach - 0.05, 4)
            if reach < MIN_WALK_M:
                return "camera_too_close"
            dest = (x0 + vx * reach, y0 + vy * reach)
            yaw = yaw_toward(vx, vy)
            target = {
                "kind": "camera",
                "id": None,
                "position": pt(dest[0], dest[1], 0.0),
                "cameraPosition": self.camera_xy(),
            }
        elif dist > 1e-9:
            if dist > STOP_DISTANCE_M:
                dest = _stop_point(
                    (target["position"][0], target["position"][1]),
                    STOP_DISTANCE_M,
                    (x0, y0),
                    obstacles,
                    path_obstacles,
                )
            vx, vy, _ = unit(target["position"][0] - dest[0], target["position"][1] - dest[1])
            yaw = yaw_toward(vx, vy)
        dest = (pt(*dest)[0], pt(*dest)[1])
        path, walk_sec, ratio = None, 0.0, 1.0
        if dest != (x0, y0):
            path = _plan_path((x0, y0), dest, path_obstacles)[0]
            length = _path_length(path)
            real = max(speed * MIN_SPEED_RATIO, length / time_for_walk) if fill else speed
            ratio = real / speed
            walk_sec = length / real
        return _Leg(dest, yaw, walk_sec, path, ratio, walk_sec + TURN_SEC, target)

    def leg_hug(
        self, who: str, target: dict[str, Any] | None, dist: float, obstacles: list[Obstacle]
    ) -> _Leg:
        assert target is not None
        s = self.state[who]
        x0, y0 = s["foot"]
        tx, ty = target["position"][0], target["position"][1]
        dest: Vec2 = (x0, y0)
        path, walk_sec = None, 0.0
        if dist > HUG_DISTANCE_M + REACH_TOLERANCE_M:
            hug_obstacles = [*obstacles, ("round", tx, ty, HUG_DISTANCE_M - 0.02)]
            stop = _stop_point((tx, ty), HUG_DISTANCE_M, (x0, y0), obstacles, hug_obstacles)
            dest = (pt(*stop)[0], pt(*stop)[1])
            path = _plan_path((x0, y0), dest, hug_obstacles)[0]
            walk_sec = _path_length(path) / WALK_SPEED
        vx, vy, d = unit(tx - dest[0], ty - dest[1])
        yaw = yaw_toward(vx, vy) if d else s["yaw"]
        return _Leg(dest, yaw, walk_sec, path, 1.0, walk_sec + GESTURE_SEC["hug"], target)

    def leg_gesture(
        self,
        code: str,
        who: str,
        target: dict[str, Any] | None,
        dist: float,
        obstacles: list[Obstacle],
        path_obstacles: list[Obstacle],
    ) -> _Leg | str:
        s = self.state[who]
        x0, y0 = s["foot"]
        dest: Vec2 = (x0, y0)
        yaw, path, walk_sec = s["yaw"], None, 0.0
        if target is not None and dist > 1e-9:
            tx, ty = target["position"][0], target["position"][1]
            reach = REACH_DISTANCE_M.get(code)
            if reach is not None and dist > reach + REACH_TOLERANCE_M:
                if s["pose"] == "seated":
                    return "cannot_reach_seated"
                stop = _stop_point((tx, ty), reach, (x0, y0), obstacles, path_obstacles)
                dest = (pt(*stop)[0], pt(*stop)[1])
                path = _plan_path((x0, y0), dest, path_obstacles)[0]
                walk_sec = _path_length(path) / WALK_SPEED
            vx, vy, d = unit(tx - dest[0], ty - dest[1])
            if d:
                yaw = yaw_toward(vx, vy)
        return _Leg(dest, yaw, walk_sec, path, 1.0, walk_sec + GESTURE_SEC[code], target)

    def leg_walk_away(
        self,
        who: str,
        target: dict[str, Any] | None,
        index: int,
        at: float,
        obstacles: list[Obstacle],
    ) -> _Leg | str:
        s = self.state[who]
        x0, y0 = s["foot"]
        camera = self.camera_xy()
        if target is not None:
            vx, vy, d0 = unit(x0 - target["position"][0], y0 - target["position"][1])
            if not d0:
                hx, hy = heading(s["yaw"])
                vx, vy = -hx, -hy
        else:
            if self.camera:
                vx, vy, d0 = unit(x0 - self.camera["position"][0], y0 - self.camera["position"][1])
            else:
                vx, vy, d0 = 0.0, 0.0, 0.0
            if not d0:
                cx, cy = self.to_camera(x0, y0)
                vx, vy = -cx, -cy
            target = {
                "kind": "camera",
                "id": None,
                "position": camera,
            }
        target["cameraPosition"] = camera
        walk_obstacles = obstacles + (
            [("round", self.camera["position"][0], self.camera["position"][1], CAMERA_SAFETY_M)]
            if self.camera and self.follower != who
            else []
        )
        start_obstacles = [o for o in walk_obstacles if _clearance((x0, y0), [o]) >= 0]
        back = BACK_STEP_M
        while back > 1e-9:
            p1 = (x0 + vx * back, y0 + vy * back)
            if self.inside(*p1) and _is_clear([(x0, y0), p1], start_obstacles):
                break
            back = round(back - 0.05, 4)
        back = max(back, 0.0)
        p1 = (pt(x0 + vx * back, y0 + vy * back)[0], pt(x0 + vx * back, y0 + vy * back)[1])
        back_sec = back / BACK_SPEED
        time_for_walk = max(
            0.0,
            min(self.next_start(index, who), self.length)
            - max(at, s["free"])
            - back_sec
            - TURN_AWAY_SEC,
        )
        fill = WALK_SPEED * time_for_walk > CAMERA_ADVANCE_M
        reach = max(CAMERA_ADVANCE_M, WALK_SPEED * time_for_walk)
        away: list[list[float]] | None = None
        while reach >= MIN_WALK_M:
            p2 = (p1[0] + vx * reach, p1[1] + vy * reach)
            if self.inside(*p2) and _clearance(p2, walk_obstacles) >= 0:
                candidate, ok = _plan_path(p1, p2, walk_obstacles)
                if (
                    ok
                    and all(self.inside(*q) for q in candidate)
                    and (not fill or _path_length(candidate) <= WALK_SPEED * time_for_walk + 1e-3)
                ):
                    away = candidate
                    break
            reach = round(reach - 0.05, 4)
        away_len = _path_length(away) if away else 0.0
        if back + away_len < MIN_WALK_M:
            return "path_blocked"
        path = [pt(x0, y0), list(p1)] + (away[1:] if away else [])
        dest = (path[-1][0], path[-1][1])
        walk_sec, ratio = 0.0, 1.0
        if away:
            real = (
                max(WALK_SPEED * MIN_SPEED_RATIO, away_len / time_for_walk) if fill else WALK_SPEED
            )
            ratio = real / WALK_SPEED
            walk_sec = away_len / real
            a, b = path[-2], path[-1]
            yaw = yaw_toward(b[0] - a[0], b[1] - a[1])
        else:
            yaw = yaw_toward(vx, vy)
        return _Leg(
            dest, yaw, walk_sec, path, ratio, back_sec + TURN_AWAY_SEC + walk_sec, target, back_sec
        )

    def commit(
        self,
        code: str,
        who: str,
        s: dict[str, Any],
        leg: _Leg,
        at: float,
        prop: str | None,
        held_from_start: bool,
    ) -> None:
        earliest = s["free"]
        if code == "hug" and leg.target:
            earliest = max(earliest, self.state[leg.target["id"]]["free"] - leg.walk_sec)
        start = max(at, earliest)
        duration, walk_sec, ratio, back_sec = (
            leg.duration,
            leg.walk_sec,
            leg.speed_ratio,
            leg.back_sec,
        )
        if start + duration > self.length:
            start = max(earliest, self.length - duration)
        if start + duration > self.length + 1e-9:
            fits = self.length - start
            if fits < MIN_FIT_SEC:
                self.drop(who, code, "out_of_time")
                return
            self.warnings.append(
                {
                    "kind": "shortened",
                    "characterId": who,
                    "motion": code,
                    "reason": "trimmed_to_shot",
                }
            )
            scale = fits / duration
            back_sec *= scale
            walk_sec *= scale
            if walk_sec > 0:
                ratio /= scale
            duration = fits
        pose_after = {"sit_down": "seated", "stand_up": "standing", "fall": "fallen"}.get(
            code, s["pose"]
        )
        x0, y0 = s["foot"]
        dest = leg.dest
        stool = None
        if code == "sit_down":
            fx, fy = heading(leg.yaw)
            stool = {
                "center": pt(dest[0] - fx * 0.05, dest[1] - fy * 0.05, STOOL_SIZE_M[2] / 2),
                "dimensions": list(STOOL_SIZE_M),
            }
        target = None
        if leg.target is not None:
            target = {
                k: leg.target[k]
                for k in ("kind", "id", "position", "cameraPosition")
                if k in leg.target
            }
        prop_id = (
            prop
            if code == "hand_over"
            else (leg.target["id"] if code == "pick_up" and leg.target else None)
        )
        entry: Motion = {
            "characterId": who,
            "code": code,
            "target": target,
            "startSec": rnd(start),
            "endSec": rnd(start + duration),
            "walkSec": rnd(walk_sec),
            "speedRatio": rnd(ratio),
            "from": {
                "foot": pt(x0, y0),
                "yawDeg": rnd(s["yaw"]),
                "pose": s["pose"],
                "z0": rnd(_origin_z(s["pose"], s)),
            },
            "to": {
                "foot": pt(*dest),
                "yawDeg": rnd(leg.yaw),
                "pose": pose_after,
                "z0": rnd(_origin_z(pose_after, s)),
            },
            "propId": prop_id,
            "handoverSec": (
                rnd(start + walk_sec + HANDOVER_RATIO[code] * (duration - walk_sec))
                if code in HANDOVER_RATIO
                else None
            ),
            "stool": stool,
            "path": leg.path,
            "amplitude": NOD_AMPLITUDE.get(self.size or "", NOD_AMPLITUDE["MS"])
            if code == "nod"
            else None,
        }
        if code == "hug" and leg.target:
            other = self.state[leg.target["id"]]
            vx, vy, d = unit(dest[0] - other["foot"][0], dest[1] - other["foot"][1])
            to_yaw = rnd(yaw_toward(vx, vy)) if d else rnd(other["yaw"])
            entry["response"] = {
                "characterId": leg.target["id"],
                "fromYawDeg": rnd(other["yaw"]),
                "toYawDeg": to_yaw,
            }
            other.update(yaw=to_yaw, free=max(other["free"], entry["endSec"]))
        elif code == "walk_away":
            entry["backSec"] = rnd(back_sec)
        self.motions.append(entry)
        s.update(
            foot=entry["to"]["foot"],
            yaw=entry["to"]["yawDeg"],
            pose=pose_after,
            free=entry["endSec"],
        )
        self.update_hands(code, who, leg, prop, held_from_start)

    def update_hands(
        self, code: str, who: str, leg: _Leg, prop: str | None, held_from_start: bool
    ) -> None:
        if code == "pick_up" and leg.target:
            picked = leg.target["id"]
            self.held[picked], self.hand[who] = who, picked
            self.ever_held.add(picked)
            self.ever_carried.add(who)
        elif code == "hand_over" and leg.target and prop:
            if held_from_start:
                self.props[prop]["heldAtStart"] = who
                self.ever_carried.add(who)
            receiver = leg.target["id"]
            self.held[prop], self.hand[who], self.hand[receiver] = receiver, None, prop
            self.ever_held.add(prop)
            self.ever_carried.add(receiver)


def build_motions(
    staging: dict[str, Any],
    dummies: dict[str, dict[str, Any]],
    spots: dict[str, Any],
    pov_id: str | None,
    props: list[dict[str, Any]],
    fixed: list[dict[str, Any]],
    stage: dict[str, Any],
    line_ends: list[int],
    spans: dict[int, tuple[str | None, int, int]],
    frame_count: int,
    space: str | None,
    to_camera: Any,
    camera: dict[str, Any] | None,
    size: str | None,
    follower: str | None,
) -> tuple[list[Motion], list[dict[str, Any]], list[Warning]]:
    """Motion entries, speaking intervals and warnings of one shot.

    Targets that are people take their place after the earlier motions already planned; a prop
    handed over before anyone picked it up is held from the first frame (`heldAtStart`, set in
    place on `props`). `follower` is the character the camera follows, whose walks are not limited
    by the camera. `camera` is the first-frame estimate and may be None.
    """
    blocks: list[Obstacle] = []
    boxes = list(fixed)
    if stage.get("table"):
        boxes.append(
            {"center": stage["table"]["center"], "dimensions": stage["table"]["dimensions"]}
        )
    for b in boxes:
        c, d = b["center"], b["dimensions"]
        blocks.append(
            (
                "box",
                c[0] - d[0] / 2 - BLOCK_PADDING_M,
                c[0] + d[0] / 2 + BLOCK_PADDING_M,
                c[1] - d[1] / 2 - BLOCK_PADDING_M,
                c[1] + d[1] / 2 + BLOCK_PADDING_M,
            )
        )
    planner = _Planner(
        staging.get("actions") or [],
        dummies,
        spots,
        pov_id,
        props,
        blocks,
        line_ends,
        frame_count,
        space,
        to_camera,
        camera,
        size,
        follower,
        walkable_area(stage),
    )
    planner.run()
    return planner.motions, speaking_intervals(spans, dummies), planner.warnings


__all__ = ["MOTION_CODES", "build_motions", "speaking_intervals"]
