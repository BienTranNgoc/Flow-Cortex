"""`probe`: ffprobe the uploaded media; the summary goes inline, the full report as a file."""

from __future__ import annotations

import json
from collections.abc import Mapping
from pathlib import Path
from typing import Any

from ..contracts.media_job import MediaJob
from ..errors import PermanentError
from ..runtime import ProcessRunner
from . import HandlerOutcome, OutputFile

REPORT_NAME = "video-details.json"
# Decoders ffprobe reports for a still picture; read by an image demuxer, it is no video.
IMAGE_CODECS = frozenset(
    {"png", "mjpeg", "jpegls", "jpeg2000", "bmp", "gif", "webp", "tiff", "qoi", "jpegxl", "apng"}
)


def handle(_job: MediaJob, inputs: Mapping[str, Path], processes: ProcessRunner) -> HandlerOutcome:
    media = inputs.get("media")
    if media is None:
        raise PermanentError("contract_violation", "probe needs an input with key 'media'")
    completed = processes.run(
        [
            "ffprobe", "-v", "error", "-protocol_whitelist", "file",
            "-print_format", "json", "-show_format", "-show_streams", str(media),
        ]
    )  # fmt: skip
    if completed.returncode != 0:
        raise PermanentError("probe_failed", "the input is not a readable audio or video file")
    try:
        report = json.loads(completed.stdout)
    except ValueError:
        raise PermanentError("probe_failed", "the media report could not be read") from None
    summary = summarize(report)
    if summary["videoCodec"] is None and summary["audioCodec"] is None:
        raise PermanentError("probe_failed", "the input has no audio or video stream")
    if _still_image(report):
        raise PermanentError("probe_failed", "the input is a still image, not audio or video")
    return HandlerOutcome(
        data=summary,
        files=[OutputFile(REPORT_NAME, "application/json", completed.stdout)],
    )


def _pictures(streams: list[Mapping[str, Any]]) -> list[Mapping[str, Any]]:
    """Video streams that move: ffprobe also lists embedded cover art as video (`attached_pic`)."""
    return [
        s
        for s in streams
        if s.get("codec_type") == "video" and (s.get("disposition") or {}).get("attached_pic") != 1
    ]


def summarize(report: Mapping[str, Any]) -> dict[str, Any]:
    fmt: Mapping[str, Any] = report.get("format") or {}
    streams: list[Mapping[str, Any]] = report.get("streams") or []
    video = next(iter(_pictures(streams)), None)
    audio = next((s for s in streams if s.get("codec_type") == "audio"), None)
    duration = fmt.get("duration")
    return {
        "container": fmt.get("format_name"),
        "durationMs": round(float(duration) * 1000) if duration is not None else None,
        "width": video.get("width") if video else None,
        "height": video.get("height") if video else None,
        "videoCodec": video.get("codec_name") if video else None,
        "audioCodec": audio.get("codec_name") if audio else None,
    }


def _still_image(report: Mapping[str, Any]) -> bool:
    streams: list[Mapping[str, Any]] = report.get("streams") or []
    fmt: Mapping[str, Any] = report.get("format") or {}
    if any(s.get("codec_type") == "audio" for s in streams):
        return False
    video = _pictures(streams)
    if not video or any(s.get("codec_name") not in IMAGE_CODECS for s in video):
        return False
    # Image demuxers (image2, png_pipe, jpeg_pipe, ...) report a one-frame duration or none;
    # a motion-JPEG movie keeps its container name and a real duration.
    container = str(fmt.get("format_name") or "")
    return fmt.get("duration") is None or container == "image2" or container.endswith("_pipe")
