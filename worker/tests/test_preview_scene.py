"""Real-Blender rules of the preview scene script: length, size, framing, what the send version
leaves out, dummy motions at check frames, camera clearance and image brightness.

Needs Blender 4.5 in BLENDER_BIN (skipped with a reason otherwise). Every run goes through
Mesa's software renderer (`LIBGL_ALWAYS_SOFTWARE=1`, `-t 4`) so local, CI and production render the
same way; one shot renders in 35-70 s that way and peaks near 1.3 GB.

    BLENDER_BIN=/path/to/blender ~/.claude/bin/test-lock uv run --frozen --no-build pytest \
        tests/test_preview_scene.py -rs
"""

from __future__ import annotations

import json
import math
import os
import shutil
import subprocess
from pathlib import Path
from typing import Any

import pytest
from test_preview_motions import act, actions, gift, load, shot_by_order, twelve_codes_doc

from flowcortex_worker.preview import build_card, dumps, send_card

WORKER = Path(__file__).parent.parent
BLENDER_BIN = os.environ.get("BLENDER_BIN", "")
SCENE = WORKER / "blender" / "preview_scene.py"
INVENTORY = WORKER / "tests" / "blender" / "inventory.py"
BRIGHTNESS = WORKER / "tests" / "blender" / "brightness.py"
FONT = WORKER / "fonts" / "be-vietnam-pro" / "be-vietnam-pro-700.ttf"

pytestmark = pytest.mark.skipif(
    not BLENDER_BIN, reason="BLENDER_BIN is not set: the scene tests need Blender 4.5"
)

Card = dict[str, Any]
Inventory = dict[str, Any]
Rendered = tuple[Path, dict[str, Any]]


def run_blender(
    script: Path, *args: Path | str, timeout: int = 900
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            BLENDER_BIN,
            "-b",
            "-t",
            "4",
            "--factory-startup",
            "--python-exit-code",
            "1",
            "-P",
            str(script),
            "--",
            *map(str, args),
        ],
        capture_output=True,
        text=True,
        errors="replace",
        timeout=timeout,
        env={**os.environ, "LIBGL_ALWAYS_SOFTWARE": "1"},
        check=False,
    )


def succeeded(done: subprocess.CompletedProcess[str]) -> None:
    assert done.returncode == 0, done.stdout[-3000:] + done.stderr[-3000:]


def write_card(folder: Path, card: Card, name: str) -> Path:
    path = folder / f"{name}.json"
    path.write_text(dumps(card), encoding="utf-8")
    return path


def render(folder: Path, card: Card, name: str) -> Rendered:
    out = folder / f"{name}.mp4"
    succeeded(run_blender(SCENE, write_card(folder, card, name), out, FONT))
    report: dict[str, Any] = json.loads(Path(f"{out}.report.json").read_text(encoding="utf-8"))
    return out, report


def inventory(folder: Path, card: Card, name: str) -> Inventory:
    out = folder / f"{name}.inventory.json"
    succeeded(run_blender(INVENTORY, write_card(folder, card, name), FONT, out))
    data: Inventory = json.loads(out.read_text(encoding="utf-8"))
    return data


def brightness(folder: Path, card: Card, name: str) -> dict[str, Any]:
    out = folder / f"{name}.brightness.json"
    succeeded(run_blender(BRIGHTNESS, write_card(folder, card, name), FONT, out))
    data: dict[str, Any] = json.loads(out.read_text(encoding="utf-8"))
    return data


def card_of(doc: dict[str, Any], shot_id: str, *, send: bool = False) -> Card:
    card = build_card(doc, shot_id, "9:16")
    return send_card(card) if send else card


def minh_thu_card(*, send: bool = False, extras: str | None = None, ms: int | None = None) -> Card:
    doc = load("minh-thu.json")
    shot = doc["story"]["shots"][0]
    if extras:
        shot["staging"]["extras"] = extras
    if ms:
        shot["durationTargetMs"] = ms
    return card_of(doc, "sh1", send=send)


def distance(a: list[float], b: list[float], axes: int = 3) -> float:
    return math.dist(a[:axes], b[:axes])


def frames_of(data: Inventory) -> dict[str, Any]:
    frames: dict[str, Any] = data["frames"]
    return frames


def nearest(data: Inventory, seconds: float, fps: int = 24) -> dict[str, Any]:
    frames = frames_of(data)
    frame: dict[str, Any] = frames[min(frames, key=lambda f: abs(int(f) / fps - seconds))]
    return frame


