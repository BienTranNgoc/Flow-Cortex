"""Child process of the video ops: faces, shot cuts and motion over files ffmpeg wrote.

Run as `python -m flowcortex_worker.video_ops.vision <command>` inside a work directory; the
files, the request and the result are the ones in `protocol`, and the exit codes are too. It
never opens the job's raw input, so OpenCV's own demuxers only see files our ffmpeg made.
"""

from __future__ import annotations

import json
import math
import re
import sys
from pathlib import Path
from typing import Any, Protocol

from . import protocol

# Mean grey difference of two consecutive 64x36 frames above which a frame starts a new shot.
CUT_THRESHOLD = 18.0
DIFF_SIZE = (64, 36)

MOTION_WIDTH = 640
MOTION_GRID = 3
MIN_TILE = 32
# Median tile correlation peak below which two frames are not one shot (a hard cut scores
# 0.04-0.08, every step inside a shot 0.2 or more); such a pair is dropped from the mean.
MIN_RESPONSE = 0.1

_FRAME_NAME = re.compile(r"f_(\d+)\.jpg")


class BadRequest(Exception):
    """The request file is missing a field or holds a value the command cannot use."""


class Unreadable(Exception):
    """A file the handler should have made is missing or does not decode."""


class FaceEngine(Protocol):
    def faces(self, bgr: Any) -> list[tuple[int, int, int, int]]:
        """Face boxes as (x, y, w, h) in the pixels of `bgr`."""
        ...


def _read_request(work: Path) -> dict[str, Any]:
    try:
        request = json.loads((work / protocol.REQUEST_FILE).read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise BadRequest(f"{protocol.REQUEST_FILE} is unreadable: {error}") from error
    if not isinstance(request, dict):
        raise BadRequest(f"{protocol.REQUEST_FILE} must hold an object")
    return request


def _scale(request: dict[str, Any]) -> float:
    value = request.get("scale")
    if (
        isinstance(value, bool)
        or not isinstance(value, int | float)
        or not math.isfinite(value)
        or value <= 0
    ):
        raise BadRequest(f"request field 'scale' must be a number above 0 (got {value!r})")
    return float(value)


def _stride(request: dict[str, Any]) -> int:
    value = request.get("stride")
    if isinstance(value, bool) or not isinstance(value, int) or value < 1:
        raise BadRequest(f"request field 'stride' must be a whole number >= 1 (got {value!r})")
    return value


def _source_box(box: tuple[int, int, int, int], scale: float) -> list[float]:
    x, y, w, h = box
    return [x * scale, y * scale, (x + w) * scale, (y + h) * scale]


def _open(path: Path) -> Any:
    import cv2

    capture = cv2.VideoCapture(str(path))
    if not capture.isOpened():
        capture.release()
        raise Unreadable(f"{path.name} is missing or does not decode")
    return capture


def run_faces(work: Path, request: dict[str, Any], engine: FaceEngine) -> dict[str, Any]:
    """Every face on each `frames/f_*.jpg` in name order, boxes scaled to source pixels."""
    import cv2

    scale = _scale(request)
    numbered = []
    for path in (work / protocol.FRAMES_DIR).glob("f_*.jpg"):
        match = _FRAME_NAME.fullmatch(path.name)
        if match:
            numbered.append((int(match.group(1)), path))
    faces: list[list[list[float]]] = []
    for _, path in sorted(numbered):
        frame = cv2.imread(str(path))
        if frame is None:
            raise Unreadable(f"{protocol.FRAMES_DIR}/{path.name} does not decode")
        faces.append([_source_box(box, scale) for box in engine.faces(frame)])
    return {"faces": faces}


def run_track(work: Path, request: dict[str, Any], engine: FaceEngine) -> dict[str, Any]:
    """Per frame of `analysis.mp4`: shot cuts, and the largest face found every `stride` frames
    and on cut frames, held until the next look and dropped at a cut."""
    import cv2

    scale = _scale(request)
    stride = _stride(request)
    capture = _open(work / protocol.ANALYSIS_FILE)
    cuts: list[int] = []
    faces: list[list[float] | None] = []
    previous = None
    held: list[float] | None = None
    try:
        while True:
            more, frame = capture.read()
            if not more:
                break
            index = len(faces)
            grey = cv2.resize(
                cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY), DIFF_SIZE, interpolation=cv2.INTER_AREA
            )
            cut = previous is not None and float(cv2.absdiff(grey, previous).mean()) > CUT_THRESHOLD
            previous = grey
            if cut:
                cuts.append(index)
                held = None
            if cut or index % stride == 0:
                found = engine.faces(frame)
                held = _source_box(max(found, key=lambda b: b[2] * b[3]), scale) if found else None
            faces.append(held)
    finally:
        capture.release()
    return {"frames": len(faces), "cuts": cuts, "faces": faces}


