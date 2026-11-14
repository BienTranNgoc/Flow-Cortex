"""White-block preview of one storyboard shot: staging card, then a headless Blender render."""

from __future__ import annotations

import json
import logging
import os
from collections.abc import Mapping
from pathlib import Path
from typing import Any

from ..contracts.media_job import MediaJob
from ..errors import PermanentError, TransientError
from ..preview import build_card, dumps, send_card
from ..preview.vocabulary import FPS, FRAME_BY_ASPECT
from ..runtime import ProcessRunner
from . import HandlerOutcome, OutputFile

log = logging.getLogger(__name__)

DEFAULT_BLENDER_BIN = "/opt/blender/blender"
DEFAULT_SCENE_SCRIPT = "/opt/flowcortex/blender/preview_scene.py"
DEFAULT_FONT = "/usr/share/fonts/truetype/flowcortex/be-vietnam-pro/be-vietnam-pro-700.ttf"
INPUT_KEY = "doc"
PREVIEW_NAME = "preview.mp4"
SEND_NAME = "preview-send.mp4"
CARD_NAME = "staging-card.json"
SEND_CARD_NAME = "staging-card-send.json"
VIDEO_MIME = "video/mp4"
LOG_TAIL_LINES = 25
PARAMS = frozenset({"shotId", "aspect", "send"})


def handle(
    job: MediaJob,
    inputs: Mapping[str, Path],
    processes: ProcessRunner,
) -> HandlerOutcome:
    shot_id, aspect, send = _params(job.params)
    source = _source(inputs)
    doc = _read_document(source)
    try:
        card = build_card(doc, shot_id, aspect)
        send_version = send_card(card) if send else None
    except (ArithmeticError, KeyError, TypeError, ValueError) as refused:
        raise PermanentError("contract_violation", f"preview_video: {refused}") from refused
    blender = _blender()

    scratch = source.parent
    card_path = scratch / CARD_NAME
    card_path.write_text(dumps(card), encoding="utf-8")
    report = _render(blender, card_path, scratch / PREVIEW_NAME, processes)
    files = [
        OutputFile(PREVIEW_NAME, VIDEO_MIME, scratch / PREVIEW_NAME),
        OutputFile(CARD_NAME, "application/json", card_path),
    ]
    render_seconds = _seconds(report)
    if send_version is not None:
        send_path = scratch / SEND_CARD_NAME
        send_path.write_text(dumps(send_version), encoding="utf-8")
        send_report = _render(blender, send_path, scratch / SEND_NAME, processes)
        render_seconds += _seconds(send_report)
        files.append(OutputFile(SEND_NAME, VIDEO_MIME, scratch / SEND_NAME))
    return HandlerOutcome(
        data={
            "shotId": shot_id,
            "frames": report["frames"],
            "width": report["width"],
            "height": report["height"],
            "durationSec": round(report["frames"] / FPS, 3),
            "subjectInFrame": report["subjectInFrame"],
            "blender": report["blender"],
            "renderer": report["renderer"],
            "renderSec": round(render_seconds, 3),
            "warnings": card["warnings"],
        },
        files=files,
    )


def _params(params: Mapping[str, Any]) -> tuple[str, str, bool]:
    unknown = sorted(set(params) - PARAMS)
    if unknown:
        raise PermanentError(
            "contract_violation", f"preview_video does not accept param '{unknown[0]}'"
        )
    shot_id = params.get("shotId")
    if not isinstance(shot_id, str) or not shot_id:
        raise PermanentError("contract_violation", "preview_video requires params.shotId")
    aspect = params.get("aspect")
    if aspect not in FRAME_BY_ASPECT:
        raise PermanentError(
            "contract_violation",
            f"preview_video requires params.aspect to be one of {', '.join(FRAME_BY_ASPECT)}",
        )
    send = params.get("send", False)
    if not isinstance(send, bool):
        raise PermanentError("contract_violation", "params.send must be true or false")
    return shot_id, aspect, send


def _source(inputs: Mapping[str, Path]) -> Path:
    unexpected = sorted(set(inputs) - {INPUT_KEY})
    if unexpected:
        raise PermanentError(
            "contract_violation", f"preview_video does not accept input '{unexpected[0]}'"
        )
    if INPUT_KEY not in inputs:
        raise PermanentError("contract_violation", f"preview_video requires input '{INPUT_KEY}'")
    return inputs[INPUT_KEY]


def _read_document(source: Path) -> Any:
    try:
        return json.loads(source.read_text(encoding="utf-8"))
    except (OSError, ValueError) as unreadable:
        raise PermanentError(
            "contract_violation", "preview_video input 'doc' is not readable JSON"
        ) from unreadable


def _blender() -> str:
    path = os.environ.get("BLENDER_BIN", DEFAULT_BLENDER_BIN)
    if not (os.path.isfile(path) and os.access(path, os.X_OK)):
        raise PermanentError("contract_violation", f"Blender is not available at '{path}'")
    return path


def _render(blender: str, card: Path, out: Path, processes: ProcessRunner) -> dict[str, Any]:
    report_path = out.with_name(out.name + ".report.json")
    out.unlink(missing_ok=True)
    report_path.unlink(missing_ok=True)
    completed = processes.run(
        [
            blender,
            "-b",
            "--factory-startup",
            "--python-exit-code",
            "1",
            "-P",
            os.environ.get("PREVIEW_SCENE_SCRIPT", DEFAULT_SCENE_SCRIPT),
            "--",
            str(card),
            str(out),
            os.environ.get("PREVIEW_FONT", DEFAULT_FONT),
        ]
    )
    if completed.returncode != 0:
        output = (completed.stdout + completed.stderr).decode("utf-8", errors="replace")
        log.warning(
            "Blender exited abnormally",
            extra={
                "exitCode": completed.returncode,
                "output": "\n".join(output.strip().splitlines()[-LOG_TAIL_LINES:]),
            },
        )
        if completed.returncode < 0:
            raise TransientError(f"Blender was ended by signal {-completed.returncode}")
        raise PermanentError(
            "contract_violation", f"Blender failed with exit code {completed.returncode}"
        )
    return _read_report(report_path, out)


def _read_report(path: Path, out: Path) -> dict[str, Any]:
    try:
        report = json.loads(path.read_text(encoding="utf-8"))
        if not (out.is_file() and out.stat().st_size > 0):
            raise ValueError("no video")
        if not isinstance(report, dict):
            raise ValueError("not an object")
        for key in ("frames", "width", "height"):
            value = report[key]
            if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
                raise ValueError(key)
        if not isinstance(report["subjectInFrame"], bool):
            raise ValueError("subjectInFrame")
        for key in ("blender", "renderer"):
            if not isinstance(report[key], str):
                raise ValueError(key)
        _seconds(report)
    except (OSError, KeyError, TypeError, ValueError) as unreadable:
        raise PermanentError(
            "contract_violation", "Blender wrote no readable video and report"
        ) from unreadable
    return report


def _seconds(report: Mapping[str, Any]) -> float:
    seconds = report["seconds"]
    total = 0.0
    for key in ("build", "render"):
        value = seconds[key]
        if not isinstance(value, int | float) or isinstance(value, bool) or value < 0:
            raise ValueError(key)
        total += value
    return total
