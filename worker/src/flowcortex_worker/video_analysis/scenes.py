"""Scene rules of `scene_frames`: aspect, standard sizes, boundaries, caller bounds."""

from __future__ import annotations

from itertools import pairwise
from typing import Any

from ..errors import PermanentError

FPS = 24
MIN_SEC = 4.0
MAX_SEC = 60.0
# A scene shorter than this many frames is flicker and merges into the previous one.
MIN_SCENE_FRAMES = 5
DEFAULT_SCENE_THRESHOLD = 0.3
STANDARD_SIZES = {"16:9": (854, 480), "9:16": (480, 854), "1:1": (640, 640)}
_RATIOS = {"9:16": 9 / 16, "1:1": 1.0, "16:9": 16 / 9}


def aspect(width: int, height: int) -> str:
    """The standard aspect label nearest to the display size; unknown size reads as portrait."""
    if width <= 0 or height <= 0:
        return "9:16"
    ratio = width / height
    return min(_RATIOS, key=lambda label: abs(_RATIOS[label] - ratio))


def boundaries(cuts_sec: list[float], total_frames: int) -> list[int]:
    """Boundaries `[0 … total_frames]`; a cut under `MIN_SCENE_FRAMES` from a neighbour drops."""
    bounds = [0]
    for seconds in cuts_sec:
        frame = round(seconds * FPS)
        if frame - bounds[-1] >= MIN_SCENE_FRAMES and total_frames - frame >= MIN_SCENE_FRAMES:
            bounds.append(frame)
    bounds.append(total_frames)
    return bounds


def given_bounds(raw: Any) -> list[int] | None:
    """Caller bounds validated against the detector's own spacing; None when none were given."""
    if raw is None:
        return None
    if (
        not isinstance(raw, list)
        or len(raw) < 2
        or any(type(b) is not int for b in raw)
        or raw[0] != 0
        or any(later - earlier < MIN_SCENE_FRAMES for earlier, later in pairwise(raw))
    ):
        raise PermanentError(
            "contract_violation",
            "bounds must be whole frame numbers starting at 0, "
            f"each at least {MIN_SCENE_FRAMES} frames after the previous",
        )
    return list(raw)


def middle_frame(start: int, end: int) -> int:
    """The frame number in the middle of the scene `[start, end)`."""
    return (start + end) // 2
