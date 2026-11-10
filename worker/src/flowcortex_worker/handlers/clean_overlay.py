"""Removes text and watermarks from a still image by running the overlay engine as a child.

The child process owns numpy, OpenCV and torch, so this module imports only the standard
library and the worker package, and the watchdog or a cancel can kill a long inpainting pass.
"""

from __future__ import annotations

import json
import shutil
import sys
from collections.abc import Mapping
from pathlib import Path
from typing import Any

from ..contracts.media_job import MediaJob
from ..errors import PermanentError
from ..overlay import protocol
from ..overlay.params import parse_params
from ..runtime import ProcessRunner
from . import HandlerOutcome, OutputFile

PARAMS_NAME = "overlay-params.json"
OUT_DIR_NAME = "overlay-out"
STDERR_TAIL_CHARS = 300


def handle(
    job: MediaJob,
    inputs: Mapping[str, Path],
    processes: ProcessRunner,
) -> HandlerOutcome:
    params = parse_params(job.params)
    if set(inputs) != {"image"}:
        raise PermanentError(
            "contract_violation", "clean_overlay needs exactly one input with key 'image'"
        )
    source = inputs["image"]

    params_path = source.parent / PARAMS_NAME
    out_dir = source.parent / OUT_DIR_NAME
    shutil.rmtree(out_dir, ignore_errors=True)
    out_dir.mkdir()
    params_path.write_text(json.dumps(protocol.params_to_json(params)), encoding="utf-8")

    try:
        completed = processes.run(
            [
                sys.executable,
                "-m",
                "flowcortex_worker.overlay",
                str(source),
                str(params_path),
                str(out_dir),
            ]
        )
        clean = out_dir / protocol.CLEAN_FILE
        _raise_for_exit(completed.returncode, completed.stderr)
        data = _read_result(out_dir / protocol.RESULT_FILE)
        if not clean.is_file() or clean.stat().st_size == 0:
            raise PermanentError("contract_violation", "the overlay engine produced no image")
    except BaseException:
        shutil.rmtree(out_dir, ignore_errors=True)
        raise
    finally:
        params_path.unlink(missing_ok=True)

    return HandlerOutcome(data=data, files=[OutputFile(protocol.CLEAN_FILE, "image/png", clean)])


def _raise_for_exit(code: int, stderr: bytes) -> None:
    if code == protocol.OK:
        return
    if code == protocol.INPUT_REJECTED:
        raise PermanentError(
            "input_rejected", "the image could not be decoded or is larger than the pixel cap"
        )
    if code == protocol.FACE_MODEL_MISSING:
        raise PermanentError(
            "contract_violation", "the face detection model is missing from this worker image"
        )
    if code == protocol.RUNTIME_MISSING:
        raise PermanentError(
            "contract_violation", "the overlay runtime is not installed on this worker image"
        )
    lines = stderr.decode(errors="replace").strip().splitlines()
    tail = lines[-1][-STDERR_TAIL_CHARS:] if lines else "no output"
    raise PermanentError("contract_violation", f"the overlay engine failed (exit {code}): {tail}")


def _read_result(path: Path) -> dict[str, Any]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise PermanentError(
            "contract_violation", "the overlay engine wrote no readable result"
        ) from exc
    if not isinstance(data, dict):
        raise PermanentError("contract_violation", "the overlay engine wrote no readable result")
    return data
