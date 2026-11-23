"""Runs INSIDE Blender for tests/test_preview_scene.py: builds the scene of a card without
rendering and writes what it holds to JSON: object names, 3D text, instances, burnt-in text
strips and, at frames 0, 3, 6 ... and the last, the camera, every dummy (root, rotation, head,
hands, joints, head and nose in screen space) and every prop. Also which dummies are hidden from
the render and which ever enter the camera's view.

    blender -b --factory-startup --python-exit-code 1 -P inventory.py -- \
        <card.json> <font.ttf> <out.json>
"""

import json
import os
import sys

from bpy_extras.object_utils import world_to_camera_view
from mathutils import Vector

WORKER = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(WORKER, "blender"))
import preview_scene as scene_script  # noqa: E402


def rounded(v):
    return [round(x, 4) for x in v]


def in_view(scene, camera, obj):
    for corner in obj.bound_box:
        p = world_to_camera_view(scene, camera, obj.matrix_world @ Vector(corner))
        if 0.0 <= p.x <= 1.0 and 0.0 <= p.y <= 1.0 and p.z > camera.data.clip_start:
            return True
    return False


args = sys.argv[sys.argv.index("--") + 1 :]
with open(args[0], encoding="utf-8") as f:
    card = json.load(f)
scene, camera, actors, composed = scene_script.build_scene(
    card, os.path.splitext(args[2])[0] + ".mp4", args[1]
)
objects = list(scene.objects)
inventory = {
    "names": sorted(o.name for o in objects),
    "text3d": sorted(o.name for o in objects if o.type == "FONT"),
    "instances": sorted(o.name for o in objects if o.instance_type == "COLLECTION"),
    "stripsText": sorted(s.name for s in composed.sequence_editor.strips_all if s.type == "TEXT"),
}

count = card["frame"]["frameCount"]
meshes = {
    who: [o for o in a.rig["root"].children_recursive if o.type == "MESH"]
    for who, a in actors.items()
}
inventory["hidden"] = {who: all(o.hide_render for o in objs) for who, objs in meshes.items()}
inventory["seen"] = {who: False for who in meshes}
inventory["frames"] = {}
for frame in sorted(set(range(0, count, 3)) | {count - 1}):
    scene.frame_set(frame)
    for who, objs in meshes.items():
        inventory["seen"][who] = inventory["seen"][who] or any(
            in_view(scene, camera, o) for o in objs if not o.hide_render
        )
    inventory["frames"][str(frame)] = {
        "camera": rounded(camera.matrix_world.translation),
        "dummies": {
            who: {
                "root": rounded(a.rig["root"].matrix_world.translation),
                "rotation": rounded(a.rig["root"].rotation_euler),
                "head": rounded(a.rig["head"].matrix_world.translation),
                "handR": rounded(a.rig["handR"].matrix_world.translation),
                "handL": rounded(a.rig["handL"].matrix_world.translation),
                "headScreen": rounded(
                    world_to_camera_view(scene, camera, a.rig["head"].matrix_world.translation)
                ),
                "noseScreen": rounded(
                    world_to_camera_view(
                        scene, camera, scene.objects[f"{who}_nose"].matrix_world.translation
                    )
                ),
                "joints": {j: rounded(a.rig[j].rotation_euler) for j in scene_script.JOINTS},
            }
            for who, a in actors.items()
        },
        "props": {
            p["id"]: rounded(scene.objects[f"prop_{i}"].matrix_world.translation)
            for i, p in enumerate(card["props"])
        },
    }
with open(args[2], "w", encoding="utf-8") as f:
    json.dump(inventory, f, ensure_ascii=False)
