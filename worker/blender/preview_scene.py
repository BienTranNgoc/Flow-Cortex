"""Blender scene script: a staging card becomes a white-block preview video.

    blender -b --factory-startup --python-exit-code 1 -P preview_scene.py -- \
        <card.json> <out.mp4> <font.ttf>

Reads only the card. Every standing place, camera function, camera move, dummy motion, speaking
interval and hand-over time is already in it. Writes an H.264 MP4 (Workbench, card size and fps)
with no burnt-in text, and `<out.mp4>.report.json`: frame count and size read back from the file,
whether the framed subjects stay in view, Blender version, GL renderer and build/render seconds.
Any error ends the process with exit code 1.

Moving dummies are keyed on every frame from a procedural pose function (steps, turns, sitting,
reaching, picking up, handing over, pointing, nodding, falling, hugging); they are rough blocks
without a skeleton, and a held prop follows the right hand. The camera is solved from the
placement parameters of the card, not from automatic camera rules.

Runs on Blender's bundled Python 3.11 and imports only bpy, gpu, mathutils and the standard library.
"""

import json
import math
import os
import sys
import time
from collections.abc import Callable
from itertools import pairwise
from typing import NamedTuple

import bpy
import gpu
from bpy_extras.object_utils import world_to_camera_view
from mathutils import Matrix, Vector

SENSOR_MM = 36.0
FRAME = (496, 864)
ASPECT = FRAME[0] / FRAME[1]
CAMERA_MIN_HEIGHT = 0.15

# Colours and Workbench light are tuned so that the floor reads mid grey, walls and cyclorama a
# darker cold grey and every dummy (grey 200 or white) brighter than both;
# tests/blender/brightness.py
# measures the rendered image against these bands.
FLOOR_GREY, WALL_GREY = (0.46, 0.455, 0.44), (0.28, 0.30, 0.35)
PIECE_COLOR, PROP_COLOR, TABLE_COLOR, LABEL_COLOR = (
    (150, 150, 150),
    (196, 180, 150),
    (128, 116, 100),
    (246, 246, 246),
)
SKY_COLOR = (0.015, 0.017, 0.022)


# ==== primitives ==============================================================================
def srgb_to_linear(c):
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def paint(obj, rgb):
    """Colour an object (sRGB 0-1) through the object colour and one shared material per colour."""
    linear = tuple(srgb_to_linear(c) for c in rgb[:3])
    obj.color = (*linear, 1.0)
    data = getattr(obj, "data", None)
    if data is not None and hasattr(data, "materials"):
        name = "mat_{:.3f}_{:.3f}_{:.3f}".format(*rgb[:3])
        material = bpy.data.materials.get(name) or bpy.data.materials.new(name)
        material.diffuse_color = (*linear, 1.0)
        data.materials.clear()
        data.materials.append(material)


def paint255(obj, rgb):
    paint(obj, tuple(c / 255 for c in rgb))


def _collection():
    return bpy.context.scene.collection


def adopt(child, parent):
    bpy.context.view_layer.update()
    world = child.matrix_world.copy()
    child.parent = parent
    child.matrix_parent_inverse = parent.matrix_world.inverted()
    child.matrix_world = world


def box(name, center, size, rgb, parent=None):
    bpy.ops.mesh.primitive_cube_add(size=1, location=center)
    obj = bpy.context.active_object
    obj.name, obj.scale = name, size
    paint(obj, rgb)
    if parent:
        adopt(obj, parent)
    return obj


def sphere(name, center, radius, rgb, scale=(1, 1, 1), parent=None):
    bpy.ops.mesh.primitive_uv_sphere_add(radius=radius, location=center, segments=24, ring_count=12)
    obj = bpy.context.active_object
    obj.name, obj.scale = name, scale
    paint(obj, rgb)
    bpy.ops.object.shade_smooth()
    if parent:
        adopt(obj, parent)
    return obj


def tube(name, a, b, radius, rgb, parent=None, end_radius=None):
    a, b = Vector(a), Vector(b)
    d = b - a
    if end_radius is None:
        bpy.ops.mesh.primitive_cylinder_add(
            radius=radius, depth=d.length, location=(a + b) / 2, vertices=16
        )
    else:
        bpy.ops.mesh.primitive_cone_add(
            radius1=radius, radius2=end_radius, depth=d.length, location=(a + b) / 2, vertices=20
        )
    obj = bpy.context.active_object
    obj.name = name
    obj.rotation_euler = Vector((0, 0, 1)).rotation_difference(d.normalized()).to_euler()
    paint(obj, rgb)
    bpy.ops.object.shade_smooth()
    if parent:
        adopt(obj, parent)
    return obj


def empty(name, position, parent=None):
    obj = bpy.data.objects.new(name, None)
    obj.empty_display_size = 0.05
    _collection().objects.link(obj)
    obj.location = position
    if parent:
        adopt(obj, parent)
    return obj


def keyframe(obj, attr, frame, value):
    setattr(obj, attr, value)
    obj.keyframe_insert(data_path=attr, frame=frame)


def yaw_for(fx, fy):
    """Rotation about Z that turns a dummy's face (default -Y) towards (fx, fy)."""
    return math.atan2(fx, -fy)


JOINTS = (
    "hip",
    "chest",
    "neck",
    "shoulderL",
    "shoulderR",
    "elbowL",
    "elbowR",
    "thighL",
    "thighR",
    "kneeL",
    "kneeR",
)


def set_pose(rig, frame, **angles):
    for joint in JOINTS:
        keyframe(
            rig[joint], "rotation_euler", frame, angles.get(joint, tuple(rig[joint].rotation_euler))
        )


def build_dummy(name, height, rgb):
    """A jointed dummy (joints are empties); the dark cone on the head marks where it looks."""
    h = height
    k = {}
    k["root"] = empty(f"{name}_root", (0, 0, 0))
    k["hip"] = empty(f"{name}_hip", (0, 0, 0.52 * h), k["root"])
    k["chest"] = empty(f"{name}_chest", (0, 0, 0.66 * h), k["hip"])
    k["neck"] = empty(f"{name}_neck", (0, 0, 0.83 * h), k["chest"])
    sphere(f"{name}_pelvis", (0, 0, 0.535 * h), 1, rgb, (0.11 * h, 0.075 * h, 0.075 * h), k["hip"])
    sphere(f"{name}_torso", (0, 0, 0.70 * h), 1, rgb, (0.12 * h, 0.075 * h, 0.13 * h), k["chest"])
    tube(f"{name}_neckpiece", (0, 0, 0.81 * h), (0, 0, 0.87 * h), 0.03 * h, rgb, k["neck"])
    k["head"] = sphere(f"{name}_head", (0, -0.005 * h, 0.925 * h), 0.072 * h, rgb, parent=k["neck"])
    tube(
        f"{name}_nose",
        (0, -0.06 * h, 0.925 * h),
        (0, -0.115 * h, 0.925 * h),
        0.022 * h,
        tuple(c * 0.55 for c in rgb),
        k["neck"],
        end_radius=0.0005,
    )
    for p, s in (("L", 1), ("R", -1)):
        shoulder = empty(f"{name}_shoulder{p}", (s * 0.115 * h, 0, 0.80 * h), k["chest"])
        elbow = empty(f"{name}_elbow{p}", (s * 0.125 * h, 0, 0.62 * h), shoulder)
        tube(
            f"{name}_upperarm{p}",
            (s * 0.115 * h, 0, 0.80 * h),
            (s * 0.125 * h, 0, 0.62 * h),
            0.026 * h,
            rgb,
            shoulder,
        )
        tube(
            f"{name}_forearm{p}",
            (s * 0.125 * h, 0, 0.62 * h),
            (s * 0.13 * h, 0, 0.465 * h),
            0.022 * h,
            rgb,
            elbow,
        )
        k[f"hand{p}"] = sphere(
            f"{name}_hand{p}", (s * 0.13 * h, 0, 0.445 * h), 0.028 * h, rgb, parent=elbow
        )
        thigh = empty(f"{name}_thigh{p}", (s * 0.05 * h, 0, 0.50 * h), k["hip"])
        knee = empty(f"{name}_knee{p}", (s * 0.05 * h, 0, 0.28 * h), thigh)
        tube(
            f"{name}_upperleg{p}",
            (s * 0.05 * h, 0, 0.50 * h),
            (s * 0.05 * h, 0, 0.28 * h),
            0.036 * h,
            rgb,
            thigh,
        )
        tube(
            f"{name}_lowerleg{p}",
            (s * 0.05 * h, 0, 0.28 * h),
            (s * 0.05 * h, 0, 0.045 * h),
            0.029 * h,
            rgb,
            knee,
        )
        box(
            f"{name}_foot{p}",
            (s * 0.05 * h, -0.035 * h, 0.02 * h),
            (0.045 * h, 0.11 * h, 0.04 * h),
            rgb,
            knee,
        )
        tube(
            f"{name}_trousers{p}",
            (s * 0.05 * h, 0, 0.52 * h),
            (s * 0.05 * h, 0, 0.40 * h),
            0.047 * h,
            tuple(c * 0.85 for c in rgb),
            thigh,
        )
        k[f"shoulder{p}"], k[f"elbow{p}"], k[f"thigh{p}"], k[f"knee{p}"] = (
            shoulder,
            elbow,
            thigh,
            knee,
        )
    return k


def make_camera(name, position, look_at, focal_mm):
    data = bpy.data.cameras.new(name)
    data.lens = focal_mm
    data.sensor_fit = "VERTICAL"
    data.sensor_height = SENSOR_MM
    data.clip_start, data.clip_end = 0.05, 300.0
    obj = bpy.data.objects.new(name, data)
    _collection().objects.link(obj)
    aim(obj, position, look_at)
    return obj


