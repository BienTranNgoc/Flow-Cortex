"""Probes and ffmpeg runs shared by the assembly handlers; standard library only."""

from __future__ import annotations

import math
import os
import re
import subprocess
from collections.abc import Sequence
from pathlib import Path
from typing import Literal

from ..errors import ErrorCode, PermanentError
from ..runtime import ProcessRunner
from ._audio_input import _run_tool, last_line

_INTEGRATED = re.compile(r"^\s*I:\s*(-?\d+(?:\.\d+)?) LUFS", re.MULTILINE)


def input_args(path: Path) -> tuple[str, ...]:
    """`-protocol_whitelist file` applies to the next input only, so every `-i` carries its own."""
    return ("-protocol_whitelist", "file", "-i", str(path))


def duration_seconds(path: Path, processes: ProcessRunner, code: ErrorCode) -> float:
    completed = _run_tool(
        [
            "ffprobe", "-v", "error", *input_args(path),
            "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1",
        ],
        processes, code, "ffprobe", path.parent,
    )  # fmt: skip
    try:
        seconds = float(completed.stdout.decode("utf-8", errors="replace").strip())
    except ValueError:
        seconds = math.nan
    if not math.isfinite(seconds) or seconds < 0:
        raise PermanentError(code, "the input has no readable duration")
    return seconds


def input_seconds(key: str, path: Path, processes: ProcessRunner, code: ErrorCode) -> float:
    """`duration_seconds` of the input `key`; a failure names the key."""
    try:
        return duration_seconds(path, processes, code)
    except PermanentError as failed:
        raise PermanentError(failed.code, f"input '{key}': {failed.message}") from failed


def has_stream(
    path: Path,
    kind: Literal["audio", "video"],
    processes: ProcessRunner,
    code: ErrorCode,
) -> bool:
    """Whether the first audio (`a:0`) or moving-picture (`v:0`) stream exists; a failed probe is
    `code`. Cover art (an `attached_pic` stream in an M4A or MP3) is not a moving picture."""
    if kind == "audio":
        entries, selector = "stream=index", "a:0"
    else:
        entries, selector = "stream=index:stream_disposition=attached_pic", "v:0"
    completed = _run_tool(
        [
            "ffprobe", "-v", "error", *input_args(path),
            "-select_streams", selector, "-show_entries", entries, "-of", "csv=p=0",
        ],
        processes, code, "ffprobe", path.parent,
    )  # fmt: skip
    row = completed.stdout.decode("utf-8", errors="replace").strip()
    if kind == "audio":
        return bool(row)
    return bool(row) and row.rpartition(",")[2] == "0"


def require_audio(key: str, path: Path, processes: ProcessRunner) -> None:
    """The input has audio, else `no_audio_stream`; a failed probe is `audio_extraction_failed`.
    Both name `key`."""
    try:
        present = has_stream(path, "audio", processes, "audio_extraction_failed")
    except PermanentError as failed:
        raise PermanentError(failed.code, f"input '{key}': {failed.message}") from failed
    if not present:
        raise PermanentError("no_audio_stream", f"input '{key}' has no audio stream")


def require_video(key: str, path: Path, processes: ProcessRunner) -> None:
    """The input has a moving picture: `input_rejected` naming `key` when it has none or cannot
    be probed."""
    try:
        present = has_stream(path, "video", processes, "input_rejected")
    except PermanentError as failed:
        raise PermanentError(failed.code, f"input '{key}': {failed.message}") from failed
    if not present:
        raise PermanentError("input_rejected", f"input '{key}' has no video stream")


def ceil_ms(seconds: float) -> int:
    """Whole milliseconds, rounded up; the microsecond rounding first keeps float noise (3.1 s as
    3100.0000000000005 ms) from adding one."""
    return math.ceil(round(seconds * 1000, 3))


def mask_dir(sources: Sequence[Path], scratch: Path) -> Path:
    """The directory every input and the output sit under, so no stderr line names a local path.

    Each download lives in a directory of its own; a root as the common parent would mask every
    slash, so the output's own directory is the fallback.
    """
    common = Path(os.path.commonpath([str(scratch), *(str(source.parent) for source in sources)]))
    return scratch if common == Path(common.anchor) else common


def loudness(path: Path, stream: str, processes: ProcessRunner, code: ErrorCode) -> float | None:
    """Integrated loudness in LUFS of `stream` (`a:0`), or None when ffmpeg ran but printed no
    reading (a source with no sample). An ffmpeg exit that is not zero is `code`: the source
    could not be decoded, and the encode that follows would fail on it too.

    No `-loglevel error`: it hides the Summary the value is read from.
    """
    completed = _run_tool(
        [
            "ffmpeg", "-nostdin", "-hide_banner", "-nostats", *input_args(path),
            "-map", f"0:{stream}", "-af", "ebur128=framelog=quiet", "-f", "null", "-",
        ],
        processes, code, "the loudness measurement", path.parent,
    )  # fmt: skip
    text = completed.stderr.decode("utf-8", errors="replace")
    found = _INTEGRATED.search(text.rpartition("Summary:")[2])
    return float(found.group(1)) if found else None


def run_ffmpeg(
    argv: Sequence[str],
    processes: ProcessRunner,
    code: ErrorCode,
    what: str,
    hide: Path,
    output: Path | None = None,
) -> subprocess.CompletedProcess[bytes]:
    """Runs `argv`: a signal the runner did not send is transient, a non-zero exit or an `output`
    that is missing or empty is `code` with the last stderr line."""
    completed = _run_tool(argv, processes, code, what, hide)
    if output is not None and (not output.is_file() or output.stat().st_size == 0):
        raise PermanentError(code, f"{what} wrote no output: {last_line(completed, hide)}")
    return completed
