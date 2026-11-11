"""Window plan, blend weights and the whole-audio mean/std for windowed separation."""

from __future__ import annotations

import math
from collections.abc import Iterable
from dataclasses import dataclass

WINDOW_SECONDS = 60
OVERLAP_SECONDS = 4


class NoSamples(ValueError):
    """The audio holds no sample."""


@dataclass(frozen=True)
class Window:
    """Frames `[start, start + length)`; `shared` frames overlap the next window."""

    start: int
    length: int
    shared: int


def window_plan(frames: int, rate: int) -> list[Window]:
    if frames <= 0:
        raise NoSamples("the audio holds no sample")
    size = WINDOW_SECONDS * rate
    overlap = OVERLAP_SECONDS * rate
    stride = size - overlap
    plan: list[Window] = []
    start = 0
    while True:
        length = min(size, frames - start)
        if start + length >= frames:
            plan.append(Window(start, length, 0))
            return plan
        plan.append(Window(start, length, overlap))
        start += stride


def blend_weights(count: int) -> list[float]:
    """Weights of the new window over a shared part of `count` samples; the previous window
    takes `1 - weight`."""
    return [(2 * i + 1) / (2 * count) for i in range(count)]


class MeanStd:
    """Running mean and unbiased standard deviation over chunks (Chan's parallel update)."""

    def __init__(self) -> None:
        self._count = 0
        self._mean = 0.0
        self._m2 = 0.0

    def update(self, values: Iterable[float]) -> None:
        chunk = list(values)
        if not chunk:
            return
        mean = math.fsum(chunk) / len(chunk)
        m2 = math.fsum((v - mean) ** 2 for v in chunk)
        self.merge(len(chunk), mean, m2)

    def merge(self, count: int, mean: float, m2: float) -> None:
        """Fold in a chunk summarised as its size, mean and sum of squared deviations."""
        if count <= 0:
            return
        total = self._count + count
        delta = mean - self._mean
        self._m2 += m2 + delta * delta * self._count * count / total
        self._mean += delta * count / total
        self._count = total

    @property
    def mean(self) -> float:
        if self._count == 0:
            raise NoSamples("the audio holds no sample")
        return self._mean

    @property
    def std(self) -> float:
        if self._count == 0:
            raise NoSamples("the audio holds no sample")
        if self._count < 2:
            return 0.0
        return math.sqrt(max(self._m2, 0.0) / (self._count - 1))
