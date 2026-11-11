"""Input handling shared by the engine-backed audio handlers; standard library only."""

from __future__ import annotations

import math
import subprocess
import sys
import wave
from collections.abc import Mapping, Sequence
from pathlib import Path

from ..engines import EXIT_UNLOADABLE
from ..errors import ErrorCode, PermanentError, TransientError
from ..runtime import ProcessRunner

_MESSAGE_LIMIT = 300


def media_input(inputs: Mapping[str, Path]) -> Path:
    """`audio` when the job carries it (an earlier step's output), else the bound `media`."""
    for key in ("audio", "media"):
        if key in inputs:
            return inputs[key]
    raise PermanentError(
        "contract_violation", "the job needs an input with key 'audio' or 'media'"
    )


def last_line(completed: subprocess.CompletedProcess[bytes], *hide: Path) -> str:
    """The last stderr line, with local directories masked so a path never reaches a result."""
    text = (completed.stderr or b"").decode("utf-8", errors="replace")
    for path in hide:
        text = text.replace(str(path), "<work>")
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    return (lines[-1] if lines else "no error output")[:_MESSAGE_LIMIT]


def _run_tool(
    argv: Sequence[str],
    processes: ProcessRunner,
    code: ErrorCode,
    what: str,
    hide: Path,
) -> subprocess.CompletedProcess[bytes]:
    """Runs `argv`; a signal the watchdog did not send is transient, any other failure permanent."""
    completed = processes.run(list(argv))
    if completed.returncode < 0:
        raise TransientError(f"{what} was ended by signal {-completed.returncode}")
    if completed.returncode != 0:
        raise PermanentError(code, f"{what} failed: {last_line(completed, hide)}")
    return completed


def has_audio(path: Path, processes: ProcessRunner) -> bool:
    completed = _run_tool(
        [
            "ffprobe", "-v", "error", "-protocol_whitelist", "file",
            "-select_streams", "a:0", "-show_entries", "stream=index",
            "-of", "csv=p=0", str(path),
        ],
        processes, "audio_extraction_failed", "ffprobe", path.parent,
    )  # fmt: skip
    return bool(completed.stdout.strip())


def decode(
    path: Path,
    out: Path,
    rate: int,
    channels: int,
    limit_seconds: int,
    processes: ProcessRunner,
) -> int:
    """Decodes the first audio stream to PCM WAV (stopping one second past the limit) and
    returns its length in whole milliseconds, rounded up."""
    _run_tool(
        [
            "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
            "-protocol_whitelist", "file", "-i", str(path), "-map", "0:a:0", "-vn",
            "-ac", str(channels), "-ar", str(rate), "-t", str(limit_seconds + 1),
            "-c:a", "pcm_s16le", str(out),
        ],
        processes, "audio_extraction_failed", "ffmpeg", path.parent,
    )  # fmt: skip
    try:
        with wave.open(str(out), "rb") as decoded:
            frames, decoded_rate = decoded.getnframes(), decoded.getframerate()
    except (wave.Error, EOFError, OSError) as failed:
        raise PermanentError(
            "audio_extraction_failed", "the input has no decodable audio stream"
        ) from failed
    if frames <= 0:
        raise PermanentError("no_audio_stream", "the input's audio holds no sample")
    return math.ceil(frames * 1000 / decoded_rate)


def require_within(duration_ms: int, limit_seconds: int) -> None:
    if duration_ms > limit_seconds * 1000:
        raise PermanentError(
            "duration_exceeded",
            f"the audio is longer than the limit of {limit_seconds} seconds",
        )


def run_engine(
    module: str,
    args: Sequence[str],
    processes: ProcessRunner,
    code: ErrorCode,
    engine_name: str,
    work: Path,
) -> None:
    """Runs `python -m <module>` as a child. Exit 3 means the engine cannot load, any other
    non-zero exit is a permanent failure, and a foreign signal is transient."""
    completed = processes.run([sys.executable, "-m", module, *args])
    if completed.returncode == 0:
        return
    if completed.returncode < 0:
        raise TransientError(f"{engine_name} was ended by signal {-completed.returncode}")
    reason = last_line(completed, work)
    if completed.returncode == EXIT_UNLOADABLE:
        raise PermanentError(code, f"{engine_name} could not be loaded: {reason}")
    raise PermanentError(code, f"{engine_name} failed: {reason}")
