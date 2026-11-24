"""Numbered job inputs `<prefix>_1 … <prefix>_N`, checked before any process runs."""

from __future__ import annotations

import re
from collections.abc import Collection, Mapping
from pathlib import Path

from ..errors import PermanentError

# Every remote job starts with the run's bound upload (`media`; Product Video adds `image_<n>`).
# Dropping them keeps a mistyped key refused without refusing every real job.
RUN_BOUND_KEY = "media"
RUN_BOUND_PREFIX = "image_"


def is_run_bound(key: str) -> bool:
    return key == RUN_BOUND_KEY or key.startswith(RUN_BOUND_PREFIX)


def numbered(
    inputs: Mapping[str, Path],
    prefix: str,
    low: int,
    high: int,
    extra: Collection[str] = (),
) -> tuple[list[Path], dict[str, Path]]:
    """The `<prefix>_<n>` inputs in ascending `n`, and the `extra` named roles apart.

    A role in `extra` wins over a run-bound key of the same name. Any other key, a zero-padded
    number, a gap or a count outside `low..high` is a `contract_violation` naming it.
    """
    pattern = re.compile(rf"{re.escape(prefix)}_([1-9][0-9]*)")
    by_number: dict[int, Path] = {}
    roles: dict[str, Path] = {}
    for key in sorted(inputs):
        if key in extra:
            roles[key] = inputs[key]
            continue
        match = pattern.fullmatch(key)
        if match:
            by_number[int(match.group(1))] = inputs[key]
        elif not is_run_bound(key):
            raise PermanentError("contract_violation", f"unexpected input key '{key}'")
    highest = max(by_number, default=0)
    for number in range(1, highest + 1):
        if number not in by_number:
            raise PermanentError("contract_violation", f"input '{prefix}_{number}' is missing")
    if not low <= len(by_number) <= high:
        raise PermanentError(
            "contract_violation",
            f"the job needs {low} to {high} '{prefix}_<n>' inputs, got {len(by_number)}",
        )
    return [by_number[number] for number in range(1, highest + 1)], roles
