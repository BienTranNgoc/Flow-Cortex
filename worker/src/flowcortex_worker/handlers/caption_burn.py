"""Burns word-timed captions into a video and returns it with a preview frame."""

from __future__ import annotations

import json
import logging
import math
from collections.abc import Mapping
from pathlib import Path
from typing import Any

from ..captions.ass import build_ass, validate_words
from ..captions.fonts import audit_libass
from ..captions.style import resolve_style
from ..contracts.media_job import MediaJob
from ..errors import PermanentError
from ..runtime import ProcessRunner
from . import HandlerOutcome, OutputFile

log = logging.getLogger(__name__)

VIDEO_NAME = "video.mp4"
PREVIEW_NAME = "preview.png"
ASS_NAME = "captions.ass"


def handle(
    job: MediaJob,
    inputs: Mapping[str, Path],
    processes: ProcessRunner,
) -> HandlerOutcome:
    if set(inputs) != {"video"}:
        raise PermanentError(
            "contract_violation", "caption_burn needs exactly one input with key 'video'"
        )
    source = inputs["video"]
    width, height = _displayed_size(source, processes)

    try:
        words, offset = validate_words(job.params.get("words"), job.params.get("startOffset"))
        spec = job.params.get("style")
        style = resolve_style(spec, height)
        script, line_count = build_ass(words, offset, style, width, height)
    except ValueError as exc:
        raise PermanentError("contract_violation", str(exc)) from exc

    script_path = source.parent / ASS_NAME
    script_path.write_text(script, encoding="utf-8")
    video = source.parent / VIDEO_NAME
    # Verbose is the only level at which libass logs which font file it chose.
    completed = processes.run(
        [
            "ffmpeg",
            "-nostdin",
            "-hide_banner",
            "-loglevel",
            "verbose",
            "-nostats",
            "-y",
            "-protocol_whitelist",
            "file",
            "-i",
            str(source),
            "-map",
            "0:v:0",
            "-map",
            "0:a:0?",
            "-sn",
            "-dn",
            "-map_metadata",
            "-1",
            "-vf",
            f"ass={script_path}",
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-pix_fmt",
            "yuv420p",
            "-c:a",
            "aac",
            "-b:a",
            "128k",
            "-movflags",
            "+faststart",
            str(video),
        ]
    )
    if completed.returncode != 0 or not video.is_file() or video.stat().st_size == 0:
        video.unlink(missing_ok=True)
        raise PermanentError(
            "contract_violation", "the captions could not be burned into the video"
        )

    def family_of(path: str) -> str:
        queried = processes.run(["fc-query", "-f", "%{family[0]}", path])
        return queried.stdout.decode(errors="replace") if queried.returncode == 0 else ""

    try:
        warnings = audit_libass(completed.stderr.decode(errors="replace"), style.font, family_of)
    except ValueError as exc:
        video.unlink(missing_ok=True)
        raise PermanentError("contract_violation", str(exc)) from exc

    duration = _duration(video, processes)
    files = [OutputFile(VIDEO_NAME, "video/mp4", video)]
    preview = _preview(video, duration, processes)
    if preview is not None:
        files.append(OutputFile(PREVIEW_NAME, "image/png", preview))

    return HandlerOutcome(
        data={
            "width": width,
            "height": height,
            "durationSeconds": duration,
            "captionInfo": {
                "font": style.font,
                "lines": line_count,
                "styled": spec is not None,
                "fontWarnings": warnings,
            },
        },
        files=files,
    )


def _probe_json(argv: list[str], processes: ProcessRunner, what: str) -> dict[str, Any]:
    completed = processes.run(argv)
    if completed.returncode != 0:
        raise PermanentError("contract_violation", f"{what} could not be read as a video")
    try:
        report = json.loads(completed.stdout)
    except json.JSONDecodeError as exc:
        raise PermanentError("contract_violation", f"{what} could not be read as a video") from exc
    if not isinstance(report, dict):
        raise PermanentError("contract_violation", f"{what} could not be read as a video")
    return report


def _displayed_size(source: Path, processes: ProcessRunner) -> tuple[int, int]:
    report = _probe_json(
        [
            "ffprobe",
            "-v",
            "error",
            "-protocol_whitelist",
            "file",
            "-select_streams",
            "v:0",
            "-show_entries",
            "stream=width,height:stream_side_data=rotation",
            "-of",
            "json",
            str(source),
        ],
        processes,
        "the input",
    )
    streams = report.get("streams")
    if not isinstance(streams, list) or not streams or not isinstance(streams[0], dict):
        raise PermanentError("contract_violation", "caption_burn needs a video stream")
    stream = streams[0]
    width, height = stream.get("width"), stream.get("height")
    if not (
        isinstance(width, int)
        and isinstance(height, int)
        and not isinstance(width, bool)
        and not isinstance(height, bool)
        and width > 0
        and height > 0
    ):
        raise PermanentError("contract_violation", "the input video has no usable dimensions")
    for entry in stream.get("side_data_list") or []:
        rotation = entry.get("rotation") if isinstance(entry, dict) else None
        if (
            isinstance(rotation, int | float)
            and not isinstance(rotation, bool)
            and math.isfinite(rotation)
            and round(rotation) % 180 == 90
        ):
            return height, width
    return width, height


def _duration(video: Path, processes: ProcessRunner) -> float:
    report = _probe_json(
        [
            "ffprobe",
            "-v",
            "error",
            "-protocol_whitelist",
            "file",
            "-show_entries",
            "format=duration",
            "-of",
            "json",
            str(video),
        ],
        processes,
        "the captioned video",
    )
    try:
        duration = float(report["format"]["duration"])
    except (KeyError, TypeError, ValueError) as exc:
        raise PermanentError(
            "contract_violation", "the captioned video has no readable duration"
        ) from exc
    if not math.isfinite(duration) or duration <= 0:
        raise PermanentError("contract_violation", "the captioned video has no readable duration")
    return round(duration, 3)


def _preview(video: Path, duration: float, processes: ProcessRunner) -> Path | None:
    preview = video.parent / PREVIEW_NAME
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
            "-ss",
            f"{duration / 2:.2f}",
            "-i",
            str(video),
            "-frames:v",
            "1",
            str(preview),
        ]
    )
    if completed.returncode != 0 or not preview.is_file() or preview.stat().st_size == 0:
        preview.unlink(missing_ok=True)
        log.warning("the preview frame was skipped", extra={"exitCode": completed.returncode})
        return None
    return preview