def aim(obj, position, look_at, frame=None):
    obj.location = position
    obj.rotation_euler = (
        (Vector(look_at) - Vector(position))
        .to_track_quat("-Z", "Y")
        .to_euler("XYZ", obj.rotation_euler)
    )
    if frame is not None:
        obj.keyframe_insert("location", frame=frame)
        obj.keyframe_insert("rotation_euler", frame=frame)


# ==== camera solving ==========================================================================
# A "view" is (camera position, look-at point, focal length mm, horizontal shift).
LANDMARK = {
    "top": 1.0,
    "eye": 0.935,
    "chin": 0.853,
    "shoulder": 0.80,
    "chest": 0.70,
    "hip": 0.52,
    "knee": 0.28,
    "foot": 0.0,
}
EYE = LANDMARK["eye"]
SHOULDER_HALF_WIDTH = 0.115
# Face depth (forward of the body axis) by height ratio, used to aim at the face and not the spine.
FACE_PROFILE = ((0.80, 0.0), (LANDMARK["chin"], 0.045), (0.90, 0.06), (0.97, 0.06), (1.0, 0.0))
# size -> (top ratio, top screen offset, bottom ratio, bottom screen offset, suggested focal mm)
LADDER = {
    "ECU": (EYE + 0.03, 0.30, EYE - 0.03, -0.30, 100),
    "BCU": (EYE, 0.17, LANDMARK["chin"], -0.45, 100),
    "CU": (EYE, 0.17, 0.76, -0.50, 85),
    "MCU": (EYE, 0.20, 0.66, -0.50, 70),
    "MS": (EYE, 0.24, LANDMARK["hip"], -0.50, 50),
    "MLS": (EYE, 0.30, LANDMARK["knee"], -0.50, 35),
    "FS": (1.00, 0.42, 0.00, -0.46, 35),
    "LS": (1.00, -0.05, 0.00, -0.30, 28),
    "EWS": (1.00, -0.18, 0.00, -0.28, 24),
}
ANGLE_DEG = {"eye": 0, "low": -20, "worm": -35, "high": 25, "overhead": 50}
_NOISE_PEAK = 0.35


def _lookup(value, table):
    return table[value] if isinstance(value, str) else value


def _tan_half_v(focal):
    return (SENSOR_MM / 2) / focal


def _ray(x, y, focal):
    t = _tan_half_v(focal)
    return Vector((2 * x * t * ASPECT, 2 * y * t, -1.0)).normalized()


def _facing(yaw):
    return Vector((math.sin(yaw), -math.cos(yaw), 0.0))


def _rotate_z(v, angle):
    c, s = math.cos(angle), math.sin(angle)
    return Vector((v.x * c - v.y * s, v.x * s + v.y * c, 0.0))


def view_matrix(view):
    position, look_at = Vector(view[0]), Vector(view[1])
    m = (look_at - position).to_track_quat("-Z", "Y").to_matrix().to_4x4()
    m.translation = position
    return m


def roll_deg(m):
    forward, up = -m.col[2].xyz.normalized(), m.col[1].xyz.normalized()
    z = Vector((0, 0, 1))
    reference = (z - z.dot(forward) * forward).normalized()
    return math.degrees(math.atan2(reference.cross(up).dot(forward), reference.dot(up)))


def _face_depth(ratio):
    for (r0, a), (r1, b) in pairwise(FACE_PROFILE):
        if r0 <= ratio <= r1:
            return a + (b - a) * (ratio - r0) / (r1 - r0)
    return 0.0


def landmark(foot, yaw, height, name, z0=0.0):
    p = Vector((foot[0], foot[1], z0))
    if name.startswith("shoulder_"):
        left = _rotate_z(_facing(yaw), math.pi / 2)
        sign = 1 if name == "shoulder_left" else -1
        return (
            p
            + left * SHOULDER_HALF_WIDTH * height * sign
            + Vector((0, 0, LANDMARK["shoulder"] * height))
        )
    return (
        p
        + _facing(yaw) * _face_depth(LANDMARK[name]) * height
        + Vector((0, 0, LANDMARK[name] * height))
    )


def blend_size(a, b, t):
    a, b = _lookup(a, LADDER), _lookup(b, LADDER)
    return tuple(x + (y - x) * t for x, y in zip(a, b, strict=True))


def _shift_scale():
    """Blender measures shift along the LONG side: a portrait frame scales x by the aspect."""
    return min(1.0, ASPECT)


def single_shot(
    foot,
    yaw,
    height,
    size="MS",
    angle="eye",
    heading_deg=0.0,
    f=None,
    screen_x=0.0,
    z0=0.0,
    ground=None,
):
    """Camera for ONE character: closed-form solve of the size (top and bottom of the framing) with
    the lens, angle and heading. heading_deg > 0 puts the camera towards the character's left."""
    top_r, top_y, bottom_r, bottom_y, suggested = _lookup(size, LADDER)
    eps, h = math.radians(_lookup(angle, ANGLE_DEG)), math.radians(heading_deg)
    u = _rotate_z(_facing(yaw), h)
    s_top, s_bottom = (_face_depth(r) * height * math.cos(h) for r in (top_r, bottom_r))
    top = Vector((foot[0], foot[1], z0 + top_r * height)) + u * s_top
    ds, dz = s_bottom - s_top, (bottom_r - top_r) * height
    length = math.hypot(ds, dz)

    def eye_angles(f):
        t = math.atan(2 * top_y * _tan_half_v(f))
        return t, t - math.atan(2 * bottom_y * _tan_half_v(f))

    def distance_at(f, e):
        a = eye_angles(f)[1]
        far = (
            math.pi
            - a
            - math.acos(max(-1.0, min(1.0, (ds * math.cos(e) + dz * math.sin(e)) / length)))
        )
        return length * math.sin(far) / math.sin(a) if far > math.radians(1) else -1.0

    if f is None:
        f = suggested
        r0 = distance_at(f, 0.0)
        if distance_at(f, eps) < r0:
            lo, hi = f, f
            while distance_at(hi, eps) < r0 and hi < 5000:
                hi *= 1.5
            for _ in range(60):
                mid = (lo + hi) / 2
                lo, hi = (mid, hi) if distance_at(mid, eps) < r0 else (lo, mid)
            f = hi
    top_angle = eye_angles(f)[0]
    ground = z0 if ground is None else ground
    ground_at = ground if callable(ground) else (lambda x, y: ground)

    def place(e):
        r = distance_at(f, e)
        if r < 0:
            raise ValueError(
                f"size {size} angle {math.degrees(e):.0f} deg lens {f:.0f} mm: too steep"
            )
        return r, top + r * (math.cos(e) * u + math.sin(e) * Vector((0, 0, 1)))

    def underground(e):
        c = place(e)[1]
        return c.z < ground_at(c.x, c.y) + CAMERA_MIN_HEIGHT

    if underground(eps):
        hi = eps
        while underground(hi):
            hi += math.radians(5)
        lo = hi - math.radians(5)
        for _ in range(40):
            mid = (lo + hi) / 2
            lo, hi = (mid, hi) if underground(mid) else (lo, mid)
        eps = hi
    r, position = place(eps)
    p = eps + top_angle
    forward = -u * math.cos(p) - Vector((0, 0, 1)) * math.sin(p)
    return position, position + forward * r, f, -screen_x * _shift_scale()


def insert_shot(center, width, f=24, up_deg=0.0):
    height = width * f / (SENSOR_MM * ASPECT)
    a = math.radians(up_deg)
    position = Vector((center[0], center[1], center[2] + height))
    return (
        position,
        Vector(
            (
                center[0] + math.sin(a) * height * 1e-3,
                center[1] + math.cos(a) * height * 1e-3,
                center[2],
            )
        ),
        f,
        0.0,
    )


def two_point_matrix(
    a_point, b_point, screen_a, screen_b, f, distance_a, side=-1, lift=0.0, behind=True
):
    """Camera matrix that puts world points A and B at two given screen positions."""
    a_point, b_point = Vector(a_point), Vector(b_point)
    da, db = _ray(*screen_a, f), _ray(*screen_b, f)
    alpha, ab = da.angle(db), (b_point - a_point).length
    s = max(-1.0, min(1.0, distance_a * math.sin(alpha) / ab))
    beta = math.pi - alpha - math.asin(s) if behind else math.asin(s) - alpha
    along = (b_point - a_point).normalized()
    sideways = along.cross(Vector((0, 0, 1))).normalized() * (-side)
    up = sideways.cross(along).normalized()
    up = up if up.z >= 0 else -up
    c = a_point + distance_a * (
        math.cos(beta) * along + math.sin(beta) * (math.cos(lift) * sideways + math.sin(lift) * up)
    )

    def frame(a, b):
        e1, e2 = (a + b).normalized(), (a - b).normalized()
        return Matrix((e1, e2, e1.cross(e2))).transposed()

    m = (
        frame((a_point - c).normalized(), (b_point - c).normalized()) @ frame(da, db).transposed()
    ).to_4x4()
    m.translation = c
    return m


def level_two_point_view(
    a_point, b_point, screen_a, screen_b, f, distance_a, side=-1, preferred_lift=10.0
):
    """Two-point camera with the roll removed: scan the lift angle for roll = 0 and keep the root
    nearest the preferred lift."""

    def roll_at(d):
        return roll_deg(
            two_point_matrix(
                a_point, b_point, screen_a, screen_b, f, distance_a, side, math.radians(d)
            )
        )

    roots = []
    for a in range(-80, 80, 2):
        b = a + 2
        ra, rb = roll_at(a), roll_at(b)
        if ra * rb <= 0 and abs(ra - rb) < 90:
            lo, hi = float(a), float(b)
            for _ in range(48):
                mid = (lo + hi) / 2
                lo, hi = (mid, hi) if (roll_at(mid) > 0) == (ra > 0) else (lo, mid)
            roots.append((lo + hi) / 2)
    if not roots:
        raise ValueError("over_shoulder: the roll cannot be removed")
    m = two_point_matrix(
        a_point,
        b_point,
        screen_a,
        screen_b,
        f,
        distance_a,
        side,
        math.radians(min(roots, key=lambda d: abs(d - preferred_lift))),
    )
    c = m.translation.copy()
    return c, c - m.col[2].xyz.normalized() * (Vector(b_point) - c).length, f, 0.0