def last_frame(data: Inventory) -> dict[str, Any]:
    frames = frames_of(data)
    frame: dict[str, Any] = frames[str(max(int(f) for f in frames))]
    return frame


def facing_camera(frame: dict[str, Any], who: str) -> float:
    dummy = frame["dummies"][who]
    yaw = dummy["rotation"][2]
    tx, ty = frame["camera"][0] - dummy["root"][0], frame["camera"][1] - dummy["root"][1]
    return float((math.sin(yaw) * tx - math.cos(yaw) * ty) / math.hypot(tx, ty))


def body_distance(camera: list[float], root: list[float], height: float = 1.7) -> float:
    ground = math.hypot(camera[0] - root[0], camera[1] - root[1])
    return math.hypot(ground, max(0.0, root[2] - camera[2], camera[2] - root[2] - height))


# ---- length, size and framing of the portrait shot --------------------------------------------
@pytest.fixture(scope="module")
def portrait(tmp_path_factory: pytest.TempPathFactory) -> Rendered:
    return render(tmp_path_factory.mktemp("portrait"), minh_thu_card(), "minh-thu-sh1")


def test_the_portrait_shot_reads_back_120_frames_at_496_by_864(portrait: Rendered) -> None:
    _, report = portrait
    assert abs(report["frames"] - 120) <= 1
    assert (report["width"], report["height"]) == (496, 864)
    assert report["fps"] == 24


@pytest.mark.skipif(shutil.which("ffprobe") is None, reason="ffprobe is not installed")
def test_ffprobe_agrees_with_the_report(portrait: Rendered) -> None:
    video, _ = portrait
    done = subprocess.run(
        [
            "ffprobe",
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-count_frames",
            "-show_entries",
            "stream=width,height,nb_read_frames,codec_name,pix_fmt:format=duration",
            "-of",
            "json",
            str(video),
        ],
        capture_output=True,
        text=True,
        check=True,
    )
    probed = json.loads(done.stdout)
    stream = probed["streams"][0]
    assert (stream["width"], stream["height"]) == (496, 864)
    assert (stream["codec_name"], stream["pix_fmt"]) == ("h264", "yuv420p")
    assert abs(int(stream["nb_read_frames"]) - 120) <= 1
    assert abs(float(probed["format"]["duration"]) - 5.0) <= 1 / 24 + 1e-6


def test_the_subject_is_in_frame_at_the_first_middle_and_last_frame(portrait: Rendered) -> None:
    _, report = portrait
    assert report["subjectInFrame"] is True
    checks = report["checks"]
    assert {c["id"] for c in checks} == {"ch_minh"} and len(checks) == 3
    assert all(c["inView"] for c in checks), checks


def test_software_rendering_is_reported(portrait: Rendered) -> None:
    _, report = portrait
    assert "llvmpipe" in report["renderer"].lower()
    assert report["blender"].startswith("4.5")
    assert report["seconds"]["build"] > 0 and report["seconds"]["render"] > 0


def test_a_missing_font_fails_the_run(tmp_path: Path) -> None:
    out = tmp_path / "no-font.mp4"
    card = write_card(tmp_path, minh_thu_card(), "no-font")
    done = run_blender(SCENE, card, out, tmp_path / "missing.ttf")
    assert done.returncode != 0
    assert not out.exists() and not Path(f"{out}.report.json").exists()


# ---- the send version --------------------------------------------------------------------------
def test_the_preview_draws_labels_extras_and_walls_but_no_subtitle_or_corner_text(
    tmp_path: Path,
) -> None:
    data = inventory(tmp_path, minh_thu_card(extras="crowd"), "preview")
    assert data["text3d"] and data["stripsText"] == []
    assert data["instances"]
    assert {"wall_north", "wall_west", "wall_east"} <= set(data["names"])


def test_the_send_version_has_no_text_extras_or_walls_and_keeps_floor_table_and_blocks(
    tmp_path: Path,
) -> None:
    card = minh_thu_card(send=True, extras="crowd")
    data = inventory(tmp_path, card, "send")
    assert data["text3d"] == [] and data["stripsText"] == []
    assert data["instances"] == []
    assert not [n for n in data["names"] if n.startswith("wall_")]
    kept = {"floor", "table", "piece_0", "piece_1", "prop_0"}
    kept |= {f"{d['id']}_root" for d in card["dummies"]}
    assert kept <= set(data["names"]), data["names"]


