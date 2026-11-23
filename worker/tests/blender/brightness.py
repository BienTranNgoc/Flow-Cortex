"""Runs INSIDE Blender for tests/test_preview_scene.py: builds the scene of a card, renders ONE
frame to PNG through the exact path that writes the MP4 (the composed scene, with its colour
management), and measures the colour and brightness of each region of the image.

Method: on a grid (every 4 px) a ray goes from the camera through that pixel; the first object it
hits names the region:
  ground  the floor (`floor`, `ground`, `car_floor`) and the horizontal part of `cyclorama`
  wall    room walls `wall_*` and the upright part of `cyclorama`
  sky     the ray hits nothing
  dummies each main dummy (objects named `<id>_...`)
Brightness is Rec.709 luma of the 8-bit sRGB-encoded pixel value (0-1, as seen on a screen).

    blender -b --factory-startup --python-exit-code 1 -P brightness.py -- \
        <card.json> <font.ttf> <out.json> [frame]
"""

import json
import os
import sys

import bpy

WORKER = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(WORKER, "blender"))
import preview_scene as scene_script  # noqa: E402

FLOOR_NAMES = ("floor", "ground", "car_floor")
STEP = 4

args = sys.argv[sys.argv.index("--") + 1 :]
with open(args[0], encoding="utf-8") as f:
    card = json.load(f)
frame = int(args[3]) if len(args) > 3 else 0
stem = os.path.splitext(args[2])[0]
scene, camera, actors, composed = scene_script.build_scene(card, stem + ".mp4", args[1])
scene.frame_set(frame)
composed.frame_set(frame)
composed.render.image_settings.file_format = "PNG"
composed.render.image_settings.color_mode = "RGB"
composed.render.filepath = stem + ".png"
bpy.ops.render.render(write_still=True, scene=composed.name)
image = bpy.data.images.load(stem + ".png")
width, height = image.size
pixels = list(image.pixels)
depsgraph = bpy.context.evaluated_depsgraph_get()
origin = camera.matrix_world.translation.copy()
top_right, bottom_right, bottom_left, top_left = [
    camera.matrix_world @ v for v in camera.data.view_frame(scene=scene)
]
dummy_ids = [d["id"] for d in card["dummies"]]


def region(obj, normal):
    if obj is None:
        return "sky"
    name = obj.name
    if name in FLOOR_NAMES or (name == "cyclorama" and normal.z > 0.7):
        return "ground"
    if name.startswith("wall_") or name == "cyclorama":
        return "wall"
    for who in dummy_ids:
        if name.startswith(who + "_"):
            return who
    return None


samples = {}
for y in range(STEP // 2, height, STEP):
    for x in range(STEP // 2, width, STEP):
        u, v = (x + 0.5) / width, (y + 0.5) / height
        point = bottom_left.lerp(bottom_right, u).lerp(top_left.lerp(top_right, u), v)
        hit, _, normal, _, obj, _ = scene.ray_cast(depsgraph, origin, (point - origin).normalized())
        key = region(obj if hit else None, normal)
        if key is not None:
            i = (y * width + x) * 4
            samples.setdefault(key, []).append(pixels[i : i + 3])


def summary(rows):
    if not rows:
        return {"n": 0, "color": None, "luma": None}
    color = [sum(c[k] for c in rows) / len(rows) for k in range(3)]
    return {
        "n": len(rows),
        "color": [round(c, 4) for c in color],
        "luma": round(0.2126 * color[0] + 0.7152 * color[1] + 0.0722 * color[2], 4),
    }


report = {
    "frame": frame,
    "width": width,
    "height": height,
    "ground": summary(samples.get("ground")),
    "wall": summary(samples.get("wall")),
    "sky": summary(samples.get("sky")),
    "dummies": {who: summary(samples[who]) for who in dummy_ids if len(samples.get(who, [])) >= 20},
}
with open(args[2], "w", encoding="utf-8") as f:
    json.dump(report, f, ensure_ascii=False)