def over_shoulder_shot(
    a,
    b,
    size="MCU",
    x_shoulder=0.36,
    y_shoulder=-0.30,
    x_face=0.12,
    shoulder_distance=None,
    f=None,
    side=-1,
):
    """Over the shoulder of A (foreground) at B; a, b = (foot, yaw, height, z0)."""
    xa, yaw_a, height_a, za = Vector((a[0][0], a[0][1], 0.0)), a[1], a[2], a[3]
    xb, yaw_b, height_b, zb = Vector((b[0][0], b[0][1], 0.0)), b[1], b[2], b[3]
    _, top_y, bottom_r, bottom_y, suggested = _lookup(size, LADDER)
    b_eye = landmark(xb, yaw_b, height_b, "eye", zb)
    b_bottom = Vector((xb.x, xb.y, zb + bottom_r * height_b))
    shoulder = max(
        (landmark(xa, yaw_a, height_a, name, za) for name in ("shoulder_left", "shoulder_right")),
        key=lambda v: side * (xb - xa).to_2d().cross((v - xa).to_2d()),
    )
    lens = f or suggested
    view = None
    for _ in range(12):
        view = level_two_point_view(
            shoulder,
            b_eye,
            (side * x_shoulder, y_shoulder),
            (-side * x_face, top_y),
            lens,
            shoulder_distance or 0.42 * height_a,
            side,
        )
        p = view_matrix(view).inverted() @ b_bottom
        new_lens = lens * (top_y - bottom_y) / (top_y - p.y / -p.z / (2 * _tan_half_v(lens)))
        if f or abs(new_lens - lens) < 0.01:
            break
        lens = new_lens
    return view


def group_shot(points, azimuth_deg=0.0, pitch_deg=35.0, f=24.0, margin=0.06, center_y=0.0):
    """Frame every point with Object.camera_fit_coords, then centre the cluster vertically."""
    scene = bpy.context.scene
    r = scene.render
    saved = (
        r.resolution_x,
        r.resolution_y,
        r.resolution_percentage,
        r.pixel_aspect_x,
        r.pixel_aspect_y,
    )
    r.resolution_x, r.resolution_y, r.resolution_percentage, r.pixel_aspect_x, r.pixel_aspect_y = (
        *FRAME,
        100,
        1,
        1,
    )
    data = bpy.data.cameras.new("_fit")
    data.sensor_fit, data.sensor_height, data.lens = "VERTICAL", SENSOR_MM, f / (1 - 2 * margin)
    obj = bpy.data.objects.new("_fit", data)
    scene.collection.objects.link(obj)
    a, p = math.radians(azimuth_deg), math.radians(pitch_deg)
    forward = Vector((math.sin(a) * math.cos(p), math.cos(a) * math.cos(p), -math.sin(p)))
    obj.rotation_euler = forward.to_track_quat("-Z", "Y").to_euler()
    bpy.context.view_layer.update()
    c, _ = obj.camera_fit_coords(
        bpy.context.evaluated_depsgraph_get(), [v for point in points for v in point]
    )
    bpy.data.objects.remove(obj)
    bpy.data.cameras.remove(data)
    r.resolution_x, r.resolution_y, r.resolution_percentage, r.pixel_aspect_x, r.pixel_aspect_y = (
        saved
    )
    c = Vector(c)
    points = [Vector(q) for q in points]
    depth = sum((q - c).dot(forward) for q in points) / len(points)
    if center_y is not None:
        up = forward.to_track_quat("-Z", "Y").to_matrix().col[1]
        for _ in range(12):
            screen = sorted(
                (
                    (q - c).dot(up) / (q - c).dot(forward) / (2 * _tan_half_v(f)),
                    (q - c).dot(forward),
                )
                for q in points
            )
            (y0, d0), (y1, d1) = screen[0], screen[-1]
            offset = (y0 + y1) / 2 - center_y
            if abs(offset) < 1e-4 or y1 - y0 > 1 - 2 * margin - 0.01:
                break
            c += up * offset * 2 * _tan_half_v(f) / ((1 / d0 + 1 / d1) / 2)
    return c, c + forward * depth, f, 0.0


def apply_view(camera, view, frame=None):
    position, look_at, focal, shift_x = view
    data = camera.data
    data.sensor_fit, data.sensor_height, data.lens, data.shift_x = (
        "VERTICAL",
        SENSOR_MM,
        focal,
        shift_x,
    )
    aim(camera, position, look_at, frame)
    if frame is not None:
        data.keyframe_insert("lens", frame=frame)
        data.keyframe_insert("shift_x", frame=frame)


def _fcurves(id_):
    animation = id_.animation_data
    try:
        from bpy_extras import anim_utils

        channelbag = anim_utils.action_get_channelbag_for_slot(
            animation.action, animation.action_slot
        )
        return list(channelbag.fcurves) if channelbag else []
    except (ImportError, AttributeError):
        return list(animation.action.fcurves)


def handheld_shake(camera, f0, f1, amplitude=1.0, rate=3.0, fade_in=6, fade_out=6, seed=0):
    """Hand-held shake: Noise on the rotation curves of an empty that the camera copies."""
    shaker = bpy.data.objects.new(f"{camera.name}_shake", None)
    (
        camera.users_collection[0] if camera.users_collection else bpy.context.scene.collection
    ).objects.link(shaker)
    shaker.empty_display_size = 0.05
    shaker.keyframe_insert("rotation_euler", frame=f0)
    for fc in _fcurves(shaker):
        noise = fc.modifiers.new("NOISE")
        noise.use_legacy_noise = False
        noise.strength = (
            math.radians(amplitude) * (0.5 if fc.array_index == 2 else 1.0) / _NOISE_PEAK
        )
        noise.scale, noise.phase = rate, 7.31 * seed + 3.17 * fc.array_index
        noise.use_restricted_range, noise.frame_start, noise.frame_end = True, f0, f1
        noise.blend_in, noise.blend_out = fade_in, fade_out
    constraint = camera.constraints.new("COPY_ROTATION")
    constraint.name, constraint.target, constraint.mix_mode = "shake", shaker, "AFTER"
    constraint.owner_space, constraint.target_space = "LOCAL", "WORLD"
    return shaker


# ==== stage and labels ========================================================================
def ease(t):
    return t * t * (3 - 2 * t)


def build_label(name, text, position, size, camera, font, parent=None, follow=None):
    """Floating text that always faces the camera (Track To). `follow` makes it copy that object's
    location (`position` is then a world offset), so a name stays above a walking dummy."""
    curve = bpy.data.curves.new(name, "FONT")
    curve.body, curve.font, curve.size = text, font, size
    curve.align_x, curve.align_y = "CENTER", "BOTTOM"
    obj = bpy.data.objects.new(name, curve)
    _collection().objects.link(obj)
    if parent is not None:
        obj.parent = parent
        obj.matrix_parent_inverse.identity()
    obj.location = position
    paint255(obj, LABEL_COLOR)
    obj.display.show_shadows = False  # a camera-facing label would cast a long streak on the floor
    if follow is not None:
        copy = obj.constraints.new("COPY_LOCATION")
        copy.target, copy.use_offset = follow, True
    track = obj.constraints.new("TRACK_TO")
    track.target, track.track_axis, track.up_axis = camera, "TRACK_Z", "UP_Y"
    return obj


def build_stage(stage):
    kind = stage["kind"]
    flat_panels = stage.get("flatPanels", True)
    if kind == "room_box":
        cx, cy = stage["center"]
        w, d, h = stage["width"], stage["depth"], stage["height"]
        box("floor", (cx, cy, -0.05), (w, d, 0.1), FLOOR_GREY)
        if flat_panels:
            box("wall_north", (cx, cy + d / 2, h / 2), (w, 0.1, h), WALL_GREY)
            box("wall_west", (cx - w / 2, cy, h / 2), (0.1, d, h), WALL_GREY)
            box("wall_east", (cx + w / 2, cy, h / 2), (0.1, d, h), WALL_GREY)
        if stage.get("table"):
            table = stage["table"]
            box(
                "table",
                tuple(table["center"]),
                tuple(table["dimensions"]),
                tuple(c / 255 for c in TABLE_COLOR),
            )
    elif kind == "car":
        box("car_floor", (0, 0.2, -0.025), (1.8, 2.2, 0.05), FLOOR_GREY)
        for i, seat in enumerate(stage["seats"]):
            x, y = seat["center"]
            box(f"seat_{i}_cushion", (x, y - 0.15, 0.4), (0.5, 0.5, 0.1), WALL_GREY)
            box(f"seat_{i}_back", (x, y + 0.15, 0.8), (0.5, 0.1, 0.8), WALL_GREY)
        wheel = stage["steeringWheel"]
        bpy.ops.mesh.primitive_torus_add(
            major_radius=wheel["radius"],
            minor_radius=0.02,
            location=tuple(wheel["center"]),
            rotation=(math.radians(70), 0, 0),
        )
        obj = bpy.context.active_object
        obj.name = "steering_wheel"
        paint(obj, (0.3, 0.3, 0.3))
    elif kind == "cyclorama" and not flat_panels:
        box("floor", (0, 0, -0.05), (stage["width"], stage["depth"], 0.1), FLOOR_GREY)
    elif kind == "cyclorama":
        # Flat floor in the floor colour; the curve and the upright backdrop in the wall colour (one
        # panel would catch the light and read almost as bright as a white dummy).
        w, depth, r, h = stage["width"], stage["depth"], stage["cycloramaRadius"], stage["height"]
        y0, y1 = -depth / 2, depth / 2 - r
        box("floor", (0, (y0 + y1) / 2, -0.05), (w, y1 - y0, 0.1), FLOOR_GREY)
        profile = [
            (y1 + r * math.sin(math.radians(a)), r - r * math.cos(math.radians(a)))
            for a in range(0, 91, 10)
        ]
        profile.append((y1 + r, h))
        vertices = [(x, y, z) for y, z in profile for x in (-w / 2, w / 2)]
        faces = [(2 * i, 2 * i + 1, 2 * i + 3, 2 * i + 2) for i in range(len(profile) - 1)]
        mesh = bpy.data.meshes.new("cyclorama")
        mesh.from_pydata(vertices, [], faces)
        obj = bpy.data.objects.new("cyclorama", mesh)
        _collection().objects.link(obj)
        paint(obj, WALL_GREY)
    else:
        box("ground", (0, 0, -0.05), (stage["width"], stage["depth"], 0.1), FLOOR_GREY)


