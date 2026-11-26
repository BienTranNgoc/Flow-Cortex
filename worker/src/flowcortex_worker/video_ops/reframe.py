"""Rules of the `reframe` capability: output sizes, parameter checks, the crop and fit filters."""

from __future__ import annotations

import math
import statistics
from collections.abc import Mapping, Sequence
from typing import Any

from ..errors import PermanentError
from ..video_analysis.streams import finite_param

OUT_DIMS = {"9:16": (1080, 1920), "1:1": (1080, 1080), "4:5": (1080, 1350), "16:9": (1920, 1080)}
CROP_MODES = ("fit", "crop")
DEFAULT_RATIO = "9:16"
DEFAULT_MODE = "fit"
# Display aspects closer than this are the same shape: a 2 % stretch is invisible, a crop is not.
SAME_ASPECT_TOLERANCE = 0.02
# Frames with a detected face needed before the crop follows them rather than the centre.
MIN_FACES = 3


def _even(value: int) -> int:
    return value - value % 2


def _aspect(ratio: str) -> float:
    wide, tall = ratio.split(":")
    return int(wide) / int(tall)


def _seconds(params: Mapping[str, Any], key: str) -> float | None:
    if key not in params:
        return None
    seconds = finite_param(params, key)
    if seconds < 0:
        raise PermanentError(
            "contract_violation",
            f"param {key!r} must be a number of seconds >= 0 (got {seconds:g})",
        )
    return seconds


def check_params(params: Mapping[str, Any]) -> tuple[str, str]:
    """`(aspectRatio, cropMode)`; an absent key takes its default, any bad value is refused.

    The window keys are checked for shape here and against the clip's length by `read_window`.
    """
    ratio = params.get("aspectRatio", DEFAULT_RATIO)
    if not isinstance(ratio, str) or ratio not in OUT_DIMS:
        raise PermanentError(
            "contract_violation",
            f"param 'aspectRatio' must be one of {', '.join(OUT_DIMS)} (got {ratio!r})",
        )
    mode = params.get("cropMode", DEFAULT_MODE)
    if not isinstance(mode, str) or mode not in CROP_MODES:
        raise PermanentError(
            "contract_violation",
            f"param 'cropMode' must be one of {', '.join(CROP_MODES)} (got {mode!r})",
        )
    start = _seconds(params, "startSec")
    end = _seconds(params, "endSec")
    first = 0.0 if start is None else start
    if end is not None and end <= first:
        raise PermanentError(
            "contract_violation",
            f"param 'endSec' ({end:g}) must be above 'startSec' ({first:g})",
        )
    return ratio, mode


def crop_width(height: int, ratio: str) -> int:
    """Width of the full-height window of `ratio`, rounded to an even number of pixels."""
    return _even(round(height * _aspect(ratio)))


def _same_aspect(width: int, height: int, ratio: str) -> bool:
    return abs(width / height - _aspect(ratio)) < SAME_ASPECT_TOLERANCE


def follows_faces(width: int, height: int, ratio: str, mode: str) -> bool:
    """Whether the crop is a full-height window whose position depends on where the faces are."""
    return (
        mode == "crop"
        and not _same_aspect(width, height, ratio)
        and width / height > _aspect(ratio)
    )


def face_x(frames: Sequence[Sequence[Sequence[float]]]) -> tuple[float | None, int]:
    """`(median face centre, faces counted)` over the sampled frames, or `(None, 0)`.

    `frames` is the vision child's `faces` result: boxes `[left, top, right, bottom]` per frame.
    Fewer than `MIN_FACES` frames with a face is too little to follow.
    """
    if not isinstance(frames, list):
        raise _malformed(frames)
    centres: list[float] = []
    with_face = 0
    for boxes in frames:
        if not isinstance(boxes, list):
            raise _malformed(boxes)
        with_face += bool(boxes)
        centres.extend(_centre(box) for box in boxes)
    if with_face < MIN_FACES:
        return None, 0
    return statistics.median(centres), len(centres)


def _centre(box: Any) -> float:
    if (
        not isinstance(box, list)
        or len(box) != 4
        or not all(
            isinstance(v, int | float) and not isinstance(v, bool) and math.isfinite(v) for v in box
        )
    ):
        raise _malformed(box)
    return float(box[0] + box[2]) / 2


def _malformed(value: Any) -> PermanentError:
    return PermanentError(
        "contract_violation", f"the vision child returned a malformed face result ({value!r:.80})"
    )


def reframe_filter(
    width: int, height: int, ratio: str, mode: str, centre: float | None, faces: int
) -> tuple[str, str]:
    """The ffmpeg filter that turns a `width`x`height` picture into `ratio`, and its `method`."""
    out_w, out_h = OUT_DIMS[ratio]
    if _same_aspect(width, height, ratio):
        return f"scale={out_w}:{out_h}", "no-crop"
    if mode == "crop":
        if follows_faces(width, height, ratio, mode):
            window = crop_width(height, ratio)
            if centre is None:
                left, method = _even((width - window) // 2), "center-crop"
            else:
                left = _even(int(max(0, min(width - window, centre - window / 2))))
                method = f"face-crop(n={faces})"
            return f"crop={window}:{height}:{left}:0,scale={out_w}:{out_h}", method
        tall = _even(round(width / _aspect(ratio)))
        top = _even((height - tall) // 2)
        return f"crop={width}:{tall}:0:{top},scale={out_w}:{out_h}", "center-crop-v"
    return (
        f"split=2[bg][fg];"
        f"[bg]scale={out_w}:{out_h}:force_original_aspect_ratio=increase,"
        f"crop={out_w}:{out_h},boxblur=20:2[bg2];"
        f"[fg]scale={out_w}:{out_h}:force_original_aspect_ratio=decrease[fg2];"
        f"[bg2][fg2]overlay=(W-w)/2:(H-h)/2"
    ), "fit-blur-pad"
