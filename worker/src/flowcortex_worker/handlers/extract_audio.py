"""Extracts the first audio stream as the canonical WAV used by later steps."""

from __future__ import annotations

from collections.abc import Mapping
from pathlib import Path

from ..contracts.media_job import MediaJob
from ..errors import PermanentError
from ..runtime import ProcessRunner
from . import HandlerOutcome, OutputFile

OUTPUT_NAME = "source-audio.wav"
OUTPUT_MIME = "audio/wav"


def handle(
    _job: MediaJob,
    inputs: Mapping[str, Path],
    processes: ProcessRunner,
) -> HandlerOutcome:
    media = inputs.get("media")
    if media is None:
        raise PermanentError(
            "contract_violation",
            "extract_audio needs an input with key 'media'",
        )

    output = media.parent / OUTPUT_NAME
    completed = processes.run(
        [
            "ffmpeg",
            "-nostdin",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-protocol_whitelist",
            "file",
            "-i",
            str(media),
            "-map",
            "0:a:0",
            "-vn",
            "-ac",
            "1",
            "-ar",
            "16000",
            "-c:a",
            "pcm_s16le",
            str(output),
        ]
    )
    if completed.returncode != 0 or not output.is_file() or output.stat().st_size == 0:
        output.unlink(missing_ok=True)
        raise PermanentError(
            "audio_extraction_failed",
            "the input has no extractable audio stream",
        )

    return HandlerOutcome(
        data={
            "sampleRate": 16000,
            "channels": 1,
            "codec": "pcm_s16le",
            "timelineOriginMs": 0,
        },
        files=[OutputFile(OUTPUT_NAME, OUTPUT_MIME, output)],
    )