def test_the_send_version_renders_the_right_length_and_size(tmp_path: Path) -> None:
    _, report = render(tmp_path, minh_thu_card(send=True, ms=1000), "send")
    assert abs(report["frames"] - 24) <= 1
    assert (report["width"], report["height"]) == (496, 864)


# ---- dummy motions at check frames -------------------------------------------------------------
@pytest.fixture(scope="module")
def gift_scene(tmp_path_factory: pytest.TempPathFactory) -> Inventory:
    return inventory(tmp_path_factory.mktemp("gift"), card_of(gift(), "sh_trao"), "gift")


def test_labels_name_the_dummies_pieces_and_props_and_nothing_else(gift_scene: Inventory) -> None:
    assert gift_scene["stripsText"] == []
    assert gift_scene["text3d"] == [
        "label_ch_lan",
        "label_ch_minh",
        "label_ch_thu",
        "label_piece_0",
        "label_piece_1",
        "label_prop_0",
    ]


def test_dummies_with_motions_move_and_turn_between_the_first_and_last_frame(
    gift_scene: Inventory,
) -> None:
    frames = frames_of(gift_scene)
    first, middle, last = (frames[k]["dummies"] for k in ("0", "72", "143"))
    assert distance(first["ch_lan"]["root"], last["ch_lan"]["root"]) > 1.8
    assert first["ch_lan"]["root"][0] < middle["ch_lan"]["root"][0] < last["ch_lan"]["root"][0]
    turned = last["ch_minh"]["rotation"][2] - first["ch_minh"]["rotation"][2]
    assert abs(turned + math.radians(30)) < 0.02
    assert first["ch_thu"] == last["ch_thu"]


def test_the_gift_moves_from_lans_right_hand_to_minhs(gift_scene: Inventory) -> None:
    frames = frames_of(gift_scene)
    for key, holder in (("0", "ch_lan"), ("108", "ch_lan"), ("143", "ch_minh")):
        frame = frames[key]
        assert distance(frame["props"]["pr_qua"], frame["dummies"][holder]["handR"]) < 0.05, key


def test_the_head_moves_while_a_dummy_speaks_and_stays_still_otherwise(
    gift_scene: Inventory,
) -> None:
    frames = frames_of(gift_scene)
    speaking = {
        tuple(frames[str(f)]["dummies"]["ch_minh"]["joints"]["neck"]) for f in range(54, 96, 6)
    }
    assert len(speaking) >= 5
    silent = {tuple(f["dummies"]["ch_thu"]["joints"]["neck"]) for f in frames.values()}
    assert silent == {(0.0, 0.0, 0.0)}


def test_every_code_of_the_closed_list_ends_with_the_right_poses(tmp_path: Path) -> None:
    data = inventory(tmp_path, card_of(twelve_codes_doc(), "sh_12"), "twelve")
    frames = frames_of(data)
    first, last = frames["0"], frames["335"]
    for who in ("ch_lan", "ch_minh", "ch_thu"):
        assert first["dummies"][who] != last["dummies"][who], who
    assert "stool_ch_minh" in data["names"]
    assert abs(last["dummies"]["ch_thu"]["rotation"][0] - math.pi / 2) < 0.01
    assert abs(last["dummies"]["ch_minh"]["root"][2]) < 0.01
    seated = frames["252"]["dummies"]["ch_minh"]
    assert seated["root"][2] < -0.3 and seated["joints"]["thighL"][0] < -1.0
    rising = frames["270"]["dummies"]["ch_minh"]
    assert -1.4 < rising["joints"]["thighL"][0] < -0.1 and -0.4 < rising["root"][2] < -0.05
    assert 0.2 < frames["240"]["dummies"]["ch_thu"]["rotation"][0] < 1.4
    assert distance(last["props"]["pr_qua"], last["dummies"]["ch_minh"]["handR"]) < 0.05
    assert distance(last["props"]["pr_bong"], last["dummies"]["ch_thu"]["handR"]) < 0.05


def test_point_at_without_a_target_points_the_right_arm_straight_ahead(tmp_path: Path) -> None:
    doc = gift()
    actions(doc)[:] = [act("ch_lan", "point_at")]
    frames = frames_of(inventory(tmp_path, card_of(doc, "sh_trao"), "point"))
    lan = frames["18"]["dummies"]["ch_lan"]
    yaw = lan["rotation"][2]
    arm = [a - b for a, b in zip(lan["handR"], lan["root"], strict=True)]
    assert arm[0] * math.sin(yaw) - arm[1] * math.cos(yaw) > 0.45
    assert abs(lan["handR"][2] - 0.8 * 1.7) < 0.12
    assert abs(yaw - frames["0"]["dummies"]["ch_lan"]["rotation"][2]) < 1e-3


