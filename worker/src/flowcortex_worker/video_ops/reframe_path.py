"""Rules of the derive reframe op: the 9:16 window, product boxes from a contact-sheet answer,
and the crop's left edge per frame."""

from __future__ import annotations

import json
import math
from collections.abc import Mapping, Sequence
from typing import Any

# Fractions of the window width: the subject may drift this far from the crop's centre before the
# crop follows it, and the crop closes this share of the gap on each frame it does follow.
DEADZONE = 0.15
EMA = 0.30
FACE_FPS = 5
ANALYSIS_WIDTH = 640
ASPECT_W, ASPECT_H = 9, 16

# [left, top, right, bottom] in source pixels; a product box only carries meaningful x values.
Box = tuple[float, float, float, float]
# [left, top, right, bottom] in tile coordinates 0-1000.
TileBox = list[int]
_Layout = tuple[int, int, int, int]


def window_width(source_height: int) -> int:
    """Width of the 9:16 window at the source height, even for yuv420p."""
    return math.floor(source_height * ASPECT_W / ASPECT_H / 2) * 2


# --- sheet boxes to frames -----------------------------------------------------------------------


def _tiles(reply: Any) -> list[Any]:
    if isinstance(reply, str):
        try:
            reply = json.loads(reply)
        except ValueError:
            return []
    if isinstance(reply, Mapping):
        reply = reply.get("tiles")
    return reply if isinstance(reply, list) else []


def _whole(value: Any) -> int | None:
    if isinstance(value, bool) or not isinstance(value, int | float) or not math.isfinite(value):
        return None
    return int(value)


def _number(value: Any) -> float | None:
    if isinstance(value, bool) or not isinstance(value, int | float) or not math.isfinite(value):
        return None
    return float(value)


def _layout(layout: Mapping[str, Any]) -> _Layout | None:
    """(cols, rows, stepMs, frames) of a contact sheet, or None when it cannot be read."""
    cols, rows, step_ms = (_whole(layout.get(k)) for k in ("cols", "rows", "stepMs"))
    if cols is None or rows is None or step_ms is None or cols <= 0 or rows <= 0:
        return None
    given = layout.get("frames")
    frames = cols * rows if given is None else _whole(given)
    if frames is None:
        return None
    return cols, rows, step_ms, min(frames, cols * rows)


def _tile(item: Any, frames: int) -> int | None:
    """The 1-based tile number an answer names, when it lies inside the layout."""
    if not isinstance(item, Mapping):
        return None
    tile = item.get("tile")
    if isinstance(tile, bool) or not isinstance(tile, int) or not 1 <= tile <= frames:
        return None
    return tile


