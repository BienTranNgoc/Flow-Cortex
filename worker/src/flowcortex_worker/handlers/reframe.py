"""Cuts a window out of a video and reframes it to another aspect ratio in one encode."""

from __future__ import annotations

import shutil
from collections.abc import Mapping
from pathlib import Path

from ..contracts.media_job import MediaJob
from ..errors import PermanentError
from ..runtime import ProcessRunner
from ..video_analysis.streams import last_line, tool_failure
from ..video_ops import protocol
from ..video_ops.media import Clip, describe_output, probe_clip, read_window
from ..video_ops.reframe import check_params, face_x, follows_faces, reframe_filter
from . import HandlerOutcome, OutputFile

OUTPUT_NAME = "reframe.mp4"
OUTPUT_MIME = "video/mp4"
WORK_DIR = "vision"
_REPORTED = ("width", "height", "durationMs")
_FFMPEG = ["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y"]
# The detector sees a 640 px wide copy of one frame a second; its boxes are scaled back after.
SAMPLE_WIDTH = 640
SAMPLE_FILTER = f"fps=1,scale={SAMPLE_WIDTH}:-2"


def _seek(start: float) -> list[str]:
    return ["-ss", f"{start:.3f}"] if start > 0 else []


def _length(start: float, end: float, bounded: bool) -> list[str]:
    return ["-t", f"{end - start:.3f}"] if bounded else []


def _sample_argv(
    video: Path, clip: Clip, start: float, end: float, bounded: bool, work: Path
) -> list[str]:
    return [
        *_FFMPEG, "-protocol_whitelist", "file",
        *_seek(start), "-i", str(video), *_length(start, end, bounded),
        "-map", f"0:{clip.video.index}", "-an",
        "-vf", SAMPLE_FILTER, "-q:v", "3",
        str(work / protocol.FRAME_PATTERN),
    ]  # fmt: skip


def _encode_argv(
    video: Path,
    clip: Clip,
    start: float,
    end: float,
    bounded: bool,
    video_filter: str,
    output: Path,
) -> list[str]:
    return [
        *_FFMPEG, "-protocol_whitelist", "file",
        *_seek(start), "-i", str(video), *_length(start, end, bounded),
        "-map", f"0:{clip.video.index}", "-map", "0:a:0?",
        "-vf", video_filter,
        "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "128k",
        "-movflags", "+faststart",
        str(output),
    ]  # fmt: skip


def _find_faces(
    video: Path, clip: Clip, start: float, end: float, bounded: bool, processes: ProcessRunner
) -> tuple[float | None, int]:
    """Sample the window once a second, let the vision child find the faces, and take their x."""
    work = video.parent / WORK_DIR
    shutil.rmtree(work, ignore_errors=True)
    (work / protocol.FRAMES_DIR).mkdir(parents=True)
    try:
        sampled = processes.run(_sample_argv(video, clip, start, end, bounded, work))
        if sampled.returncode != 0:
            raise tool_failure(
                "ffmpeg",
                f"could not sample frames from the input (exit {sampled.returncode}): "
                f"{last_line(sampled.stderr)}",
            )
        result = protocol.run_child(
            "faces", work, {"scale": clip.video.width / SAMPLE_WIDTH}, processes
        )
        if "faces" not in result:
            raise PermanentError(
                "contract_violation", "the vision child's result has no 'faces' entry"
            )
        return face_x(result["faces"])
    finally:
        shutil.rmtree(work, ignore_errors=True)


def handle(
    job: MediaJob,
    inputs: Mapping[str, Path],
    processes: ProcessRunner,
) -> HandlerOutcome:
    if set(inputs) != {"video"}:
        raise PermanentError(
            "contract_violation",
            f"reframe needs exactly one input with key 'video' (got {sorted(inputs)})",
        )
    ratio, mode = check_params(job.params)
    video = inputs["video"]
    clip = probe_clip(video, processes)
    start, end = read_window(job.params, "startSec", "endSec", clip.duration_ms, "s")
    # With no endSec the encode runs to the end of the input; a measured end would only risk
    # cutting its last frame.
    bounded = "endSec" in job.params
    width, height = clip.video.width, clip.video.height

    centre: float | None = None
    faces = 0
    if follows_faces(width, height, ratio, mode):
        centre, faces = _find_faces(video, clip, start, end, bounded, processes)
    video_filter, method = reframe_filter(width, height, ratio, mode, centre, faces)

    output = video.parent / OUTPUT_NAME
    encoded = processes.run(_encode_argv(video, clip, start, end, bounded, video_filter, output))
    if encoded.returncode != 0:
        raise tool_failure(
            "ffmpeg",
            f"could not reframe the input (exit {encoded.returncode}): {last_line(encoded.stderr)}",
        )

    described = describe_output(output, processes)
    data = {key: described[key] for key in _REPORTED}
    return HandlerOutcome(
        data={**data, "method": method}, files=[OutputFile(OUTPUT_NAME, OUTPUT_MIME, output)]
    )
