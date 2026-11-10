"""`inspect`: black runs, freezes and scene cuts of one video, measured in a single decode."""

from __future__ import annotations

from collections.abc import Mapping
from pathlib import Path

from ..contracts.media_job import MediaJob
from ..errors import PermanentError
from ..runtime import ProcessRunner
from ..video_analysis import frame_metadata
from ..video_analysis.streams import VideoStream, last_line, probe_video, tool_failure
from . import HandlerOutcome

CHECKER = "video.inspect/1"

# Passed explicitly, at the values the ffmpeg documentation gives as defaults, so that a later
# ffmpeg changing a default cannot change what the checker reports.
BLACKDETECT_PIC_TH = 0.98
BLACKDETECT_PIX_TH = 0.10
FREEZEDETECT_NOISE = "-60dB"
FREEZEDETECT_DURATION_SEC = 2
SCDET_THRESHOLD = 10

FRAMES_NAME = "inspect-frames.txt"


def handle(job: MediaJob, inputs: Mapping[str, Path], processes: ProcessRunner) -> HandlerOutcome:
    if set(inputs) != {"video"}:
        raise PermanentError("contract_violation", "inspect needs exactly one input, 'video'")
    faces = job.params.get("faces", False)
    if not isinstance(faces, bool):
        raise PermanentError("contract_violation", "inspect param 'faces' must be a boolean")
    if faces:
        raise PermanentError(
            "provider_capability_unavailable", "face measurement is not installed on this worker"
        )
    video = inputs["video"]
    duration_ms, stream = probe_video(video, processes, require_rate=True)
    if stream is None:
        return HandlerOutcome(data=_data(duration_ms, None, [], [], []), files=[])

    frames_file = video.parent / FRAMES_NAME
    chain = ",".join(
        [
            f"blackdetect=d={stream.one_frame_expr}:pic_th={BLACKDETECT_PIC_TH:g}"
            f":pix_th={BLACKDETECT_PIX_TH:g}",
            f"freezedetect=n={FREEZEDETECT_NOISE}:d={FREEZEDETECT_DURATION_SEC:g}",
            f"scdet=threshold={SCDET_THRESHOLD:g}",
            f"metadata=mode=print:file={frames_file}",
        ]
    )
    completed = processes.run(
        [
            "ffmpeg", "-nostdin", "-hide_banner", "-nostats", "-loglevel", "error",
            "-protocol_whitelist", "file", "-i", str(video), "-map", f"0:{stream.index}",
            "-vf", chain, "-f", "null", "-",
        ]
    )  # fmt: skip
    if completed.returncode != 0:
        raise tool_failure(
            "ffmpeg",
            f"could not decode the video (exit {completed.returncode}): "
            f"{last_line(completed.stderr)}",
        )
    text = frames_file.read_text(encoding="utf-8") if frames_file.is_file() else ""
    frames, events = frame_metadata.frame_events(text)
    if frames == 0:
        raise tool_failure("ffmpeg", "decoded no frame of the video stream")
    black = frame_metadata.black_runs(events, duration_ms, stream.one_frame_ms_floor)
    freeze = frame_metadata.runs(
        events, frame_metadata.FREEZE_START, frame_metadata.FREEZE_END, duration_ms
    )
    cuts = frame_metadata.cuts(events, duration_ms)
    return HandlerOutcome(data=_data(duration_ms, stream, black, freeze, cuts), files=[])


def _data(
    duration_ms: int,
    stream: VideoStream | None,
    black: list[dict[str, int]],
    freeze: list[dict[str, int]],
    cuts: list[int],
) -> dict[str, object]:
    return {
        "black": black,
        "freeze": freeze,
        "cutsMs": cuts,
        "durationMs": duration_ms,
        "width": stream.width if stream else None,
        "height": stream.height if stream else None,
        "fps": stream.fps if stream else None,
        "hasVideo": stream is not None,
        "checker": CHECKER,
    }
