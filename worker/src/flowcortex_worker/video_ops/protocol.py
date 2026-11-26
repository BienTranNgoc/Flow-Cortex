"""What a handler and the vision child agree on.

The child runs as `python -m flowcortex_worker.video_ops.vision <command>` with the handler's
work directory as its working directory, so nothing here is a path argument: every file is one of
the fixed relative names below, and the only argument is one of `COMMANDS`. It reads `request.json`
and writes `result.json`; a box is `[left, top, right, bottom]` in source pixels.

  faces   request {"scale"}; reads frames/f_*.jpg; result {"faces": [[box, ...] per frame]}
  track   request {"scale", "stride"}; reads analysis.mp4;
          result {"frames": n, "cuts": [frame, ...], "faces": [box | null per frame]}
  motion  request {}; reads window.mkv and output.mp4; result {"windowMotion", "outputMotion"}
          in source pixels a frame
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import TYPE_CHECKING, Any

from ..errors import PermanentError

if TYPE_CHECKING:
    from ..runtime import ProcessRunner

OK = 0
FAILED = 1
USAGE = 2
FACE_MODEL_MISSING = 4
RUNTIME_MISSING = 5

COMMANDS = ("faces", "track", "motion")

REQUEST_FILE = "request.json"
RESULT_FILE = "result.json"
FRAMES_DIR = "frames"
FRAME_PATTERN = "frames/f_%04d.jpg"
ANALYSIS_FILE = "analysis.mp4"
WINDOW_FILE = "window.mkv"
OUTPUT_FILE = "output.mp4"


def run_child(
    command: str, work_dir: Path, request: dict[str, Any], processes: ProcessRunner
) -> dict[str, Any]:
    """Run one vision command in `work_dir` and return its result; any failure is permanent."""
    if command not in COMMANDS:
        raise ValueError(f"unknown vision command {command!r}")
    (work_dir / REQUEST_FILE).write_text(json.dumps(request), encoding="utf-8")
    (work_dir / RESULT_FILE).unlink(missing_ok=True)
    completed = processes.run(
        [sys.executable, "-m", "flowcortex_worker.video_ops.vision", command], cwd=work_dir
    )
    if completed.returncode == FACE_MODEL_MISSING:
        raise PermanentError(
            "contract_violation", "the face detection model is missing from this worker image"
        )
    if completed.returncode == RUNTIME_MISSING:
        raise PermanentError(
            "contract_violation",
            "the vision runtime (OpenCV) is not installed on this worker image",
        )
    if completed.returncode != OK:
        # Imported here: the child imports this module and must not load the runner's broker client.
        from ..video_analysis.streams import last_line

        tail = last_line(completed.stderr) or "no output"
        raise PermanentError(
            "contract_violation",
            f"the vision child failed on {command!r} (exit {completed.returncode}): {tail}",
        )
    return _read_result(work_dir / RESULT_FILE, command)


def _read_result(path: Path, command: str) -> dict[str, Any]:
    try:
        result = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        result = None
    if not isinstance(result, dict):
        raise PermanentError(
            "contract_violation", f"the vision child wrote no readable result for {command!r}"
        )
    return result
