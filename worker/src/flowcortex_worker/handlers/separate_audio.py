"""`separate_audio`: splits voice from background with demucs, in a child process."""

from __future__ import annotations

from collections.abc import Mapping
from pathlib import Path

from ..contracts.media_job import MediaJob
from ..errors import PermanentError, TransientError
from ..runtime import ProcessRunner
from . import HandlerOutcome, OutputFile
from ._audio_input import decode, has_audio, last_line, media_input, require_within, run_engine

LIMIT_SECONDS = 600
RATE = 44100
CHANNELS = 2
ENGINE_MODULE = "flowcortex_worker.engines.separate"
ENGINE_NAME = "htdemucs"
M4A_BITRATE = "128k"
OUTPUT_MIME = "audio/mp4"
STEMS = {
    "vocals": ("vocals",),
    "no_vocals": ("no_vocals",),
    "both": ("vocals", "no_vocals"),
}


def handle(
    job: MediaJob,
    inputs: Mapping[str, Path],
    processes: ProcessRunner,
) -> HandlerOutcome:
    source = media_input(inputs)
    requested = job.params.get("stems", "no_vocals")
    if not isinstance(requested, str) or requested not in STEMS:
        raise PermanentError(
            "contract_violation",
            f"stems must be one of {', '.join(STEMS)}",
        )

    work = source.parent
    if not has_audio(source, processes):
        raise PermanentError("no_audio_stream", "the input has no audio stream")
    decoded = work / "separation-input.wav"
    duration_ms = decode(source, decoded, RATE, CHANNELS, LIMIT_SECONDS, processes)
    require_within(duration_ms, LIMIT_SECONDS)

    stem_dir = work / "stems"
    stem_dir.mkdir(exist_ok=True)
    run_engine(
        ENGINE_MODULE,
        ["--input", str(decoded), "--output-dir", str(stem_dir)],
        processes,
        "separation_failed",
        ENGINE_NAME,
        work,
    )

    files: list[OutputFile] = []
    for stem in STEMS[requested]:
        wav = stem_dir / f"{stem}.wav"
        if not wav.is_file() or wav.stat().st_size == 0:
            raise PermanentError("separation_failed", f"{ENGINE_NAME} wrote no {stem} stem")
        files.append(_encode(wav, work / f"{stem}.m4a", processes))
    return HandlerOutcome(
        data={"stems": list(STEMS[requested]), "durationMs": duration_ms},
        files=files,
    )


def _encode(wav: Path, target: Path, processes: ProcessRunner) -> OutputFile:
    completed = processes.run(
        [
            "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
            "-protocol_whitelist", "file", "-i", str(wav), "-vn",
            "-ac", str(CHANNELS), "-ar", str(RATE),
            "-c:a", "aac", "-b:a", M4A_BITRATE, "-movflags", "+faststart", str(target),
        ]
    )  # fmt: skip
    if completed.returncode < 0:
        raise TransientError(f"ffmpeg was ended by signal {-completed.returncode}")
    if completed.returncode != 0 or not target.is_file() or target.stat().st_size == 0:
        raise PermanentError(
            "separation_failed",
            f"encoding the {wav.stem} stem failed: {last_line(completed, wav.parent)}",
        )
    return OutputFile(target.name, OUTPUT_MIME, target)
