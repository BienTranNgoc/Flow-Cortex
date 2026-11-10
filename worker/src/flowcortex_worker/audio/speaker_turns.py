"""Speaker turns: engine segments to whole-millisecond turns numbered by first speech."""

from __future__ import annotations

from collections.abc import Iterable
from decimal import ROUND_HALF_UP, Decimal

SEPARATOR = "3d-speaker/sherpa-onnx-1.13.8"


def to_ms(seconds: float) -> int:
    """Seconds to whole milliseconds, halves up. Goes through the shortest decimal repr so that
    1.2005 is 1201 rather than the 1200 its binary value would give."""
    return int((Decimal(repr(seconds)) * 1000).quantize(Decimal(1), rounding=ROUND_HALF_UP))


def turns(segments: Iterable[tuple[float, float, int]]) -> list[dict[str, int]]:
    """`segments` are `(start_s, end_s, raw_label)`. Turns shorter than 1 ms are dropped, the rest
    sorted by start; overlaps are kept; labels are renumbered from 1 in order of first speech."""
    spans = [(to_ms(start), to_ms(end), raw) for start, end, raw in segments]
    kept = sorted((s for s in spans if s[1] - s[0] >= 1), key=lambda s: (s[0], s[1]))
    labels: dict[int, int] = {}
    result: list[dict[str, int]] = []
    for from_ms, to_ms_, raw in kept:
        label = labels.setdefault(raw, len(labels) + 1)
        result.append({"fromMs": from_ms, "toMs": to_ms_, "label": label})
    return result
