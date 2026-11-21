"""`speakers`: finds who speaks when, in a child process; the result is data only."""

from __future__ import annotations

import json
from collections.abc import Mapping
from pathlib import Path

from ..audio.speaker_turns import SEPARATOR, turns
from ..contracts.media_job import MediaJob
from ..errors import PermanentError
from ..runtime import ProcessRunner
from . import HandlerOutcome
from ._audio_input import decode, has_audio, media_input, require_within, run_engine

LIMIT_SECONDS = 1800
RATE = 16000
CHANNELS = 1
ENGINE_MODULE = "flowcortex_worker.engines.speakers"


def handle(
    _job: MediaJob,
    inputs: Mapping[str, Path],
    processes: ProcessRunner,
) -> HandlerOutcome:
    source = media_input(inputs)
    work = source.parent
    if not has_audio(source, processes):
        raise PermanentError("no_audio_stream", "the input has no audio stream")
    decoded = work / "speakers-input.wav"
    duration_ms = decode(source, decoded, RATE, CHANNELS, LIMIT_SECONDS, processes)
    require_within(duration_ms, LIMIT_SECONDS)

    result = work / "speakers.json"
    run_engine(
        ENGINE_MODULE,
        ["--input", str(decoded), "--output", str(result)],
        processes,
        "diarization_failed",
        SEPARATOR,
        work,
    )
    shaped = turns(_read_segments(result))
    return HandlerOutcome(
        data={
            "turns": shaped,
            "count": len({turn["label"] for turn in shaped}),
            "durationMs": duration_ms,
            "separator": SEPARATOR,
        },
        files=[],
    )


def _read_segments(path: Path) -> list[tuple[float, float, int]]:
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
        return [(float(start), float(end), int(label)) for start, end, label in raw]
    except (OSError, ValueError, TypeError) as failed:
        raise PermanentError(
            "diarization_failed", f"{SEPARATOR} wrote an unreadable result"
        ) from failed
