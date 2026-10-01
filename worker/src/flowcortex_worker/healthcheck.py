"""Container healthcheck: exit 0 while the main loop has touched the liveness file recently."""

import sys
import time
from pathlib import Path

LIVENESS_FILE = Path("/tmp/flowcortex-worker.alive")
MAX_AGE_SECONDS = 60.0


def check(path: Path = LIVENESS_FILE, max_age: float = MAX_AGE_SECONDS) -> int:
    try:
        age = time.time() - path.stat().st_mtime
    except FileNotFoundError:
        return 1
    return 0 if age <= max_age else 1


if __name__ == "__main__":
    sys.exit(check())
