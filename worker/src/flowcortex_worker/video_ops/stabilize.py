"""Rules of the stabilize op: parameters, the zoom log, the steadier check and the disk guard."""

from __future__ import annotations

import re
import shutil
from collections.abc import Callable, Mapping
from pathlib import Path
from typing import Any, Protocol

from ..errors import PermanentError
from ..video_analysis.streams import finite_param

DEFAULT_SMOOTHING = 10
DEFAULT_OPTZOOM = 1
OPTZOOM_VALUES = (0, 1, 2)
# vf_vidstabtransform's own option range; a value past it fails only at the last ffmpeg run.
SMOOTHING_RANGE = (0, 1000)

# Worst case measured for the all-intra copy: 49 MB/s at 1920x1080x30 of pure noise (0.79 B/px).
INTRA_BYTES_PER_PIXEL = 0.8
# Smallest drop in px a frame that counts as steadier; below it the two measures are within noise.
MIN_GAIN_PX = 0.02

_ZOOM = re.compile(r"Final zoom:\s*(-?\d+(?:\.\d+)?)")


class DiskUsage(Protocol):
    @property
    def free(self) -> int: ...


def _whole(op_params: Mapping[str, Any], key: str, default: int) -> int:
    if key not in op_params:
        return default
    number = finite_param(op_params, key)
    if not number.is_integer():
        raise PermanentError(
            "contract_violation", f"param {key!r} must be a whole number (got {op_params[key]!r})"
        )
    return int(number)


def read_params(op_params: Mapping[str, Any]) -> tuple[int, int]:
    """`(smoothing, optzoom)`; an absent key takes its default, a bad value is refused."""
    smoothing = _whole(op_params, "smoothing", DEFAULT_SMOOTHING)
    low, high = SMOOTHING_RANGE
    if not low <= smoothing <= high:
        raise PermanentError(
            "contract_violation", f"param 'smoothing' must be {low}-{high} (got {smoothing})"
        )
    optzoom = _whole(op_params, "optzoom", DEFAULT_OPTZOOM)
    if optzoom not in OPTZOOM_VALUES:
        raise PermanentError(
            "contract_violation",
            f"param 'optzoom' must be one of {', '.join(map(str, OPTZOOM_VALUES))} (got {optzoom})",
        )
    return smoothing, optzoom


def parse_zoom_pct(log: str) -> int:
    """Whole percent of the last `Final zoom:` line, halves rounded up; 0 when there is none."""
    found = _ZOOM.findall(log)
    return max(0, int(float(found[-1]) + 0.5)) if found else 0


def not_steadier(source_motion: float, output_motion: float) -> bool:
    """True unless the output's motion is below the source's by more than the measurement noise."""
    return not output_motion < source_motion - MIN_GAIN_PX


def intra_bytes(width: int, height: int, fps: float, seconds: float) -> int:
    """Worst-case size of the window's all-intra copy."""
    return round(width * height * fps * seconds * INTRA_BYTES_PER_PIXEL)


def check_disk_room(
    width: int,
    height: int,
    fps: float,
    seconds: float,
    work_dir: Path,
    free: Callable[[Path], DiskUsage] = shutil.disk_usage,
) -> None:
    """Refuse a window whose all-intra copy and output would not fit in `work_dir`'s filesystem.

    The copy lives in a subdirectory of the job directory and the output is written next to it,
    so both always share one filesystem and the room needed is twice the copy's size.
    """
    copy = intra_bytes(width, height, fps, seconds)
    available = free(work_dir).free
    if available < 2 * copy:
        raise PermanentError(
            "duration_exceeded",
            f"the window's all-intra copy needs {copy} bytes and the copy with the output "
            f"{2 * copy}, but only {available} are free; pick a shorter window",
        )
