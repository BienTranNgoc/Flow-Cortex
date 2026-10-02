"""The container is healthy only while the main loop keeps touching its liveness file."""

from __future__ import annotations

import os
import stat
import time
from pathlib import Path

import pytest

from flowcortex_worker.config import StateSettings
from flowcortex_worker.healthcheck import check, prepare_state_dir


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


def test_healthcheck_reads_the_file_in_the_configured_state_dir(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("WORKER_STATE_DIR", str(tmp_path))
    assert check() == 1

    StateSettings().liveness_file.touch()

    assert StateSettings().liveness_file == tmp_path / "alive"
    assert check() == 0


def test_missing_state_dir_is_created_private(tmp_path: Path) -> None:
    state = tmp_path / "state"

    prepare_state_dir(state)

    assert stat.S_IMODE(state.stat().st_mode) == 0o700


@pytest.mark.parametrize("mode", [0o777, 0o1777, 0o720, 0o702])
def test_state_dir_others_can_write_is_refused(tmp_path: Path, mode: int) -> None:
    state = tmp_path / "state"
    state.mkdir()
    state.chmod(mode)

    with pytest.raises(RuntimeError, match="writable by other users"):
        prepare_state_dir(state)


def test_state_dir_that_is_a_symlink_is_refused(tmp_path: Path) -> None:
    (tmp_path / "real").mkdir(mode=0o700)
    link = tmp_path / "link"
    link.symlink_to(tmp_path / "real")

    with pytest.raises(RuntimeError, match="not a directory"):
        prepare_state_dir(link)