def measure_motion(path: Path) -> float:
    """Mean source pixels a frame of the camera's shift between consecutive frames.

    Phase correlation on a 3x3 grid of tiles of grey frames at most 640 px wide (one tile when a
    tile would be under 32 px), the median over tiles so a moving subject does not count; a pair
    whose median response is under `MIN_RESPONSE` is skipped. 0.0 when no pair is left.
    """
    import cv2
    import numpy as np

    capture = _open(path)
    previous = hann = None
    grid = MOTION_GRID
    steps: list[float] = []
    try:
        while True:
            more, frame = capture.read()
            if not more:
                break
            grey, scale = _motion_grey(cv2, frame)
            if hann is None:
                grid = MOTION_GRID if min(grey.shape) // MOTION_GRID >= MIN_TILE else 1
                hann = cv2.createHanningWindow(
                    (grey.shape[1] // grid, grey.shape[0] // grid), cv2.CV_32F
                )
            if previous is not None:
                step = _tile_shift(cv2, np, (previous, grey), hann, grid, scale)
                if step is not None:
                    steps.append(step)
            previous = grey
    finally:
        capture.release()
    return sum(steps) / len(steps) if steps else 0.0


def _motion_grey(cv2: Any, frame: Any) -> tuple[Any, float]:
    """The frame as float32 grey at most `MOTION_WIDTH` wide, and the scale it was shrunk by."""
    height, width = frame.shape[:2]
    scale = min(1.0, MOTION_WIDTH / width)
    grey = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    if scale < 1.0:
        size = (round(width * scale), round(height * scale))
        grey = cv2.resize(grey, size, interpolation=cv2.INTER_AREA)
    return grey.astype("float32"), scale


def _tile_shift(
    cv2: Any, np: Any, pair: tuple[Any, Any], hann: Any, grid: int, scale: float
) -> float | None:
    """Median shift in source pixels between two grey frames, None when the tiles do not agree
    on one shot (median response under `MIN_RESPONSE`)."""
    previous, grey = pair
    tile_h, tile_w = grey.shape[0] // grid, grey.shape[1] // grid
    tiles = [
        cv2.phaseCorrelate(
            previous[r * tile_h : (r + 1) * tile_h, c * tile_w : (c + 1) * tile_w],
            grey[r * tile_h : (r + 1) * tile_h, c * tile_w : (c + 1) * tile_w],
            hann,
        )
        for r in range(grid)
        for c in range(grid)
    ]
    if float(np.median([t[1] for t in tiles])) >= MIN_RESPONSE:
        dx = float(np.median([t[0][0] for t in tiles]))
        dy = float(np.median([t[0][1] for t in tiles]))
        return float((dx * dx + dy * dy) ** 0.5 / scale)
    return None


def run_motion(work: Path) -> dict[str, Any]:
    return {
        "windowMotion": measure_motion(work / protocol.WINDOW_FILE),
        "outputMotion": measure_motion(work / protocol.OUTPUT_FILE),
    }


def main(argv: list[str]) -> int:
    if len(argv) != 1 or argv[0] not in protocol.COMMANDS:
        choices = "|".join(protocol.COMMANDS)
        sys.stderr.write(f"usage: python -m flowcortex_worker.video_ops.vision {{{choices}}}\n")
        return protocol.USAGE
    command = argv[0]
    work = Path.cwd()

    try:
        # Imports cv2 and numpy, so a worker image without them exits here, before any work.
        from ..overlay import models
    except ImportError as error:
        sys.stderr.write(f"vision runtime missing: {error}\n")
        return protocol.RUNTIME_MISSING

    if command != "motion":
        try:
            models.require_face_model()
        except models.FaceModelMissing as error:
            sys.stderr.write(f"{error}\n")
            return protocol.FACE_MODEL_MISSING

    try:
        request = _read_request(work)
        if command == "motion":
            result = run_motion(work)
        elif command == "faces":
            result = run_faces(work, request, models.ModelEngines())
        else:
            result = run_track(work, request, models.ModelEngines())
    except BadRequest as error:
        sys.stderr.write(f"bad request: {error}\n")
        return protocol.USAGE
    except Unreadable as error:
        sys.stderr.write(f"unreadable input: {error}\n")
        return protocol.FAILED

    (work / protocol.RESULT_FILE).write_text(json.dumps(result), encoding="utf-8")
    return protocol.OK


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
