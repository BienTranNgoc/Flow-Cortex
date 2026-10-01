"""The container is healthy only while the main loop keeps touching its liveness file."""

from __future__ import annotations

import os
import time
from pathlib import Path

from flowcortex_worker.healthcheck import check


def test_fresh_file_is_healthy(tmp_path: Path) -> None:
    alive = tmp_path / "alive"
    alive.touch()

    assert check(alive) == 0


def test_file_older_than_sixty_seconds_is_unhealthy(tmp_path: Path) -> None:
    alive = tmp_path / "alive"
    alive.touch()
    stale = time.time() - 61
    os.utime(alive, (stale, stale))

    assert check(alive) == 1


def test_missing_file_is_unhealthy(tmp_path: Path) -> None:
    assert check(tmp_path / "alive") == 1