def test_a_hug_closes_the_distance_raises_both_arms_and_the_hugged_dummy_turns(
    tmp_path: Path,
) -> None:
    doc = load("d8-hien-nha.json")
    card = card_of(doc, shot_by_order(doc, 5)["id"])
    (hug,) = card["motions"]
    hugger, hugged = hug["characterId"], hug["response"]["characterId"]
    data = inventory(tmp_path, card, "hug")
    first, last = frames_of(data)["0"]["dummies"], last_frame(data)["dummies"]
    assert distance(first[hugger]["root"], first[hugged]["root"], 2) > 1.0
    assert distance(last[hugger]["root"], last[hugged]["root"], 2) == pytest.approx(0.4, abs=0.02)
    middle = nearest(data, hug["startSec"] + hug["walkSec"] + 1.0)["dummies"]
    for who, other in ((hugger, hugged), (hugged, hugger)):
        for hand in ("handL", "handR"):
            assert abs(middle[who][hand][2] - 0.8 * 1.7) < 0.15, (who, hand)
            assert distance(middle[who][hand], middle[other]["root"], 2) < 0.3, (who, hand)
        assert first[who]["handL"][2] < 1.0 and first[who]["handR"][2] < 1.0
        assert last[who]["handL"][2] < 1.0 and last[who]["handR"][2] < 1.0
    turned = math.radians(hug["response"]["toYawDeg"])
    assert abs((last[hugged]["rotation"][2] - turned + math.pi) % (2 * math.pi) - math.pi) < 0.02


def test_walk_away_backs_off_facing_the_camera_then_turns_and_leaves(tmp_path: Path) -> None:
    doc = load("d8-hien-nha.json")
    card = card_of(doc, shot_by_order(doc, 6)["id"])
    (job,) = card["motions"]
    who = job["characterId"]
    data = inventory(tmp_path, card, "walk-away")
    first, last = frames_of(data)["0"], last_frame(data)
    start = distance(first["camera"], first["dummies"][who]["root"])
    assert distance(last["camera"], last["dummies"][who]["root"]) > start + 3
    backing = nearest(data, job["backSec"] * 0.6)
    assert distance(backing["dummies"][who]["root"], first["dummies"][who]["root"]) > 0.15
    assert facing_camera(backing, who) > 0.5
    assert facing_camera(last, who) < -0.5


# ---- framing rules -----------------------------------------------------------------------------
@pytest.mark.parametrize("send", [False, True])
def test_dummies_declared_off_frame_never_enter_the_camera_view(tmp_path: Path, send: bool) -> None:
    doc = load("son-tinh.json")
    card = card_of(doc, shot_by_order(doc, 7)["id"], send=send)
    data = inventory(tmp_path, card, "off-frame")
    outside = [d["id"] for d in card["dummies"] if not d["inFrame"]]
    assert outside
    for who in outside:
        assert data["hidden"][who] or not data["seen"][who], who
    for d in card["dummies"]:
        if d["inFrame"]:
            assert not data["hidden"][d["id"]] and data["seen"][d["id"]]


def test_a_dummy_walking_towards_the_camera_stays_in_frame_and_never_passes_through_it(
    tmp_path: Path,
) -> None:
    doc = load("son-tinh.json")
    card = card_of(doc, shot_by_order(doc, 5)["id"])
    walker = next(m["characterId"] for m in card["motions"] if m["code"] == "walk_to")
    for key, frame in frames_of(inventory(tmp_path, card, "toward-camera")).items():
        x, y, depth = frame["dummies"][walker]["headScreen"]
        assert 0.0 <= x <= 1.0 and 0.0 <= y <= 1.0 and depth > 0, (key, x, y, depth)
        assert distance(frame["dummies"][walker]["root"], frame["camera"], 2) >= 0.5, key


def test_no_two_dummies_overlap(tmp_path: Path) -> None:
    doc = load("son-tinh.json")
    data = inventory(tmp_path, card_of(doc, shot_by_order(doc, 3)["id"]), "overlap")
    for key, frame in frames_of(data).items():
        roots = sorted(frame["dummies"].items())
        for i, (a, da) in enumerate(roots):
            for b, db in roots[i + 1 :]:
                assert distance(da["root"], db["root"], 2) >= 0.45, (key, a, b)


