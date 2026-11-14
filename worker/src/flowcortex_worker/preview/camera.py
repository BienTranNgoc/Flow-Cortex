"""Camera placement parameters and the camera estimate of the staging card.

The card records only the placement function and its parameters; the Blender scene solves the
real camera. The estimate here is the same solution, used to know where a character walking
towards the camera stops and whether it stays in frame.
"""

from __future__ import annotations

import math
from collections.abc import Callable
from itertools import pairwise
from typing import Any

from flowcortex_worker.preview.stage import Dummy, Vec2, heading, rotate, unit
from flowcortex_worker.preview.vocabulary import (
    ANGLE_DEG,
    CAMERA_MIN_HEIGHT_M,
    DEFAULT_FACE_ANGLE_DEG,
    FACE_PROFILE,
    PAN_TILT_DEG,
    SIZE_FRAMING,
    SIZE_LADDER,
)

Vec3 = tuple[float, float, float]


def heading_from_gaze(row: dict[str, Any], yaw_deg: float) -> tuple[float, str]:
    """Camera heading in degrees from the character's gaze and face angle, with the look name.

    A positive heading puts the camera on the character's left, so the character looks to frame
    left. Without a gaze the camera sits off the character's facing, on the side that keeps it
    south of the character.
    """
    gaze = row.get("gaze")
    if gaze == "lens":
        return 0.0, "lens"
    angle = row.get("faceAngleDeg")
    angle = DEFAULT_FACE_ANGLE_DEG if not isinstance(angle, int | float) else abs(angle)
    if gaze == "frame_left":
        return float(angle), "frame_left"
    if gaze == "frame_right":
        return -float(angle), "frame_right"
    f = heading(yaw_deg)
    skew = DEFAULT_FACE_ANGLE_DEG
    chosen = skew if rotate(f, skew)[1] <= rotate(f, -skew)[1] + 1e-9 else -skew
    return float(chosen), "along_axis"


def size_step(size: str, steps: int) -> str:
    i = SIZE_LADDER.index(size) + steps
    return SIZE_LADDER[max(0, min(len(SIZE_LADDER) - 1, i))]


def _face_front(z: float) -> float:
    for (z0, a), (z1, b) in pairwise(FACE_PROFILE):
        if z0 <= z <= z1:
            return a + (b - a) * (z - z0) / (z1 - z0)
    return 0.0


def solve_single(
    x: float,
    y: float,
    yaw_deg: float,
    height: float,
    size: str,
    angle: str,
    heading_deg: float,
    z0: float,
    sensor_mm: float,
) -> tuple[Vec3, Vec3, float] | None:
    """Position, view direction and focal length of the single placement; None if unsolvable."""
    zt, yt, zd, yd, preset_focal = SIZE_FRAMING[size]
    f = float(preset_focal)

    def tan_v(focal: float) -> float:
        return (sensor_mm / 2) / focal

    eps, h = math.radians(ANGLE_DEG[angle]), math.radians(heading_deg)
    ux, uy = rotate(heading(yaw_deg), heading_deg)
    s_t, s_d = (_face_front(zz) * height * math.cos(h) for zz in (zt, zd))
    top = (x + ux * s_t, y + uy * s_t, z0 + zt * height)
    ds, dz = s_d - s_t, (zd - zt) * height
    seg = math.hypot(ds, dz)

    def eye_angles(focal: float) -> tuple[float, float]:
        t = math.atan(2 * yt * tan_v(focal))
        return t, t - math.atan(2 * yd * tan_v(focal))

    def distance_at(focal: float, e: float) -> float:
        a = eye_angles(focal)[1]
        cosine = max(-1.0, min(1.0, (ds * math.cos(e) + dz * math.sin(e)) / seg))
        far_angle = math.pi - a - math.acos(cosine)
        return seg * math.sin(far_angle) / math.sin(a) if far_angle > math.radians(1) else -1.0

    r0 = distance_at(f, 0.0)
    if distance_at(f, eps) < r0:
        lo = hi = f
        while distance_at(hi, eps) < r0 and hi < 5000:
            hi *= 1.5
        for _ in range(60):
            mid = (lo + hi) / 2
            lo, hi = (mid, hi) if distance_at(mid, eps) < r0 else (lo, mid)
        f = hi

    def place(e: float) -> tuple[float, Vec3]:
        r = distance_at(f, e)
        return r, (
            top[0] + r * math.cos(e) * ux,
            top[1] + r * math.cos(e) * uy,
            top[2] + r * math.sin(e),
        )

    def buried(e: float) -> bool:
        return place(e)[0] < 0 or place(e)[1][2] < CAMERA_MIN_HEIGHT_M

    if buried(eps):
        hi = eps
        while buried(hi) and hi < math.radians(85):
            hi += math.radians(5)
        lo = hi - math.radians(5)
        for _ in range(40):
            mid = (lo + hi) / 2
            lo, hi = (mid, hi) if buried(mid) else (lo, mid)
        eps = hi
    r, c = place(eps)
    if r < 0:
        return None
    p = eps + eye_angles(f)[0]
    return c, (-ux * math.cos(p), -uy * math.cos(p), -math.sin(p)), f


