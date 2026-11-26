"""Dropped dub segments: what is reported, and when there are too many to deliver the dub."""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from typing import Literal

# One dropped line in five is a flaw worth a warning; more than that is a dub with holes, and
# discarding a whole run over the first drop would waste the segments that did voice.
MAX_DROP_RATIO = 0.2

DropReason = Literal["missing", "unreadable"]


@dataclass(frozen=True)
class DropReport:
    ok: bool
    total: int
    dropped: list[dict[str, int | str]]

    @property
    def count(self) -> int:
        return len(self.dropped)


def drop_report(total: int, dropped: Sequence[tuple[int, DropReason]]) -> DropReport:
    """`ok` is false when more than `MAX_DROP_RATIO` of the `total` listed segments dropped."""
    rows: list[dict[str, int | str]] = [
        {"segIdx": index, "reason": reason} for index, reason in dropped
    ]
    ratio = len(rows) / total if total else 0.0
    return DropReport(ratio <= MAX_DROP_RATIO, total, rows)
