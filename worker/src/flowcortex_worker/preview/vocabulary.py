"""Closed staging vocabulary, frame table and the numeric constants of the staging card.

Units are metres and degrees; +Y is north and +X east, the default camera stands south and looks
north, so west (-X) is frame left.
"""

from __future__ import annotations

CARD_KIND = "flowcortex.staging-card/1"
FPS = 24
FRAME_BY_ASPECT: dict[str, tuple[int, int]] = {
    "9:16": (496, 864),
    "16:9": (854, 480),
    "1:1": (640, 640),
}
SENSOR_MM_BY_ASPECT: dict[str, float] = {"9:16": 36.0, "16:9": 24.0, "1:1": 24.0}

SHOT_TYPES = ("single", "over_shoulder", "two_shot", "group", "insert", "establishing", "pov")
MOVES = (
    "static",
    "push_in",
    "pull_back",
    "pan",
    "tilt",
    "handheld",
    "track",
    "truck",
    "crane",
    "drone",
    "orbit",
)
SIZES = ("ECU", "CU", "MCU", "MS", "MLS", "LS", "EWS")
ANGLES = ("eye", "high", "low", "overhead", "worm")
SPACES = ("room", "car", "outdoor", "studio")

PLACEMENT_BY_SHOT_TYPE = {
    "single": "single",
    "over_shoulder": "over_shoulder",
    "two_shot": "group",
    "group": "group",
    "insert": "insert",
    "establishing": "establishing",
    "pov": "pov",
}
MOTION_BY_MOVE = {
    "static": "static",
    "push_in": "dolly",
    "pull_back": "dolly",
    "pan": "pan",
    "tilt": "tilt",
    "handheld": "handheld",
    "track": "follow",
    "truck": "truck",
    "crane": "crane",
    "drone": "drone",
    "orbit": "orbit",
}

HUMAN_HEIGHT_M = 1.7
TWO_PERSON_SPACING_M = 1.2
SEAT_HEIGHT_M = 0.45
HIP_RATIO = 0.52
DEFAULT_COLOR = [200, 200, 200]
EXTRAS_COLOR = [120, 120, 120]
EXTRAS_COUNT = {"none": 0, "few": 4, "crowd": 30}
EXTRAS_SEED = 7

# Tight to wide; steps of a dolly or crane move walk this ladder.
SIZE_LADDER = ("ECU", "BCU", "CU", "MCU", "MS", "MLS", "FS", "LS", "EWS")
SIZE_BOTTOM_RATIO = {
    "ECU": 0.905,
    "BCU": 0.853,
    "CU": 0.76,
    "MCU": 0.66,
    "MS": 0.52,
    "MLS": 0.28,
    "FS": 0.0,
    "LS": 0.0,
    "EWS": 0.0,
}
SIZE_FOCAL_MM = {
    "ECU": 100,
    "BCU": 100,
    "CU": 85,
    "MCU": 70,
    "MS": 50,
    "MLS": 35,
    "FS": 35,
    "LS": 28,
    "EWS": 24,
}
GROUP_MARGIN = {"LS": 0.15, "EWS": 0.3}
GROUP_MARGIN_DEFAULT = 0.06
INSERT_WIDTH_M = {"ECU": 0.15, "CU": 0.3, "MCU": 0.5, "MS": 0.8, "MLS": 1.2, "LS": 2.0, "EWS": 4.0}
OPEN_GROUND_HALF_WIDTH_M = {
    "ECU": 1.0,
    "CU": 1.5,
    "MCU": 2.0,
    "MS": 3.0,
    "MLS": 4.0,
    "LS": 6.0,
    "EWS": 12.0,
}

ANGLE_DEG = {"eye": 0, "low": -20, "worm": -35, "high": 25, "overhead": 50}
GROUP_PITCH_DEG = {"eye": 8, "high": 25, "low": -8, "overhead": 60, "worm": -15}
ESTABLISHING_PITCH_MIN_DEG = 25
EYE_PITCH_DEG = {"eye": 0, "high": 20, "low": -15, "overhead": 60, "worm": -25}
POV_GROUND_EYE_M = 0.3
POV_EYE_RATIO = 0.935

SCREEN_X = {"left": -0.17, "center": 0.0, "right": 0.17}
DEFAULT_FACE_ANGLE_DEG = 30

PAN_TILT_DEG = 15
FOLLOW_WALK_M = 2.0
TRUCK_M = 1.5
ORBIT_DEG = 90
CRANE_LIFT_M = 1.5
DOLLY_FACTOR = {"push_in": 0.7, "pull_back": 1.4}
DRONE_HEIGHT_M = {"outdoor": 15.0, None: 15.0}
DRONE_HEIGHT_OTHER_M = 6.0
DRONE_DRIFT_M = 4.0
DRONE_PITCH_DEG = 40
HANDHELD_PARAMS = {"amplitude": 0.8, "rate": 10.0, "seed": 1}

FIXED_PIECE_SIZE_M = [0.8, 0.6, 0.9]
PROP_SIZE_M = [0.12, 0.12, 0.15]
FIXED_PIECE_RING_RADIUS_M = {"room": 2.2, "car": 1.6, "outdoor": 5.0, "studio": 2.5, None: 5.0}
TABLE = {"center": [0.0, 0.55, 0.375], "dimensions": [0.9, 0.6, 0.75]}

CAMERA_MIN_DISTANCE_M = 0.6
CAMERA_MIN_HEIGHT_M = 0.15
STAGE_EDGE_MARGIN_M = 0.3

PROP_DISTANCE_M = 0.45
PROP_SIDE_SPACING_M = 0.2
TABLE_EDGE_M = 0.25
TABLE_EDGE_INSET_M = 0.08
PROP_CAMERA_SKEW_DEG = 60.0
PERSON_CLEARANCE_M = 0.5
WALK_CLEARANCE_M = 0.7
TABLE_AVOID_MARGIN_M = 0.15

# Per size: (top eye ratio, top screen offset, bottom ratio, bottom screen offset, focal mm), the
# framing solved by the camera estimate.
SIZE_FRAMING = {
    "ECU": (0.965, 0.30, 0.905, -0.30, 100),
    "BCU": (0.935, 0.17, 0.853, -0.45, 100),
    "CU": (0.935, 0.17, 0.76, -0.50, 85),
    "MCU": (0.935, 0.20, 0.66, -0.50, 70),
    "MS": (0.935, 0.24, 0.52, -0.50, 50),
    "MLS": (0.935, 0.30, 0.28, -0.50, 35),
    "FS": (1.00, 0.42, 0.00, -0.46, 35),
    "LS": (1.00, -0.05, 0.00, -0.30, 28),
    "EWS": (1.00, -0.18, 0.00, -0.28, 24),
}
FACE_PROFILE = ((0.80, 0.0), (0.853, 0.045), (0.90, 0.06), (0.97, 0.06), (1.0, 0.0))
