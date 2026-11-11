"""Parsers for ffmpeg `metadata=mode=print` output."""

from __future__ import annotations

import re

from .streams import finite_float, ms, tool_failure

BLACK_START = "lavfi.black_start"
BLACK_END = "lavfi.black_end"
FREEZE_START = "lavfi.freezedetect.freeze_start"
FREEZE_END = "lavfi.freezedetect.freeze_end"
SCENE_CUT = "lavfi.scd.time"
_READ_KEYS = frozenset({BLACK_START, BLACK_END, FREEZE_START, FREEZE_END, SCENE_CUT})

_FRAME_HEADER = re.compile(r"^frame:\s*\d+\s+pts:\s*\S+\s+pts_time:(\S+)\s*$")
_SCORE_LINE = re.compile(r"^lavfi\.scd\.score=(\S+)\s*$")


def _seconds(key: str, value: str) -> float:
    seconds = finite_float(value)
    if seconds is None:
        raise tool_failure("ffmpeg", f"frame metadata {key} has no timestamp ({value!r})")
    return seconds


def frame_events(text: str) -> tuple[int, list[tuple[str, float]]]:
    """Decoded frame count and the (key, seconds) marks of the keys read, in frame order."""
    frames = 0
    events: list[tuple[str, float]] = []
    for line in text.splitlines():
        if line.startswith("frame:"):
            frames += 1
            continue
        key, _, value = line.partition("=")
        if key in _READ_KEYS:
            events.append((key, _seconds(key, value)))
    return frames, events


def runs(
    events: list[tuple[str, float]], start_key: str, end_key: str, duration_ms: int
) -> list[dict[str, int]]:
    """`{fromMs, toMs}` runs between start and end marks; an open run ends at the clip end."""
    pairs: list[tuple[int, int]] = []
    opened: float | None = None
    for key, seconds in events:
        if key == start_key:
            if opened is not None:
                raise tool_failure("ffmpeg", f"set {start_key} twice without {end_key}")
            opened = seconds
        elif key == end_key:
            if opened is None:
                raise tool_failure("ffmpeg", f"set {end_key} without {start_key}")
            pairs.append((ms(opened), ms(seconds)))
            opened = None
    if opened is not None:
        pairs.append((ms(opened), duration_ms))
    return [
        {"fromMs": min(max(a, 0), duration_ms), "toMs": min(max(b, 0), duration_ms)}
        for a, b in pairs
    ]


def black_runs(
    events: list[tuple[str, float]], duration_ms: int, one_frame_ms_floor: int
) -> list[dict[str, int]]:
    """Black runs of at least one frame.

    blackdetect marks every black run in the metadata; its `d` gates only its log line, so the
    one-frame minimum is applied here in whole milliseconds, rounded down so one frame passes.
    """
    return [
        run
        for run in runs(events, BLACK_START, BLACK_END, duration_ms)
        if run["toMs"] - run["fromMs"] >= one_frame_ms_floor
    ]


def cuts(events: list[tuple[str, float]], duration_ms: int) -> list[int]:
    """Ascending scene cuts strictly inside the clip, without repeats."""
    found: list[int] = []
    for at in (ms(seconds) for key, seconds in events if key == SCENE_CUT):
        if 0 < at < duration_ms and (not found or at > found[-1]):
            found.append(at)
    return found


def scdet_scores(text: str) -> list[tuple[float, float]]:
    """(seconds, score) of each frame block that carries a scdet score."""
    found: list[tuple[float, float]] = []
    current: float | None = None
    for line in text.splitlines():
        header = _FRAME_HEADER.match(line)
        if header:
            current = _seconds("pts_time", header.group(1))
            continue
        score = _SCORE_LINE.match(line)
        if score and current is not None:
            found.append((current, _seconds("lavfi.scd.score", score.group(1))))
            current = None
    return found


def selected_times(text: str) -> list[float]:
    """The `pts_time` of every frame block, in order."""
    return [
        _seconds("pts_time", header.group(1))
        for header in map(_FRAME_HEADER.match, text.splitlines())
        if header
    ]