# ==== dummy motion ============================================================================
# A pose is (root xyz, root rotation (lean, 0, yaw), {joint: euler}) computed procedurally per
# second of the shot. Joint convention: the face looks along -Y; arms and legs hang along -Z;
# negative X rotation swings a limb FORWARD; positive X at the chest or neck bends forward;
# L is the dummy's left (+X), R its right (-X).
STEP_PERIOD = {"walk_to": 26 / 24, "run_to": 16 / 24}  # seconds per two-step cycle
STEP_AMPLITUDE = {"walk_to": 0.45, "run_to": 0.8}
STEP_BOB = {"walk_to": 0.015, "run_to": 0.04}
RUN_LEAN = 0.25
STEP_RAMP_SEC = 0.2  # step amplitude ramps up and down over this long at each end of a walk
TURN_BEFORE_SEC = 0.25
TURN_AFTER_SEC = 0.3
SEATED = {
    "thighL": (-math.pi / 2, 0, 0),
    "thighR": (-math.pi / 2, 0, 0),
    "kneeL": (math.pi / 2, 0, 0),
    "kneeR": (math.pi / 2, 0, 0),
    "shoulderL": (-0.5, 0, 0.15),
    "shoulderR": (-0.5, 0, -0.15),
    "elbowL": (-1.1, 0, 0),
    "elbowR": (-1.1, 0, 0),
}
FALLEN = {
    "shoulderL": (-2.8, 0.2, 0),
    "shoulderR": (-2.8, -0.2, 0),
}  # face down, arms stretched overhead
HOLDING_ARM = {"shoulderR": (-0.35, 0, 0), "elbowR": (-1.25, 0, 0)}  # forearm across the belly
RIGHT_ARM = ("shoulderR", "elbowR")
CHEST_JOINT_HEIGHT = 0.66
HANDOVER_SLIDE_SEC = 0.3  # a prop slides from the old hand to the new one over this long
HUG_ARMS = (
    0.0,
    0.15,
    0.85,
    1.0,
)  # fractions of the gesture: arms up to shoulder height, hold, down
HUG_CLOSE = (
    0.15,
    0.3,
    0.7,
    0.85,
)  # elbows close the circle round the other's shoulders, hold, open
HUG_ELBOW = 0.85
HUG_OUTSIDE_SHOULDER = 0.04
HUG_BEHIND_SHOULDER = 0.05
HUG_RESPONSE_TURN_SEC = 0.4
HUG_RESPONSE_DROP = 0.08  # the hugged dummy's arms sit this far lower than the hugger's
WALK_SPEED = 1.2  # m/s, scales the backward steps of walk_away


def _short_angle(a, b):
    """b - a folded into (-pi, pi]."""
    return (b - a + math.pi) % (2 * math.pi) - math.pi


def _mix(a, b, w):
    return tuple(x + (y - x) * w for x, y in zip(a, b, strict=True))


def _mix_joints(joints, target, w, names=None):
    for name in names or target:
        joints[name] = _mix(joints.get(name, (0.0, 0.0, 0.0)), target.get(name, (0.0, 0.0, 0.0)), w)


def _add(joints, name, v):
    joints[name] = tuple(a + b for a, b in zip(joints.get(name, (0.0, 0.0, 0.0)), v, strict=True))


def _envelope(u, rise, fall_from):
    """0 -> 1 over [0, rise], hold, back to 0 over [fall_from, 1]."""
    if u <= 0.0 or u >= 1.0:
        return 0.0
    if u < rise:
        return ease(u / rise)
    if u > fall_from:
        return ease((1.0 - u) / (1.0 - fall_from))
    return 1.0


def _envelope4(u, a, b, c, d):
    """0 -> 1 over [a, b], hold, back to 0 over [c, d]; zero outside [a, d]."""
    if u <= a or u >= d:
        return 0.0
    if u < b:
        return ease((u - a) / (b - a))
    if u > c:
        return ease((d - u) / (d - c))
    return 1.0


def aim_arm(x, y, z_root, yaw, height, side, target, bend=0.0):
    """Shoulder euler that points the hanging arm at `target`; side +1 = left arm, -1 = right.
    `bend` is the chest's forward bend (rad): the shoulder moves with the chest and the direction
    is taken in the chest frame. X rotation first (lift by height), then Z (sideways)."""
    c, s_ = math.cos(yaw), math.sin(yaw)
    above_chest = (LANDMARK["shoulder"] - CHEST_JOINT_HEIGHT) * height
    lx0, ly0 = side * SHOULDER_HALF_WIDTH * height, -above_chest * math.sin(bend)
    vx, vy = x + lx0 * c - ly0 * s_, y + lx0 * s_ + ly0 * c
    vz = z_root + CHEST_JOINT_HEIGHT * height + above_chest * math.cos(bend)
    dx, dy, dz = target[0] - vx, target[1] - vy, target[2] - vz
    lx, ly = dx * c + dy * s_, -dx * s_ + dy * c
    ly, dz = ly * math.cos(bend) + dz * math.sin(bend), -ly * math.sin(bend) + dz * math.cos(bend)
    length = math.sqrt(lx * lx + ly * ly + dz * dz) or 1.0
    e = math.asin(max(-1.0, min(1.0, dz / length)))
    return (-(math.pi / 2 + e), 0.0, math.atan2(lx, -ly))


def hug_arms(joints, x, y, z, yaw, height, target, u):
    """Both arms hugging at fraction u of the gesture: shoulders aim outside and behind the other's
    shoulders (`target` is the other's body axis at shoulder height), then the elbows close in."""
    reach, close = _envelope4(u, *HUG_ARMS), _envelope4(u, *HUG_CLOSE)
    c, s_ = math.cos(yaw), math.sin(yaw)
    for side, p in ((1, "L"), (-1, "R")):
        offset = side * (SHOULDER_HALF_WIDTH * height + HUG_OUTSIDE_SHOULDER)
        point = (
            target[0] + c * offset + s_ * HUG_BEHIND_SHOULDER,
            target[1] + s_ * offset - c * HUG_BEHIND_SHOULDER,
            target[2],
        )
        _mix_joints(
            joints,
            {
                f"shoulder{p}": aim_arm(x, y, z, yaw, height, side, point),
                f"elbow{p}": (0.0, 0.0, 0.0),
            },
            reach,
            (f"shoulder{p}", f"elbow{p}"),
        )
        _add(joints, f"elbow{p}", (0.0, side * HUG_ELBOW * close, 0.0))
    return reach


def walk_cycle(seconds, code, holding, ramp=1.0, speed_ratio=1.0):
    """Limbs while stepping. `seconds` since the walk began; `ramp` scales the amplitude in and out.
    A slower walk takes shorter and fewer steps, each by the square root of the ratio, so the
    feet do not slide."""
    r = math.sqrt(max(1e-3, speed_ratio))
    amplitude = STEP_AMPLITUDE[code] * ramp * r
    phase = 2 * math.pi * seconds * r / STEP_PERIOD[code]
    s_ = math.sin(phase)
    elbow = (-0.25 - 0.2 * abs(s_) * (amplitude > 0.5), 0, 0)
    joints = {
        "thighL": (-amplitude * s_, 0, 0),
        "thighR": (amplitude * s_, 0, 0),
        "kneeL": (max(0.0, amplitude * 1.2 * s_), 0, 0),
        "kneeR": (max(0.0, -amplitude * 1.2 * s_), 0, 0),
        "shoulderL": (amplitude * 0.9 * s_, 0, 0),
        "elbowL": elbow,
        "chest": (RUN_LEAN * ramp if code == "run_to" else 0.0, 0, 0),
    }
    if not holding:
        joints["shoulderR"], joints["elbowR"] = (-amplitude * 0.9 * s_, 0, 0), elbow
    return joints, STEP_BOB[code] * ramp * abs(math.sin(phase))


def _point_on_path(path, u):
    """Point at fraction u (by length) of a polyline, and the yaw of the segment holding it."""
    lengths = [math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in pairwise(path)]
    remaining = max(0.0, min(1.0, u)) * sum(lengths)
    for i, length in enumerate(lengths):
        if remaining <= length or i == len(lengths) - 1:
            a, b = path[i], path[i + 1]
            t = remaining / length if length > 1e-9 else 1.0
            return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t), yaw_for(
                b[0] - a[0], b[1] - a[1]
            )
        remaining -= length
    return tuple(path[-1]), 0.0


