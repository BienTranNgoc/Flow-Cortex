"""Image self-check: load every model offline, then clean a generated picture with a caption.

Run as `python -m flowcortex_worker.overlay.selfcheck`; exits non-zero on the first failure.
"""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

import cv2
import numpy as np
from numpy.typing import NDArray

from . import protocol
from .models import ModelEngines
from .params import parse_params

SIDE = 1024
CAPTION = "SALE 50% OFF TODAY"
SEED = 11
CHILD_TIMEOUT_SECONDS = 600


def caption_image() -> NDArray[np.uint8]:
    rng = np.random.default_rng(SEED)
    noise = rng.integers(40, 200, (SIDE, SIDE, 3)).astype(np.uint8)
    background = np.asarray(cv2.GaussianBlur(noise, (0, 0), 6), dtype=np.uint8)
    cv2.rectangle(background, (80, 420), (SIDE - 80, 600), (255, 255, 255), -1)
    cv2.putText(
        background, CAPTION, (110, 535), cv2.FONT_HERSHEY_SIMPLEX, 2.2, (0, 0, 0), 6, cv2.LINE_AA
    )
    return background


def check() -> list[str]:
    """Problems found; empty when the image cleans a captioned picture."""
    with tempfile.TemporaryDirectory() as scratch:
        root = Path(scratch)
        encoded, png = cv2.imencode(".png", caption_image())
        if not encoded:
            return ["could not encode the generated input"]
        (root / protocol.INPUT_FILE).write_bytes(png.tobytes())
        (root / protocol.PARAMS_FILE).write_text(
            json.dumps(protocol.params_to_json(parse_params({"extractText": True}))),
            encoding="utf-8",
        )
        done = subprocess.run(
            [sys.executable, "-m", "flowcortex_worker.overlay"],
            cwd=root,
            capture_output=True,
            text=True,
            timeout=CHILD_TIMEOUT_SECONDS,
            check=False,
        )
        if done.returncode != protocol.OK:
            return [f"child exited {done.returncode}: {done.stderr.strip()[-400:]}"]
        cleaned = cv2.imread(str(root / protocol.CLEAN_FILE), cv2.IMREAD_UNCHANGED)
        data = json.loads((root / protocol.RESULT_FILE).read_text(encoding="utf-8"))
    # After the child has exited: two copies of the models do not fit a small CI runner.
    ModelEngines().load_all()
    problems: list[str] = []
    if cleaned is None or cleaned.shape[:2] != (SIDE, SIDE):
        problems.append("clean.png is missing or not the size of the input")
    if data.get("regionsRemoved", 0) < 1:
        problems.append("no text region was removed")
    if not data.get("textLayers"):
        problems.append("no text layer was read")
    return problems


def main() -> int:
    try:
        problems = check()
    except Exception as error:  # a missing model file or an import failure must fail the job
        sys.stderr.write(f"selfcheck failed: {type(error).__name__}: {error}\n")
        return 1
    for problem in problems:
        sys.stderr.write(f"selfcheck failed: {problem}\n")
    if not problems:
        sys.stdout.write("selfcheck ok\n")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
