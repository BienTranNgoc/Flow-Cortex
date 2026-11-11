"""`beats`: beats, downbeats, tempo and drops of a piece of music, with beat_this in a child."""

from __future__ import annotations

import json
import math
from collections.abc import Mapping
from pathlib import Path

from ..audio import beat_grid
from ..contracts.media_job import MediaJob
from ..errors import PermanentError
from ..runtime import ProcessRunner
from . import HandlerOutcome
from ._audio_input import decode, has_audio, media_input, require_within, run_engine

LIMIT_SECONDS = 1200
RATE = 22050
CHANNELS = 1
ENGINE_MODULE = "flowcortex_worker.engines.beats"


def handle(
    _job: MediaJob,
    inputs: Mapping[str, Path],
    processes: ProcessRunner,
) -> HandlerOutcome:
    source = media_input(inputs)
    work = source.parent
    if not has_audio(source, processes):
        raise PermanentError("no_audio_stream", "the input has no audio stream")
    decoded = work / "beats-input.wav"
    duration_ms = decode(source, decoded, RATE, CHANNELS, LIMIT_SECONDS, processes)
    require_within(duration_ms, LIMIT_SECONDS)
    if duration_ms < beat_grid.MIN_AUDIO_MS:
        raise PermanentError("no_beats_detected", "the audio is too short to hold a pulse")

    raw_path = work / "beats.json"
    run_engine(
        ENGINE_MODULE,
        ["--input", str(decoded), "--output", str(raw_path)],
        processes,
        "beat_detection_failed",
        beat_grid.ENGINE,
        work,
    )
    beats_s, downbeats_s, rms, frame_ms = _read(raw_path)
    try:
        data = beat_grid.shape_result(beats_s, downbeats_s, rms, frame_ms, duration_ms)
    except beat_grid.NoBeats as none:
        raise PermanentError("no_beats_detected", str(none)) from none
    return HandlerOutcome(data=data, files=[])


def _numbers(value: object) -> list[float] | None:
    if not isinstance(value, list):
        return None
    out: list[float] = []
    for item in value:
        if isinstance(item, bool) or not isinstance(item, int | float) or not math.isfinite(item):
            return None
        out.append(float(item))
    return out


def _read(path: Path) -> tuple[list[float], list[float], list[float], float]:
    unreadable = PermanentError(
        "beat_detection_failed", f"{beat_grid.ENGINE} wrote an unreadable result"
    )
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as failed:
        raise unreadable from failed
    if not isinstance(raw, dict):
        raise unreadable
    beats_s, downbeats_s, rms = (_numbers(raw.get(k)) for k in ("beats", "downbeats", "rms"))
    frame_ms = raw.get("frameMs")
    if (
        beats_s is None
        or downbeats_s is None
        or rms is None
        or isinstance(frame_ms, bool)
        or not isinstance(frame_ms, int | float)
        or not math.isfinite(frame_ms)
        or frame_ms <= 0
    ):
        raise unreadable
    return beats_s, downbeats_s, rms, float(frame_ms)