def walk_away_pose(job, seconds, holding):
    """`walk_away` at second `seconds` of the job: back off along path[0] -> path[1] (face towards
    the target or camera, feet stepping backwards), turn away for the rest of the gap, then walk
    path[1:]. Returns ((x, y), yaw, joints, bob)."""
    path, back, walk = job["path"], job["backSec"], job["walkSec"]
    turn = max(0.0, (job["endSec"] - job["startSec"]) - back - walk)
    (x0, y0), (x1, y1) = path[0], path[1]
    yaw0 = math.radians(job["from"]["yawDeg"])
    back_length = math.hypot(x1 - x0, y1 - y0)
    yaw_back = yaw_for(x0 - x1, y0 - y1) if back_length > 1e-6 else yaw0
    leave = path[1:]
    leaving = walk > 0 and len(leave) > 1
    yaw_leave = _point_on_path(leave, 0.0)[1] if leaving else math.radians(job["to"]["yawDeg"])
    if back > 0 and seconds < back:
        u = seconds / back
        yaw = yaw0 + _short_angle(yaw0, yaw_back) * ease(
            min(1.0, seconds / min(TURN_BEFORE_SEC, back / 3))
        )
        ramp = min(1.0, seconds / STEP_RAMP_SEC, (back - seconds) / STEP_RAMP_SEC)
        joints, bob = walk_cycle(
            -seconds, "walk_to", holding, ramp, back_length / back / WALK_SPEED
        )  # reversed phase
        return (x0 + (x1 - x0) * u, y0 + (y1 - y0) * u), yaw, joints, bob
    if seconds < back + turn or not leaving:
        w = ease(min(1.0, (seconds - back) / turn)) if turn > 1e-6 else 1.0
        return (x1, y1), yaw_back + _short_angle(yaw_back, yaw_leave) * w, {}, 0.0
    t3 = seconds - back - turn
    (x, y), yaw = _point_on_path(leave, t3 / walk)
    ramp = min(1.0, t3 / STEP_RAMP_SEC, (walk - t3) / STEP_RAMP_SEC)
    joints, bob = walk_cycle(t3, "walk_to", holding, ramp, job.get("speedRatio", 1.0))
    return (x, y), yaw, joints, bob


def plan_motions(card):
    """({dummy id: plan}, {prop id: (holder at start, [(second, new holder)])}) from the card."""
    plans = {}
    total = (card["frame"]["frameCount"] - 1) / card["frame"]["fps"]
    motions = card.get("motions", [])
    for dummy in card["dummies"]:
        jobs = sorted(
            (m for m in motions if m["characterId"] == dummy["id"]), key=lambda m: m["startSec"]
        )
        if dummy["pose"] == "walking":  # `track`: walk 2 m through the whole shot as one walk_to
            start = {
                "foot": dummy["walk"]["from"],
                "yawDeg": dummy["yawDeg"],
                "pose": "standing",
                "z0": dummy["z0"],
            }
            jobs = [
                {
                    "code": "walk_to",
                    "startSec": 0.0,
                    "endSec": total,
                    "walkSec": total,
                    "from": start,
                    "to": dict(start, foot=dummy["walk"]["to"]),
                    "target": None,
                    "handoverSec": None,
                }
            ]
        plans[dummy["id"]] = {
            "dummy": dummy,
            "jobs": jobs,
            "speech": [
                (p["startSec"], p["endSec"])
                for p in card.get("speaking", [])
                if p["characterId"] == dummy["id"]
            ],
            "receives": [
                m for m in motions if m["code"] == "hand_over" and m["target"]["id"] == dummy["id"]
            ],
            "hugged": [
                m
                for m in motions
                if m["code"] == "hug" and m.get("response", {}).get("characterId") == dummy["id"]
            ],
        }
    handovers = {}
    for prop in card["props"]:
        switches = [
            (m["handoverSec"], m["characterId"] if m["code"] == "pick_up" else m["target"]["id"])
            for m in motions
            if m.get("propId") == prop["id"] and m.get("handoverSec") is not None
        ]
        handovers[prop["id"]] = (prop.get("heldAtStart"), sorted(switches))
    for plan in plans.values():
        plan["held"] = list(handovers.values())
    return plans, handovers


def holder_at(handover, t):
    holder, switches = handover
    for second, who in switches:
        if t >= second:
            holder = who
    return holder


def is_animated(plan):
    """A dummy with a job, a spoken line, a prop to receive, a hug to answer, or that ever holds a
    prop, is keyed on every frame."""
    who = plan["dummy"]["id"]
    held = any(
        first == who or any(w == who for _, w in switches) for first, switches in plan["held"]
    )
    return bool(plan["jobs"] or plan["speech"] or plan["receives"] or plan["hugged"]) or held


def _handover_fraction(job):
    """Fraction of the gesture (after the walk) at which the prop changes hands."""
    return (job["handoverSec"] - job["startSec"] - job["walkSec"]) / max(
        1e-6, job["endSec"] - job["startSec"] - job["walkSec"]
    )


def gesture(job, u, joints, x, y, z, yaw, height, pose, holding):
    """The gesture of `job` at fraction u of its post-walk part: (z, root lean, right hand busy)."""
    code, target = job["code"], job["target"]
    z0, z1 = job["from"]["z0"], job["to"]["z0"]
    lean = 0.0
    busy = False
    seated = [
        j for j in SEATED if not (holding and j in RIGHT_ARM)
    ]  # a held prop keeps the right arm
    if code == "sit_down":
        e = ease(u)
        z = z0 + (z1 - z0) * e
        _mix_joints(joints, SEATED, e, seated)
        _add(joints, "chest", (0.35 * math.sin(math.pi * u), 0, 0))
    elif code == "stand_up" and job["from"]["pose"] == "fallen":
        e = ease(u)
        z, lean = z0 + (z1 - z0) * e, math.pi / 2 * (1 - e)
        _mix_joints(joints, {}, e, FALLEN)
        for name, v in (("thighL", -0.8), ("thighR", -0.8), ("kneeL", 1.2), ("kneeR", 1.2)):
            _add(joints, name, (v * math.sin(math.pi * u), 0, 0))
    elif code == "stand_up":
        e = ease(u)
        z = z0 + (z1 - z0) * e
        _mix_joints(joints, {}, e, seated)
        _add(joints, "chest", (0.35 * math.sin(math.pi * u), 0, 0))
    elif code == "fall":
        e = u * u  # accelerating
        z, lean = z0 + (z1 - z0) * e, math.pi / 2 * e
        _mix_joints(joints, FALLEN, ease(u))
        for name in ("kneeL", "kneeR"):
            _add(joints, name, (0.4 * math.sin(math.pi * u), 0, 0))
    elif code == "nod":
        nod = (job.get("amplitude") or 0.32) * (1 - math.cos(4 * math.pi * u)) / 2
        _add(joints, "neck", (nod, 0, 0))
        _add(joints, "chest", (0.4 * nod, 0, 0))
    elif code == "raise_arms":
        w = _envelope(u, 0.3, 0.7)
        _mix_joints(
            joints,
            {
                "shoulderL": (-2.9, 0.3, 0),
                "shoulderR": (-2.9, -0.3, 0),
                "elbowL": (-0.2, 0, 0),
                "elbowR": (-0.2, 0, 0),
            },
            w,
        )
        busy = w > 0
    elif code in ("point_at", "reach"):
        rise, fall = (0.25, 0.75) if code == "point_at" else (0.35, 0.65)
        w = _envelope(u, rise, fall)
        aimed = (
            aim_arm(x, y, z, yaw, height, -1, target["position"])
            if target
            else (-math.pi / 2, 0.0, 0.0)
        )
        _mix_joints(joints, {"shoulderR": aimed, "elbowR": (0, 0, 0)}, w)
        if code == "reach":
            _add(joints, "chest", (0.15 * w, 0, 0))
        busy = w > 0
    elif code == "pick_up":
        p = _handover_fraction(job)
        h = target["position"][2]
        bend = (
            max(0.15, min(1.0, (1.0 - h) / 0.9)) * 1.2
        )  # the lower the object, the deeper the bend
        squat = 0.0 if pose == "seated" else max(0.0, min(1.0, (0.8 - h) / 0.7))
        w = ease(u / p) if u < p else 1 - ease((u - p) / (1 - p))
        _add(joints, "chest", (bend * w, 0, 0))
        for (
            side
        ) in "LR":  # thigh 1.1 rad and shin 2 rad keep the feet under the hip, which drops ~0.4 m
            _add(joints, f"thigh{side}", (-1.1 * squat * w, 0, 0))
            _add(joints, f"knee{side}", (2.0 * squat * w, 0, 0))
        z -= 0.4 * squat * w
        reaching = {
            "shoulderR": aim_arm(x, y, z0 - 0.4 * squat, yaw, height, -1, target["position"], bend),
            "elbowR": (0, 0, 0),
        }
        if u < p:
            _mix_joints(joints, reaching, ease(u / p))
        else:
            joints.update(reaching)
            _mix_joints(joints, HOLDING_ARM, ease((u - p) / (1 - p)))
        busy = True
    elif code == "hug":
        busy = hug_arms(joints, x, y, z, yaw, height, target["position"], u) > 0
    elif code == "hand_over":
        p = _handover_fraction(job)
        mx, my = target["position"][:2]  # offer halfway between the two, at waist height
        aimed = aim_arm(x, y, z, yaw, height, -1, ((x + mx) / 2, (y + my) / 2, z + 0.6 * height))
        if u < p:
            _mix_joints(
                joints, {"shoulderR": aimed, "elbowR": (0, 0, 0)}, ease(min(1.0, u / (0.8 * p)))
            )
        else:
            joints["shoulderR"], joints["elbowR"] = aimed, (0.0, 0.0, 0.0)
            _mix_joints(joints, {}, ease((u - p) / (1 - p)), RIGHT_ARM)
        busy = True
    return z, lean, busy