DEMO = "demo-hanh-trinh.json"
WALKER = "chc658mms17r"


def demo_card(order: int, move: str | None = None) -> Card:
    doc = load(DEMO)
    shot = shot_by_order(doc, order)
    if move:
        shot["staging"]["move"] = move
    return card_of(doc, shot["id"])


@pytest.mark.parametrize("move", ["push_in", "pull_back", "crane", "orbit"])
def test_a_camera_that_follows_the_subject_keeps_it_in_frame_and_0_6_m_away(
    tmp_path: Path, move: str
) -> None:
    frames = frames_of(inventory(tmp_path, demo_card(1, move), f"follow-{move}"))
    walked = distance(
        frames["0"]["dummies"][WALKER]["root"], frames["167"]["dummies"][WALKER]["root"]
    )
    assert walked > 1.5
    for key, frame in frames.items():
        walker = frame["dummies"][WALKER]
        x, y, depth = walker["headScreen"]
        assert 0.0 <= x <= 1.0 and 0.0 <= y <= 1.0 and depth > 0, (move, key, walker["headScreen"])
        assert body_distance(frame["camera"], walker["root"]) >= 0.6 - 0.01, (move, key)


def test_a_still_camera_keeps_its_distance_while_the_subject_walks_to_the_end(
    tmp_path: Path,
) -> None:
    frames = frames_of(inventory(tmp_path, demo_card(1, "static"), "still"))
    late, last = frames["141"]["dummies"][WALKER]["root"], frames["162"]["dummies"][WALKER]["root"]
    assert distance(late, last) > 0.2
    for key, frame in frames.items():
        assert body_distance(frame["camera"], frame["dummies"][WALKER]["root"]) >= 0.6 - 0.01, key


def test_the_camera_keeps_0_6_m_from_every_dummy_including_those_off_frame(
    tmp_path: Path,
) -> None:
    frames = frames_of(inventory(tmp_path, demo_card(4), "clearance"))
    for key, frame in frames.items():
        for who, dummy in frame["dummies"].items():
            assert body_distance(frame["camera"], dummy["root"]) >= 0.6 - 0.01, (key, who)


# ---- brightness bands (past bug: a near-black floor) -------------------------------------------
# Luma of the 8-bit sRGB pixel, 0 black, 1 white:
#   floor 0.45-0.85: clearly grey on a screen, not blown out, still shows shadows
#   floor to sky gap >= 0.12: the horizon reads
#   dummy colour to floor and wall colour >= 0.10 (mean |dR|,|dG|,|dB| of the region averages):
#     a video model can read the figure on its background, white and light-grey dummies included
FLOOR_LUMA = (0.45, 0.85)
SKY_GAP = 0.12
DUMMY_GAP = 0.10


def recolored(space: str, color: tuple[int, int, int] | None) -> dict[str, Any]:
    doc = gift()
    doc["story"]["sets"][0]["space"] = space
    for character in doc["characters"]:
        if color and character["kind"] == "character":
            character["clayColor"] = list(color)
    return doc


@pytest.mark.parametrize(
    ("space", "color", "send"),
    [
        ("outdoor", None, False),
        ("room", None, False),
        ("studio", None, False),
        ("car", None, False),
        ("outdoor", None, True),
        ("room", None, True),
        ("room", (200, 200, 200), False),
        ("studio", (200, 200, 200), False),
        ("outdoor", (255, 255, 255), False),
        ("studio", (255, 255, 255), False),
        ("car", (245, 210, 0), False),
    ],
)
def test_the_floor_is_light_grey_apart_from_the_sky_and_the_dummies(
    tmp_path: Path, space: str, color: tuple[int, int, int] | None, send: bool
) -> None:
    card = card_of(recolored(space, color), "sh_trao", send=send)
    measured = brightness(tmp_path, card, "bands")
    floor = measured["ground"]
    assert floor["n"] >= 30, measured
    assert FLOOR_LUMA[0] <= floor["luma"] <= FLOOR_LUMA[1], measured
    if measured["sky"]["n"]:
        assert abs(floor["luma"] - measured["sky"]["luma"]) >= SKY_GAP, measured
    assert measured["dummies"], measured
    backgrounds = [floor] + ([measured["wall"]] if measured["wall"]["n"] >= 100 else [])
    for who, dummy in measured["dummies"].items():
        for region in backgrounds:
            gap = sum(abs(a - b) for a, b in zip(dummy["color"], region["color"], strict=True)) / 3
            assert gap >= DUMMY_GAP, (who, gap, measured)
