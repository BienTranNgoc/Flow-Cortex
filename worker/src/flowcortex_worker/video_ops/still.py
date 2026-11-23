"""Rules of the `still` capability: output size, parameter checks and the zoom filter."""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from ..errors import PermanentError
from ..video_analysis.streams import finite_param

MAX_WIDTH = 1920
ZOOM_MAX = 1.12
ZOOM_STEP = 0.0005
# yuv420p needs even sides; the zoom is cut from an image four times the output width so that
# zoompan, which works in whole pixels, does not step.
ZOOM_SOURCE_FACTOR = 4

MOTIONS = ("zoom_in", "zoom_out", "none")
DEFAULT_DURATION_SEC = 5.0
DEFAULT_FPS = 30
DEFAULT_MOTION = "zoom_in"
DURATION_RANGE = (1.0, 60.0)
FPS_RANGE = (1, 60)


def _even(value: int) -> int:
    return value - value % 2


def target_size(width: int, height: int) -> tuple[int, int]:
    """The image's aspect, scaled down to at most 1920 px wide, with even sides of at least 2."""
    if width > MAX_WIDTH:
        height = round(height * (MAX_WIDTH / width))
        width = MAX_WIDTH
    return max(_even(width), 2), max(_even(height), 2)


def _in_range(params: Mapping[str, Any], key: str, low: float, high: float) -> float:
    number = finite_param(params, key)
    if not low <= number <= high:
        raise PermanentError(
            "contract_violation", f"param {key!r} must be {low:g}-{high:g} (got {params[key]!r})"
        )
    return number


def check_params(params: Mapping[str, Any]) -> tuple[float, int, str]:
    """`(durationSec, fps, motion)`; an absent key takes its default, any bad value is refused."""
    duration = (
        _in_range(params, "durationSec", *DURATION_RANGE)
        if "durationSec" in params
        else DEFAULT_DURATION_SEC
    )
    fps = DEFAULT_FPS
    if "fps" in params:
        number = _in_range(params, "fps", *FPS_RANGE)
        if not number.is_integer():
            raise PermanentError(
                "contract_violation", f"param 'fps' must be a whole number (got {params['fps']!r})"
            )
        fps = int(number)
    motion = params.get("motion", DEFAULT_MOTION)
    if not isinstance(motion, str) or motion not in MOTIONS:
        raise PermanentError(
            "contract_violation",
            f"param 'motion' must be one of {', '.join(MOTIONS)} (got {motion!r})",
        )
    return duration, fps, motion


def zoom_filter(width: int, height: int, duration: float, fps: int, motion: str) -> str:
    """Centred zoompan over `round(duration * fps)` frames at `width`x`height`."""
    if motion == "zoom_in":
        zoom = f"min(zoom+{ZOOM_STEP},{ZOOM_MAX})"
    elif motion == "zoom_out":
        zoom = f"if(lte(zoom,1.0),{ZOOM_MAX},max(zoom-{ZOOM_STEP},1.0))"
    else:
        raise ValueError(f"{motion!r} is not a zoom motion")
    frames = max(1, round(duration * fps))
    return (
        f"scale={width * ZOOM_SOURCE_FACTOR}:-2,"
        f"zoompan=z='{zoom}':d={frames}:x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)'"
        f":s={width}x{height}:fps={fps},format=yuv420p"
    )


def static_filter(width: int, height: int, fps: int) -> str:
    return f"scale={width}:{height},fps={fps},format=yuv420p"