def pose_at(plan, t):
    """Dummy pose at second t: ((x, y, z), (lean, 0, yaw), {joint: euler})."""
    dummy = plan["dummy"]
    height = dummy["height"]
    x, y = dummy["foot"]
    yaw, z, pose = math.radians(dummy["yawDeg"]), dummy["z0"], dummy["pose"]
    active = None
    finished_at = 0.0  # when this dummy's latest job ended
    for job in plan["jobs"]:
        if t >= job["endSec"]:
            x, y = job["to"]["foot"]
            yaw, z, pose = math.radians(job["to"]["yawDeg"]), job["to"]["z0"], job["to"]["pose"]
            finished_at = job["endSec"]
            continue
        if t >= job["startSec"]:
            active = job
        break
    holding = any(holder_at(v, t) == dummy["id"] for v in plan["held"])
    joints = {}
    lean = 0.0
    if pose == "seated":
        joints.update(SEATED)
    elif pose == "fallen":
        joints.update(FALLEN)
        lean = math.pi / 2
    if holding and pose != "fallen":
        joints.update(HOLDING_ARM)
    right_busy = left_busy = False
    if active is not None and active["code"] == "walk_away":
        pose, z = active["from"]["pose"], active["from"]["z0"]
        (x, y), yaw, steps, bob = walk_away_pose(active, t - active["startSec"], holding)
        joints.update(steps)
        z += bob
    elif active is not None:
        job = active
        tau, span, walk = (
            t - job["startSec"],
            max(1e-6, job["endSec"] - job["startSec"]),
            job["walkSec"],
        )
        (x0, y0), (x1, y1) = job["from"]["foot"], job["to"]["foot"]
        yaw0, yaw1 = math.radians(job["from"]["yawDeg"]), math.radians(job["to"]["yawDeg"])
        pose = job["from"]["pose"]
        z = job["from"]["z0"]
        path = job.get("path") or [[x0, y0], [x1, y1]]  # a detour round people and blocks
        walk_heading = (
            _point_on_path(path, 1.0)[1]
            if walk > 0 and abs(x1 - x0) + abs(y1 - y0) > 1e-6
            else yaw0
        )
        step_code = "run_to" if job["code"] == "run_to" else "walk_to"
        if walk > 0 and tau < walk:
            (x, y), segment_yaw = _point_on_path(path, tau / walk)
            yaw = yaw0 + _short_angle(yaw0, segment_yaw) * ease(
                min(1.0, tau / min(TURN_BEFORE_SEC, walk / 3))
            )
            ramp = min(1.0, tau / STEP_RAMP_SEC, (walk - tau) / STEP_RAMP_SEC)
            steps, bob = walk_cycle(tau, step_code, holding, ramp, job.get("speedRatio", 1.0))
            joints.update(steps)
            z += bob
        else:
            x, y = x1, y1
            tau2, span2 = tau - walk, max(1e-6, span - walk)
            u = min(1.0, tau2 / span2)
            yaw_start = walk_heading if walk > 0 else yaw0
            if job["code"] == "turn_to":
                yaw = yaw_start + _short_angle(yaw_start, yaw1) * ease(u)
                lag = _short_angle(yaw, yaw1)
                _add(joints, "neck", (0, 0, 0.6 * lag * min(1.0, u / 0.15)))
                if abs(_short_angle(yaw_start, yaw1)) > math.radians(20) and pose == "standing":
                    shuffle = 0.12 * math.sin(2 * math.pi * 2 * u)
                    _add(joints, "thighL", (-max(0.0, shuffle), 0, 0))
                    _add(joints, "thighR", (-max(0.0, -shuffle), 0, 0))
            else:
                yaw = yaw_start + _short_angle(yaw_start, yaw1) * ease(
                    min(1.0, tau2 / min(TURN_AFTER_SEC, span2))
                )
                if job["code"] not in ("walk_to", "run_to"):
                    z, lean, right_busy = gesture(
                        job, u, joints, x, y, z, yaw, height, pose, holding
                    )
                    left_busy = right_busy and job["code"] == "hug"
    # Being hugged: when free, turn to the hugger over the first HUG_RESPONSE_TURN_SEC of the
    # gesture and answer with open arms.
    if active is None:
        for job in plan["hugged"]:
            g0 = job["startSec"] + job["walkSec"]
            if t < g0 or finished_at > g0 + 1e-6:
                continue
            span2 = max(1e-6, job["endSec"] - g0)
            y0_, y1_ = (
                math.radians(job["response"]["fromYawDeg"]),
                math.radians(job["response"]["toYawDeg"]),
            )
            yaw = y0_ + _short_angle(y0_, y1_) * ease(
                min(1.0, (t - g0) / min(HUG_RESPONSE_TURN_SEC, span2))
            )
            if t < job["endSec"]:
                bx, by = job["to"]["foot"]
                other = (
                    bx,
                    by,
                    job["to"]["z0"] + LANDMARK["shoulder"] * height - HUG_RESPONSE_DROP,
                )
                right_busy = left_busy = (
                    hug_arms(joints, x, y, z, yaw, height, other, (t - g0) / span2) > 0
                )
    # Receiving a prop: the right hand reaches out half a second before the switch, then returns
    # to the holding pose.
    if not right_busy:
        for job in plan["receives"]:
            g = job["handoverSec"]
            if g - 0.5 <= t <= g + 0.3:
                (ax, ay), (bx, by) = job["to"]["foot"], (x, y)
                middle = ((ax + bx) / 2, (ay + by) / 2, z + 0.6 * height)
                offered = {
                    "shoulderR": aim_arm(x, y, z, yaw, height, -1, middle),
                    "elbowR": (0.0, 0.0, 0.0),
                }
                if t <= g:
                    _mix_joints(joints, offered, ease((t - g + 0.5) / 0.5), RIGHT_ARM)
                else:
                    joints.update(offered)
                    _mix_joints(joints, HOLDING_ARM, ease((t - g) / 0.3), RIGHT_ARM)
    # Speaking: a light head shake and the left hand lifting, added on top of the current pose.
    for a, b in plan["speech"]:
        if a <= t < b and pose != "fallen":
            w = min(1.0, (t - a) / 0.15, (b - t) / 0.15)
            tt = t - a
            _add(
                joints,
                "neck",
                (
                    0.06 * w * math.sin(2 * math.pi * 3.3 * tt),
                    0,
                    0.13 * w * math.sin(2 * math.pi * 2.0 * tt),
                ),
            )
            if left_busy:  # hugging: both hands busy, only the head moves
                continue
            lift = w * (0.5 - 0.5 * math.cos(2 * math.pi * 0.8 * tt))
            _add(joints, "shoulderL", (-0.3 * lift, 0, 0))
            _add(joints, "elbowL", (-0.6 * lift, 0, 0))
    return (x, y, z), (lean, 0.0, yaw), joints


class Actor(NamedTuple):
    rig: dict
    foot: Callable[[int], tuple[float, float]]  # frame -> (x, y)
    yaw: float
    height: float
    z0: float


def build_dummies(card, frame_count):
    """{id: Actor} of the main cast; extras are unlabelled instances of one template."""
    fps = card["frame"]["fps"]
    plans, _ = plan_motions(card)
    for job in card.get("motions", []):
        if job.get("stool"):
            box(
                f"stool_{job['characterId']}",
                tuple(job["stool"]["center"]),
                tuple(job["stool"]["dimensions"]),
                WALL_GREY,
            )
    actors = {}
    for dummy in card["dummies"]:
        height, z0 = dummy["height"], dummy["z0"]
        rig = build_dummy(dummy["id"], height, tuple(c / 255 for c in dummy["color"]))
        yaw = math.radians(dummy["yawDeg"])
        plan = plans[dummy["id"]]
        if is_animated(plan):
            trail = []
            previous_yaw = None
            for f in range(frame_count):
                (x, y, z), (lean, roll, yaw_f), joints = pose_at(plan, f / fps)
                if previous_yaw is not None:  # keep yaw continuous across +-180 deg
                    yaw_f = previous_yaw + _short_angle(previous_yaw, yaw_f)
                previous_yaw = yaw_f
                keyframe(rig["root"], "location", f, (x, y, z))
                keyframe(rig["root"], "rotation_euler", f, (lean, roll, yaw_f))
                for joint in JOINTS:
                    keyframe(rig[joint], "rotation_euler", f, joints.get(joint, (0.0, 0.0, 0.0)))
                trail.append((x, y))

            def foot(f, trail=trail):
                return trail[max(0, min(len(trail) - 1, f))]
        else:
            x, y = dummy["foot"]
            rig["root"].location, rig["root"].rotation_euler = (x, y, z0), (0, 0, yaw)
            if dummy["pose"] == "seated":
                set_pose(rig, 0, **SEATED)

            def foot(f, x=x, y=y):
                return (x, y)

        actors[dummy["id"]] = Actor(rig, foot, yaw, height, z0)
    extras = card["extras"]
    if extras["people"]:
        # One template in its own collection plus an instance per extra: 30 extras build in under
        # a second instead of ~20 s of bpy.ops calls. The MATERIAL colour type keeps their colour.
        template = bpy.data.collections.new("extra_template")
        rig = build_dummy("extra", extras["height"], tuple(c / 255 for c in extras["color"]))
        for obj in [rig["root"], *rig["root"].children_recursive]:
            template.objects.link(obj)
            _collection().objects.unlink(obj)
        for i, person in enumerate(extras["people"]):
            instance = bpy.data.objects.new(f"extra_{i:02d}", None)
            instance.instance_type, instance.instance_collection = "COLLECTION", template
            _collection().objects.link(instance)
            instance.location = (person["foot"][0], person["foot"][1], 0.0)
            instance.rotation_euler = (0, 0, math.radians(person["yawDeg"]))
    return actors


def _actor_tuple(actors, who, f=0):
    a = actors[who]
    return a.foot(f), a.yaw, a.height, a.z0