def view_basis(direction: Vec3) -> tuple[Vec3, Vec3]:
    """Right and up axes of a camera looking along `direction` (up follows world Z)."""
    px, py = direction[1], -direction[0]
    d = math.hypot(px, py) or 1.0
    right = (px / d, py / d, 0.0)
    up = (
        right[1] * direction[2] - right[2] * direction[1],
        right[2] * direction[0] - right[0] * direction[2],
        right[0] * direction[1] - right[1] * direction[0],
    )
    return right, up


def solve_group(
    points: list[list[float]], pitch_deg: float, focal: float, sensor_mm: float, aspect: float
) -> tuple[Vec3, Vec3, float]:
    """Camera looking north and pitched down, pulled back until every point fits the frame."""
    p = math.radians(pitch_deg)
    direction = (0.0, math.cos(p), -math.sin(p))
    right, up = view_basis(direction)
    tv = (sensor_mm / 2) / focal
    th = tv * aspect
    dots = [
        (
            sum(a * b for a, b in zip(P, right, strict=True)),
            sum(a * b for a, b in zip(P, up, strict=True)),
            sum(a * b for a, b in zip(P, direction, strict=True)),
        )
        for P in points
    ]
    ah, bh = max(r - th * q for r, _, q in dots), min(r + th * q for r, _, q in dots)
    av, bv = max(u - tv * q for _, u, q in dots), min(u + tv * q for _, u, q in dots)
    back = min((bh - ah) / (2 * th), (bv - av) / (2 * tv))
    cx, cy = (ah + bh) / 2, (av + bv) / 2
    position = (
        cx * right[0] + cy * up[0] + back * direction[0],
        cx * right[1] + cy * up[1] + back * direction[1],
        cx * right[2] + cy * up[2] + back * direction[2],
    )
    return position, direction, focal


def estimate_camera(
    placement: dict[str, Any],
    dummies: dict[str, Dummy],
    frame: dict[str, Any],
    motion_function: str,
) -> dict[str, Any] | None:
    """Camera position, direction and focal length at the first frame, or None when not estimable.

    `marginV` and `marginH` are the extra frame margins a tilt or pan needs. Insert and
    over-shoulder placements are not estimated.
    """
    function, params = placement["function"], placement["params"]
    sensor, aspect = frame["sensorMm"], frame["width"] / frame["height"]
    solved: tuple[Vec3, Vec3, float] | None = None
    if function == "single":
        n = dummies[params["characterId"]]
        solved = solve_single(
            n["foot"][0],
            n["foot"][1],
            n["yawDeg"],
            n["height"],
            params["size"],
            params["angle"],
            params["headingDeg"],
            n["z0"],
            sensor,
        )
    elif function in ("group", "establishing"):
        solved = solve_group(
            params["points"],
            params["pitchDeg"],
            params["focalMm"] / (1 - 2 * params["margin"]),
            sensor,
            aspect,
        )
    elif function == "pov":
        yaw, pitch = math.radians(params["yawDeg"]), math.radians(params["pitchDeg"])
        solved = (
            (params["eye"][0], params["eye"][1], params["eye"][2]),
            (
                math.sin(yaw) * math.cos(pitch),
                -math.cos(yaw) * math.cos(pitch),
                -math.sin(pitch),
            ),
            params["focalMm"],
        )
    if solved is None:
        return None
    position, direction, focal = solved
    margin = math.tan(math.radians(PAN_TILT_DEG / 2)) / (2 * (sensor / 2) / focal)
    return {
        "position": position,
        "direction": direction,
        "focalMm": focal,
        "sensorMm": sensor,
        "aspect": aspect,
        "marginV": margin if motion_function == "tilt" else 0.0,
        "marginH": margin / aspect if motion_function == "pan" else 0.0,
    }


def project(camera: dict[str, Any], point: Vec3 | list[float]) -> Vec3:
    """World point to (screen x, screen y in 0..1, depth) through the estimated camera."""
    direction, focal = camera["direction"], camera["focalMm"]
    right, up = view_basis(direction)
    v = [point[i] - camera["position"][i] for i in range(3)]
    depth = sum(a * b for a, b in zip(v, direction, strict=True))
    if depth <= 1e-6:
        return -1.0, -1.0, depth
    tv = (camera["sensorMm"] / 2) / focal
    return (
        0.5
        + sum(a * b for a, b in zip(v, right, strict=True)) / depth / (2 * tv * camera["aspect"]),
        0.5 + sum(a * b for a, b in zip(v, up, strict=True)) / depth / (2 * tv),
        depth,
    )


def toward_camera(
    placement: dict[str, Any], dummies: dict[str, Dummy], spots: dict[str, Any]
) -> Callable[[float, float], Vec2]:
    """(x, y) of a character to the horizontal unit vector towards the camera."""
    function, params = placement["function"], placement["params"]
    if function == "single":
        u = rotate(heading(dummies[params["characterId"]]["yawDeg"]), params["headingDeg"])
        return lambda x, y: u
    if function == "over_shoulder":
        (ax, ay), (bx, by) = (
            dummies[params["foregroundId"]]["foot"],
            dummies[params["subjectId"]]["foot"],
        )
        ux, uy, d = unit(ax - bx, ay - by)
        return lambda x, y: (ux, uy) if d else (0.0, -1.0)
    if function == "pov":
        px, py = spots[params["characterId"]][:2]

        def to_viewer(x: float, y: float) -> Vec2:
            ux, uy, d = unit(px - x, py - y)
            return (ux, uy) if d else (0.0, -1.0)

        return to_viewer
    return lambda x, y: (0.0, -1.0)
