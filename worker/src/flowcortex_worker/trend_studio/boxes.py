"""Detector pixel boxes to the frame fractions the role editor draws."""

from __future__ import annotations

from collections.abc import Iterable, Sequence

MIN_SCORE = 0.5


def person_boxes(
    raw: Iterable[Sequence[float]], width: int, height: int, min_score: float = MIN_SCORE
) -> list[list[float]]:
    """`(x0, y0, x1, y1, score)` pixels to `[x0, y0, x1, y1, score]` fractions, best score first.

    Coordinates clamp to [0, 1] and round to 4 decimals, the score to 3; a score under
    `min_score` or a box with no area after the clamp is dropped.
    """
    kept: list[list[float]] = []
    for x0, y0, x1, y1, score in raw:
        if score < min_score:
            continue
        left, right = (min(max(x / width, 0.0), 1.0) for x in (x0, x1))
        top, bottom = (min(max(y / height, 0.0), 1.0) for y in (y0, y1))
        if right <= left or bottom <= top:
            continue
        kept.append([round(v, 4) for v in (left, top, right, bottom)] + [round(float(score), 3)])
    return sorted(kept, key=lambda box: -box[4])