def initial_view(card, actors):
    """The camera view at frame 0 from the card's placement function."""
    placement = card["camera"]["placement"]
    p = placement["params"]
    function = placement["function"]
    if function == "single":
        foot, yaw, height, z0 = _actor_tuple(actors, p["characterId"])
        return single_shot(
            foot,
            yaw,
            height,
            p["size"],
            p["angle"],
            p["headingDeg"],
            f=p["focalMm"],
            screen_x=p["screenX"],
            z0=z0,
            ground=0.0,
        )
    if function == "over_shoulder":
        return over_shoulder_shot(
            _actor_tuple(actors, p["foregroundId"]),
            _actor_tuple(actors, p["subjectId"]),
            p["size"],
            side=p["side"],
        )
    if function in ("group", "establishing"):
        c, look_at, f, shift = group_shot(
            p["points"], p["azimuthDeg"], p["pitchDeg"], p["focalMm"], p["margin"]
        )
        if (
            c.z < CAMERA_MIN_HEIGHT
        ):  # a low angle must not put the camera underground; keep the look-at point
            c = Vector((c.x, c.y, CAMERA_MIN_HEIGHT))
        return c, look_at, f, shift
    if function == "insert":
        return insert_shot(p["center"], p["width"], p["focalMm"], p["upDeg"])
    if function == "pov":
        c = Vector(p["eye"])
        yaw, pitch = math.radians(p["yawDeg"]), math.radians(p["pitchDeg"])
        forward = _facing(yaw) * math.cos(pitch) - Vector((0, 0, 1)) * math.sin(pitch)
        return c, c + forward * 5.0, p["focalMm"], 0.0
    raise ValueError(f"unknown placement function: {function}")


def _right_of(c, look_at):
    d = Vector(look_at) - Vector(c)
    v = Vector((d.y, -d.x, 0.0))
    return v.normalized() if v.length > 1e-6 else Vector((1.0, 0.0, 0.0))


def _rotate_about(v, axis, angle):
    return Matrix.Rotation(angle, 3, axis) @ v


def motion_function(card, actors, start):
    """(t in [0, 1], frame) -> view, or None for a camera that does not move. For a single-character
    placement, dolly/crane/orbit/follow re-solve the shot every frame at the subject's CURRENT place
    (heading kept as at frame 0), so a walking subject keeps its framing; other placements transform
    the start view."""
    placement, motion = card["camera"]["placement"], card["camera"]["motion"]
    function, p = motion["function"], motion["params"]
    c0, look0, f0, shift0 = Vector(start[0]), Vector(start[1]), start[2], start[3]
    on_subject = placement["function"] == "single"
    pp = placement["params"]
    if function in ("static", "handheld"):
        return None
    subject_foot = actors[pp["characterId"]].foot if on_subject else None

    def solved(size, angle, heading, f=None, foot=None):
        foot0, yaw, height, z0 = _actor_tuple(actors, pp["characterId"])
        return single_shot(
            foot or foot0,
            yaw,
            height,
            size,
            angle,
            heading,
            f=f,
            screen_x=pp["screenX"],
            z0=z0,
            ground=0.0,
        )

    if function == "dolly":
        if on_subject:
            f = LADDER[p["sizeFrom"]][4]
            return lambda t, k: solved(
                blend_size(p["sizeFrom"], p["sizeTo"], ease(t)),
                pp["angle"],
                pp["headingDeg"],
                f=f,
                foot=subject_foot(k),
            )
        return lambda t, k: (
            look0 + (c0 - look0) * (1 + (p["factor"] - 1) * ease(t)),
            look0,
            f0,
            shift0,
        )
    if function == "pan":

        def pan(t, k):
            d = _rotate_about(
                look0 - c0, Vector((0, 0, 1)), math.radians(p["degrees"]) * (0.5 - ease(t))
            )
            return c0, c0 + d, f0, shift0

        return pan
    if function == "tilt":

        def tilt(t, k):
            d = _rotate_about(
                look0 - c0, _right_of(c0, look0), math.radians(p["degrees"]) * (0.5 - ease(t))
            )
            return c0, c0 + d, f0, shift0

        return tilt
    if function == "follow":
        who = p["characterId"]
        if who and on_subject and who == pp["characterId"]:
            return lambda t, k: solved(
                pp["size"], pp["angle"], pp["headingDeg"], f=f0, foot=actors[who].foot(k)
            )
        if who:
            foot = actors[who].foot
            x0, y0 = foot(0)

            def follow(t, k):
                x, y = foot(k)
                d = Vector((x - x0, y - y0, 0.0))
                return c0 + d, look0 + d, f0, shift0

            return follow
        heading = Vector(((look0 - c0).x, (look0 - c0).y, 0.0))
        heading = heading.normalized() if heading.length > 1e-6 else Vector((0.0, 1.0, 0.0))
        return lambda t, k: (
            c0 + heading * p["distanceM"] * t,
            look0 + heading * p["distanceM"] * t,
            f0,
            shift0,
        )
    if function == "truck":
        right = _right_of(c0, look0)
        return lambda t, k: (
            c0 + right * p["distanceM"] * (ease(t) - 0.5),
            look0 + right * p["distanceM"] * (ease(t) - 0.5),
            f0,
            shift0,
        )
    if function == "crane":
        if on_subject:
            a0, a1 = ANGLE_DEG[p["angleFrom"]], ANGLE_DEG[p["angleTo"]]
            return lambda t, k: solved(
                blend_size(p["sizeFrom"], p["sizeTo"], ease(t)),
                a0 + (a1 - a0) * ease(t),
                pp["headingDeg"],
                foot=subject_foot(k),
            )
        return lambda t, k: (c0 + Vector((0, 0, p["liftM"] * ease(t))), look0, f0, shift0)
    if function == "drone":
        back = Vector(((c0 - look0).x, (c0 - look0).y, 0.0))
        back = back.normalized() if back.length > 1e-6 else Vector((0.0, -1.0, 0.0))
        run = p["heightM"] / math.tan(math.radians(p["pitchDeg"]))

        def fly(t, k):
            drift = p["driftM"] * (ease(t) - 0.5)
            shift = -back * drift
            return (
                look0 + back * run + Vector((0, 0, p["heightM"])) + shift,
                look0 + shift,
                f0,
                shift0,
            )

        return fly
    if function == "orbit":
        if on_subject:
            h0, h1 = p["headingFromDeg"], p["headingToDeg"]
            return lambda t, k: solved(
                pp["size"], pp["angle"], h0 + (h1 - h0) * ease(t), f=f0, foot=subject_foot(k)
            )
        return lambda t, k: (
            look0
            + _rotate_about(
                c0 - look0, Vector((0, 0, 1)), math.radians(p["degrees"]) * (ease(t) - 0.5)
            ),
            look0,
            f0,
            shift0,
        )
    raise ValueError(f"unknown camera motion function: {function}")


def _bodies(actors, k):
    """Standing axis (x, y, foot z, top z) of every main dummy at frame k."""
    out = []
    for a in actors.values():
        x, y = a.foot(k)
        out.append((x, y, a.z0, a.z0 + a.height))
    return out


def _distance_to_bodies(c, bodies):
    return min(
        (
            math.hypot(math.hypot(c.x - x, c.y - y), max(0.0, z0 - c.z, c.z - z1))
            for x, y, z0, z1 in bodies
        ),
        default=math.inf,
    )


def keep_distance(view, bodies, distance):
    """Keep the camera at least `distance` from every dummy. Too close: back up along the look
    axis (look-at point kept) and scale the focal length with the distance, which keeps the size
    of the shot; if backing up is impossible (a dummy behind, underground) go forward. A coarse
    5 % scan, then bisection to the nearest allowed place so it is continuous between frames.
    Nowhere allowed: keep the view."""
    c, look_at, f, shift = Vector(view[0]), Vector(view[1]), view[2], view[3]
    if not distance or _distance_to_bodies(c, bodies) >= distance:
        return view

    def at(s):
        return look_at + (c - look_at) * s

    def allowed(s):
        cs = at(s)
        return cs.z >= CAMERA_MIN_HEIGHT and _distance_to_bodies(cs, bodies) >= distance

    for step, steps in ((0.05, 60), (-0.05, 16)):  # back up to 4x the distance, forward to 0.2x
        previous = 1.0
        for i in range(1, steps + 1):
            s = 1.0 + step * i
            if allowed(s):
                lo, hi = previous, s  # lo not allowed, hi allowed
                for _ in range(24):
                    mid = (lo + hi) / 2
                    lo, hi = (lo, mid) if allowed(mid) else (mid, hi)
                return at(hi), look_at, f * hi, shift
            previous = s
    return view


def place_camera(card, actors, frame_count):
    start = initial_view(card, actors)
    camera = make_camera("camera", start[0], start[1], start[2])
    apply_view(camera, start)
    move = motion_function(card, actors, start)
    distance = card["camera"].get("minDistanceM", 0.0)
    views = [
        keep_distance(
            move(k / max(1, frame_count - 1), k) if move is not None else start,
            _bodies(actors, k),
            distance,
        )
        for k in range(frame_count)
    ]
    if move is not None or any(
        v is not start for v in views
    ):  # a still camera is keyed only when it had to back up
        for k, view in enumerate(views):
            apply_view(camera, view, k)
    if card["camera"]["motion"]["function"] == "handheld":
        p = card["camera"]["motion"]["params"]
        handheld_shake(camera, 0, frame_count - 1, p["amplitude"], p["rate"], 0, 0, p["seed"])
    return camera


def build_labels(card, actors, camera, font):
    """Block objects plus labels (dummy names, fixed pieces, props; no other text). A null label
    means the send version: no text at all, blocks still built. Each prop is a block and a label
    parented to an anchor empty that follows the hand when the prop is held. Returns {prop id:
    anchor}."""
    for dummy in card["dummies"]:
        rig = actors[dummy["id"]].rig
        if dummy["label"]:
            build_label(
                f"label_{dummy['id']}",
                dummy["label"],
                (0, 0, (1 - EYE) * dummy["height"] + 0.1),
                0.13,
                camera,
                font,
                follow=rig["head"],
            )
    for i, piece in enumerate(card["fixedPieces"]):
        box(
            f"piece_{i}",
            tuple(piece["center"]),
            tuple(piece["dimensions"]),
            tuple(c / 255 for c in PIECE_COLOR),
        )
        x, y, z = piece["center"]
        if piece["label"]:
            build_label(
                f"label_piece_{i}",
                piece["label"],
                (x, y, z + piece["dimensions"][2] / 2 + 0.06),
                0.15,
                camera,
                font,
            )
    anchors = {}
    for i, prop in enumerate(card["props"]):
        anchor = empty(f"prop_{i}_anchor", tuple(prop["center"]))
        box(
            f"prop_{i}",
            tuple(prop["center"]),
            tuple(prop["dimensions"]),
            tuple(c / 255 for c in PROP_COLOR),
            parent=anchor,
        )
        if prop["label"]:
            build_label(
                f"label_prop_{i}",
                prop["label"],
                (0, 0, prop["dimensions"][2] / 2 + 0.03),
                0.05,
                camera,
                font,
                parent=anchor,
            )
        anchors[prop["id"]] = anchor
    return anchors


