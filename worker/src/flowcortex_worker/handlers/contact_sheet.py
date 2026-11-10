"""Samples a frame every `stepMs` of a clip and lays the frames out as one labelled image."""

from __future__ import annotations

import math
from collections.abc import Mapping
from pathlib import Path
from typing import Any

from PIL import Image

from ..contracts.media_job import MediaJob
from ..errors import PermanentError
from ..runtime import ProcessRunner
from ..video_analysis.sheet import (
    JPEG_QUALITY,
    clock,
    compose_sheet,
    grid,
    tile_size,
)
from ..video_analysis.streams import last_line, probe_video, tool_failure
from . import HandlerOutcome, OutputFile

# Twice the longest take a caller sends at 2 frames a second; a sheet of 60 tiles holds about
# 23 MB of pixels before encoding.
MAX_FRAMES_CEILING = 60
SHEET_NAME = "sheet.jpg"
SHEET_MIME = "image/jpeg"


def _whole_number(params: Mapping[str, Any], key: str) -> int:
    value = params.get(key)
    if type(value) is not int or value <= 0:
        raise PermanentError(
            "contract_violation", f"{key} must be a whole number above 0 (got {value!r})"
        )
    return value


def handle(
    job: MediaJob,
    inputs: Mapping[str, Path],
    processes: ProcessRunner,
) -> HandlerOutcome:
    if set(inputs) != {"video"}:
        raise PermanentError(
            "contract_violation",
            f"contact_sheet needs exactly one input with key 'video' (got {sorted(inputs)})",
        )
    step_ms = _whole_number(job.params, "stepMs")
    max_frames = _whole_number(job.params, "maxFrames")
    if max_frames > MAX_FRAMES_CEILING:
        raise PermanentError(
            "contract_violation",
            f"maxFrames must be at most {MAX_FRAMES_CEILING} (got {max_frames})",
        )

    video_path = inputs["video"]
    duration_ms, stream = probe_video(video_path, processes)
    if stream is None:
        raise tool_failure("ffprobe", "the input has no moving picture")
    needed = math.ceil(duration_ms / step_ms)
    if needed > max_frames:
        raise PermanentError(
            "duration_exceeded",
            f"{duration_ms} ms needs {needed} frames at {step_ms} ms, over maxFrames {max_frames}",
        )

    tile_w, tile_h = tile_size(stream.width, stream.height)
    tiles_dir = video_path.parent / "sheet-tiles"
    tiles_dir.mkdir(exist_ok=True)
    completed = processes.run(
        [
            "ffmpeg", "-nostdin", "-hide_banner", "-nostats", "-loglevel", "error",
            "-protocol_whitelist", "file",
            "-i", str(video_path),
            "-map", f"0:{stream.index}",
            "-vf", f"fps=1000/{step_ms}:round=up,scale={tile_w}:{tile_h}:flags=area",
            "-start_number", "0",
            str(tiles_dir / "tile_%04d.png"),
        ]
    )  # fmt: skip
    if completed.returncode != 0:
        raise tool_failure(
            "ffmpeg",
            f"could not decode the input (exit {completed.returncode}): "
            f"{last_line(completed.stderr)}",
        )

    # Slot k is the frame at k * stepMs; a slot at or after the container length is dropped.
    slots = [k for k in range(needed) if (tiles_dir / f"tile_{k:04d}.png").is_file()]
    if not slots:
        raise tool_failure("ffmpeg", "no frame could be sampled")
    tiles: list[Image.Image] = []
    for k in slots:
        with Image.open(tiles_dir / f"tile_{k:04d}.png") as tile:
            tiles.append(tile.convert("RGB"))
    sheet = compose_sheet(tiles, [clock(k * step_ms) for k in slots], tile_w, tile_h)
    output = video_path.parent / SHEET_NAME
    sheet.save(output, format="JPEG", quality=JPEG_QUALITY)

    cols, rows = grid(len(tiles), tile_w, tile_h)
    return HandlerOutcome(
        data={
            "frames": len(tiles),
            "stepMs": step_ms,
            "cols": cols,
            "rows": rows,
            "tileWidth": tile_w,
            "tileHeight": tile_h,
            "width": sheet.width,
            "height": sheet.height,
        },
        files=[OutputFile(SHEET_NAME, SHEET_MIME, output)],
    )
