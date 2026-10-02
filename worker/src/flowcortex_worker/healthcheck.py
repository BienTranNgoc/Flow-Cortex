"""Container healthcheck: exit 0 while the main loop has touched the liveness file recently."""

import os
import stat
import sys
import time
from pathlib import Path

from .config import StateSettings

MAX_AGE_SECONDS = 60.0


def prepare_state_dir(directory: Path) -> None:
    """Creates the state directory `0700` if missing; refuses one another user could write to."""
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = directory.lstat()
    if not stat.S_ISDIR(info.st_mode):
        raise RuntimeError(f"state directory {directory} is not a directory")
    if info.st_uid != os.geteuid():
        raise RuntimeError(f"state directory {directory} is owned by uid {info.st_uid}")
    if info.st_mode & (stat.S_IWGRP | stat.S_IWOTH):
        raise RuntimeError(f"state directory {directory} is writable by other users")


def check(path: Path | None = None, max_age: float = MAX_AGE_SECONDS) -> int:
    path = path or StateSettings().liveness_file
    try:
        age = time.time() - path.stat().st_mtime
    except FileNotFoundError:
        return 1
    return 0 if age <= max_age else 1


if __name__ == "__main__":
    sys.exit(check())