def animate_props(scene, card, actors, anchors, frame_count):
    """A held prop follows the holder's right hand on every frame (holder from heldAtStart plus
    the switch times); an unheld prop stays where it is. Hand positions are read after the
    dummies are keyed."""
    _, handovers = plan_motions(card)
    moving = {p: v for p, v in handovers.items() if v[0] is not None or v[1]}
    if not moving:
        return
    fps = card["frame"]["fps"]
    rest = {p["id"]: Vector(p["center"]) for p in card["props"]}

    def place_of(prop, who):
        return (
            rest[prop]
            if who is None
            else actors[who].rig["handR"].matrix_world.translation - Vector((0, 0, 0.02))
        )

    for f in range(frame_count):
        scene.frame_set(f)
        t = f / fps
        for prop, handover in moving.items():
            place = place_of(prop, holder_at(handover, t))
            upcoming = next(
                ((g, who) for g, who in handover[1] if g - HANDOVER_SLIDE_SEC <= t < g), None
            )  # about to switch: slide
            if upcoming is not None:
                place = place.lerp(
                    place_of(prop, upcoming[1]),
                    ease((t - upcoming[0] + HANDOVER_SLIDE_SEC) / HANDOVER_SLIDE_SEC),
                )
            keyframe(anchors[prop], "location", f, tuple(place))
    scene.frame_set(0)


def setup_render(scene, frame):
    scene.render.engine = "BLENDER_WORKBENCH"
    scene.render.resolution_x, scene.render.resolution_y, scene.render.resolution_percentage = (
        frame["width"],
        frame["height"],
        100,
    )
    scene.render.fps, scene.render.fps_base = frame["fps"], 1.0
    scene.view_settings.view_transform, scene.view_settings.look = "Standard", "None"
    scene.view_settings.exposure = (
        2.2  # the "Default" studio light puts only ~1/3 on horizontal faces
    )
    shading = scene.display.shading
    shading.light, shading.color_type = "STUDIO", "MATERIAL"
    shading.show_shadows, shading.shadow_intensity = True, 0.3
    shading.show_cavity, shading.cavity_type = True, "BOTH"
    shading.show_object_outline = True
    # Direction TO the shadow light in camera space (x right, y up, z towards the viewer): above,
    # right and in front, so the floor is lit and dummies cast shadows on it. A negative y lights
    # from below and leaves the whole floor in shadow.
    scene.display.light_direction = (0.32, 0.81, 0.49)
    scene.display.render_aa = "5"  # 5 AA samples: enough for a preview and faster on CPU rendering
    world = scene.world or bpy.data.worlds.new("sky")
    world.color = SKY_COLOR
    scene.world = world


def compose_video(scene, camera, frame, frame_count, out):
    """A second scene that only records the H.264 file: one strip of the 3D scene, no text."""
    composed = bpy.data.scenes.new("compose")
    (
        composed.render.resolution_x,
        composed.render.resolution_y,
        composed.render.resolution_percentage,
    ) = frame["width"], frame["height"], 100
    composed.render.fps, composed.render.fps_base = frame["fps"], 1.0
    composed.frame_start, composed.frame_end = 0, frame_count - 1
    # The colour management of the MP4 belongs to this scene (the 3D strip enters linear), so copy
    # it all from the 3D scene including exposure; without it the video is darker than a render.
    for key in ("view_transform", "look", "exposure", "gamma"):
        setattr(composed.view_settings, key, getattr(scene.view_settings, key))
    editor = composed.sequence_editor_create()
    strip = editor.strips.new_scene(name="blocks", scene=scene, channel=1, frame_start=0)
    strip.scene_input, strip.scene_camera = "CAMERA", camera
    composed.render.use_sequencer, composed.render.use_compositing = True, False
    composed.render.image_settings.file_format = "FFMPEG"
    composed.render.ffmpeg.format, composed.render.ffmpeg.codec = "MPEG4", "H264"
    composed.render.ffmpeg.constant_rate_factor = "HIGH"
    composed.render.ffmpeg.gopsize = frame["fps"]
    composed.render.ffmpeg.audio_codec = "NONE"
    composed.render.use_file_extension = False
    composed.render.filepath = out
    return composed


def check_framing(scene, camera, card, actors):
    """Is each framed dummy's head in the frame, in front of the camera, at the first, middle and
    last frame."""
    n = card["frame"]["frameCount"]
    checks = []
    for who in [d["id"] for d in card["dummies"] if d["inFrame"]]:
        head = actors[who].rig["head"]
        for f in sorted({0, n // 2, n - 1}):
            scene.frame_set(f)
            p = world_to_camera_view(scene, camera, head.matrix_world.translation)
            checks.append(
                {
                    "id": who,
                    "frame": f,
                    "x": round(p.x, 4),
                    "y": round(p.y, 4),
                    "depth": round(p.z, 3),
                    "inView": bool(0.0 <= p.x <= 1.0 and 0.0 <= p.y <= 1.0 and p.z > 0),
                }
            )
    return checks


def _in_view(scene, camera, obj):
    """Does any corner of the object's bounding box project into the frame, before the camera."""
    for corner in obj.bound_box:
        p = world_to_camera_view(scene, camera, obj.matrix_world @ Vector(corner))
        if 0.0 <= p.x <= 1.0 and 0.0 <= p.y <= 1.0 and p.z > camera.data.clip_start:
            return True
    return False


def hide_off_frame(scene, camera, card, actors):
    """Dummies declared outside the frame (`inFrame: false`) that still enter the camera's view at
    some frame (sampled every 3 frames) are hidden from the render, their name label too. Returns
    the hidden ids."""
    outside = [d["id"] for d in card["dummies"] if not d["inFrame"]]
    if not outside:
        return []
    n = card["frame"]["frameCount"]
    meshes = {
        who: [o for o in actors[who].rig["root"].children_recursive if o.type == "MESH"]
        for who in outside
    }
    seen = set()
    for f in sorted(set(range(0, n, 3)) | {n - 1}):
        scene.frame_set(f)
        seen |= {
            who
            for who in outside
            if who not in seen and any(_in_view(scene, camera, o) for o in meshes[who])
        }
    for who in seen:
        for obj in actors[who].rig["root"].children_recursive:
            obj.hide_render = True
        label = scene.objects.get(f"label_{who}")
        if label is not None:
            label.hide_render = True
    scene.frame_set(0)
    return sorted(seen)


def build_scene(card, out, font):
    """Card -> the 3D scene (stage, animated dummies, camera, labels, props following hands) plus
    the scene that records the MP4 to `out`; not rendered. Returns (scene, camera, actors,
    composed scene). The Blender checks inspect the scene through this without rendering."""
    global SENSOR_MM, FRAME, ASPECT
    frame = card["frame"]
    SENSOR_MM, FRAME = float(frame["sensorMm"]), (frame["width"], frame["height"])
    ASPECT = FRAME[0] / FRAME[1]
    n = frame["frameCount"]
    if not os.path.isfile(font):
        raise FileNotFoundError(f"label font not found: {font}")

    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene = bpy.context.scene
    scene.name = "blocks"
    scene.frame_start, scene.frame_end = 0, n - 1
    setup_render(scene, frame)
    font_data = bpy.data.fonts.load(font)
    build_stage(card["stage"])
    actors = build_dummies(card, n)
    camera = place_camera(card, actors, n)
    scene.camera = camera
    anchors = build_labels(card, actors, camera, font_data)
    animate_props(scene, card, actors, anchors, n)
    scene["hidden_off_frame"] = hide_off_frame(scene, camera, card, actors)
    composed = compose_video(scene, camera, frame, n, out)
    return scene, camera, actors, composed


def main():
    args = sys.argv[sys.argv.index("--") + 1 :]
    card_path, out, font = args[0], os.path.abspath(args[1]), args[2]
    t0 = time.time()
    with open(card_path, encoding="utf-8") as f:
        card = json.load(f)
    frame = card["frame"]
    scene, camera, actors, composed = build_scene(card, out, font)
    checks = check_framing(scene, camera, card, actors)
    t1 = time.time()
    bpy.ops.render.render(animation=True, scene=composed.name)
    t2 = time.time()

    if not os.path.isfile(out):
        raise RuntimeError(f"Blender wrote no file at {out}")
    clip = bpy.data.movieclips.load(out)
    report = {
        "frames": clip.frame_duration,
        "width": clip.size[0],
        "height": clip.size[1],
        "fps": clip.fps,
        "subjectInFrame": all(c["inView"] for c in checks),
        "checks": checks,
        "blender": bpy.app.version_string,
        "renderer": gpu.platform.renderer_get(),
        "seconds": {"build": round(t1 - t0, 3), "render": round(t2 - t1, 3)},
    }
    with open(out + ".report.json", "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=1)
    expected = (frame["frameCount"], frame["width"], frame["height"])
    if (
        abs(report["frames"] - expected[0]) > 1
        or (report["width"], report["height"]) != expected[1:]
    ):
        raise RuntimeError(
            f"wrong video: got {report['frames']} frames at "
            f"{report['width']}x{report['height']}, expected {expected}"
        )
    print("PREVIEW_SCENE_DONE", json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