def sheet_boxes_to_frames(reply: Any, layout: Mapping[str, Any]) -> dict[int, TileBox]:
    """`{t_ms: [x1, y1, x2, y2]}` in tile coordinates 0-1000, from boxes in sheet coordinates.

    Tiles are numbered from 1, row by row; tile k sits `(k-1) * stepMs` after the window start.
    A box centred outside its tile, a degenerate box, a tile beyond the layout, a layout that
    cannot be read and a reply that is not a list of answers give no entry.
    """
    read = _layout(layout)
    if read is None:
        return {}
    cols, rows, step_ms, frames = read
    cell_x, cell_y = 1000 / cols, 1000 / rows
    found: dict[int, TileBox] = {}
    for item in _tiles(reply):
        tile = _tile(item, frames)
        bbox = item.get("bbox") if tile is not None else None
        if tile is None or not isinstance(bbox, list | tuple) or len(bbox) != 4:
            continue
        numbers = [_number(v) for v in bbox]
        if any(n is None for n in numbers):
            continue
        x1, y1, x2, y2 = (float(n) for n in numbers if n is not None)
        left, top = ((tile - 1) % cols) * cell_x, ((tile - 1) // cols) * cell_y
        if not (left <= (x1 + x2) / 2 <= left + cell_x and top <= (y1 + y2) / 2 <= top + cell_y):
            continue
        box = [
            _to_tile(x1, left, cell_x, cols),
            _to_tile(y1, top, cell_y, rows),
            _to_tile(x2, left, cell_x, cols),
            _to_tile(y2, top, cell_y, rows),
        ]
        if box[2] <= box[0] or box[3] <= box[1]:
            continue
        found[(tile - 1) * step_ms] = box
    return found


def _to_tile(value: float, origin: float, cell: float, count: int) -> int:
    inside = min(max(value, origin), origin + cell) - origin
    return max(0, min(1000, round(inside * count)))


def sheet_null_frames(reply: Any, layout: Mapping[str, Any]) -> set[int]:
    """`{t_ms}` of the tiles answered `bbox: null`: the product is not there at that time."""
    read = _layout(layout)
    if read is None:
        return set()
    _, _, step_ms, frames = read
    absent: set[int] = set()
    for item in _tiles(reply):
        tile = _tile(item, frames)
        if tile is not None and item.get("bbox") is None:
            absent.add((tile - 1) * step_ms)
    return absent


def _nearest(times: Sequence[int], at: float) -> int:
    return min(times, key=lambda u: abs(u - at))


def product_per_frame(
    answers: Mapping[int, Sequence[float] | None],
    cuts: Sequence[int],
    frames: int,
    fps: float,
    source_width: int,
) -> list[Box | None]:
    """Hold each sheet answer over the frames nearest to it, inside its shot, as source pixels.

    `answers` maps t_ms to a tile box, or to None for a tile answered null: no product then, so
    the hold ends there.
    """
    if not answers:
        return [None] * frames
    starts = _shot_starts(cuts, frames)
    shot_of = [0] * frames
    for k, begin in enumerate(starts):
        for i in range(begin, frames if k + 1 == len(starts) else starts[k + 1]):
            shot_of[i] = k
    times = sorted(answers)
    products: list[Box | None] = []
    for i in range(frames):
        at = i * 1000 / fps
        same = [u for u in times if shot_of[min(frames - 1, int(u * fps / 1000))] == shot_of[i]]
        if not same:
            products.append(None)
            continue
        box = answers[_nearest(same, at)]
        if box is None:
            products.append(None)
        else:
            products.append((box[0] * source_width / 1000, 0, box[2] * source_width / 1000, 0))
    return products


# --- crop path -----------------------------------------------------------------------------------


def _shot_starts(cuts: Sequence[int], frames: int) -> list[int]:
    return sorted({c for c in cuts if 0 < c < frames} | {0})


def _clamp(value: float, low: float, high: float) -> float:
    return min(max(value, low), high)


def _must_contain(face: Box | None, product: Box | None, win: int) -> tuple[float, float] | None:
    """(low, high) the window's left edge must stay within to keep the box that matters inside."""
    box = product if product is not None else face
    if box is None or box[2] - box[0] > win:
        return None
    return box[2] - win, box[0]


def _target_left(face: Box | None, product: Box | None, win: int, source_width: int) -> float:
    """The window's left edge that frames this frame's subject, before smoothing."""
    if face is None and product is None:
        return (source_width - win) / 2
    if face is None or product is None:
        only = face if face is not None else product
        assert only is not None
        return (only[0] + only[2]) / 2 - win / 2
    low, high = min(face[0], product[0]), max(face[2], product[2])
    if high - low <= win:
        return (low + high) / 2 - win / 2
    if product[2] - product[0] >= win:
        return (product[0] + product[2]) / 2 - win / 2
    return min(max((face[0] + face[2]) / 2 - win / 2, product[2] - win), product[0])


def _bounds(
    face: Box | None, product: Box | None, win: int, source_width: int
) -> tuple[float, float]:
    """Allowed (low, high) for the left edge: inside the frame, around the boxes when they fit."""
    low, high = 0.0, float(source_width - win)
    if (
        face is not None
        and product is not None
        and max(face[2], product[2]) - min(face[0], product[0]) <= win
    ):
        low = max(low, max(face[2], product[2]) - win)
        high = min(high, min(face[0], product[0]))
    else:
        need = _must_contain(face, product, win)
        if need is not None:
            low, high = max(low, need[0]), min(high, need[1])
    return low, max(low, high)


def _clip_box(box: Box | None, source_width: int) -> Box | None:
    """The box clipped to the frame width; a box wholly outside the frame is no box."""
    if box is None:
        return None
    left, right = max(0.0, box[0]), min(float(source_width), box[2])
    return None if right <= left else (left, box[1], right, box[3])


def _hold_faces(faces: Sequence[Box | None]) -> list[Box | None]:
    """A missed detection inside a shot keeps the nearest earlier face (the first one for leading
    gaps); a shot with no face at all stays all None."""
    last = next((f for f in faces if f is not None), None)
    held: list[Box | None] = []
    for face in faces:
        last = face if face is not None else last
        held.append(last)
    return held


def _shot_path(
    targets: Sequence[float], bounds: Sequence[tuple[float, float]], win: int
) -> list[int]:
    low = max(b[0] for b in bounds)
    high = min(b[1] for b in bounds)
    median = sorted(targets)[len(targets) // 2]
    if low <= high and all(abs(t - median) <= DEADZONE * win for t in targets):
        return [round(_clamp(median, low, high))] * len(targets)
    position = targets[0]
    path: list[int] = []
    for target, (b_low, b_high) in zip(targets, bounds, strict=True):
        if path and abs(target - position) > DEADZONE * win:
            position += EMA * (target - position)
        elif not path:
            position = target
        position = _clamp(position, b_low, b_high)
        path.append(round(position))
    return path


def crop_path(
    frames: int,
    faces: Sequence[Box | None],
    products: Sequence[Box | None],
    cuts: Sequence[int],
    win: int,
    source_width: int,
) -> list[int]:
    """Left edge of the crop window for each of `frames` frames.

    `faces` and `products` hold per frame a box in source pixels or None; `cuts` the first frame
    of each new shot after the first. A shot whose subject stays inside the deadzone gets one
    fixed edge, otherwise the window tracks with deadzone and smoothing, restarting at every cut.
    The product stays inside whenever it fits.
    """
    starts = _shot_starts(cuts, frames)
    path: list[int] = []
    for k, begin in enumerate(starts):
        end = starts[k + 1] if k + 1 < len(starts) else frames
        shot_faces = _hold_faces([_clip_box(f, source_width) for f in faces[begin:end]])
        targets: list[float] = []
        bounds: list[tuple[float, float]] = []
        for face, product in zip(shot_faces, products[begin:end], strict=True):
            targets.append(_target_left(face, product, win, source_width))
            bounds.append(_bounds(face, product, win, source_width))
        path += [int(_clamp(x, 0, source_width - win)) for x in _shot_path(targets, bounds, win)]
    return path


def crop_commands(path: Sequence[int], rate: tuple[int, int]) -> str:
    """A `sendcmd` file moving the crop's `x` to each frame's left edge, one line where it changes.

    Frame n sits at `n * den / num` seconds; each time is floored to the microsecond, which is how
    ffmpeg compares it with a frame's timestamp, so a command never lands a frame late.
    """
    num, den = rate
    lines: list[str] = []
    previous: int | None = None
    for index, left in enumerate(path):
        if left == previous:
            continue
        micros = index * den * 1_000_000 // num
        lines.append(f"{micros // 1_000_000}.{micros % 1_000_000:06d} crop x {left};")
        previous = left
    return "\n".join(lines) + "\n" if lines else ""
