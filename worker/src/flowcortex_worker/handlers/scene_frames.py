"""Normalises a clip to a standard size at 24 fps and keeps one frame of each of its scenes."""

from __future__ import annotations

from collections.abc import Mapping
from pathlib import Path
from typing import Any

from ..contracts.media_job import MediaJob
from ..errors import PermanentError
from ..runtime import ProcessRunner
from ..video_analysis.frame_metadata import selected_times
from ..video_analysis.scenes import (
    DEFAULT_SCENE_THRESHOLD,
    FPS,
    MAX_SEC,
    MIN_SEC,
    STANDARD_SIZES,
    aspect,
    boundaries,
    given_bounds,
    middle_frame,
)
from ..video_analysis.streams import finite_param, last_line, probe_video, tool_failure
from . import HandlerOutcome, OutputFile

NORMALIZED_NAME = "normalized.mp4"
NORMALIZED_MIME = "video/mp4"
FRAME_MIME = "image/jpeg"
_QUIET = ["-nostdin", "-hide_banner", "-loglevel", "error", "-protocol_whitelist", "file"]
THRESHOLD_BOUNDS = (0.0, 1.0)


def _threshold(params: Mapping[str, Any]) -> float:
    if params.get("sceneThreshold") is None:
        return DEFAULT_SCENE_THRESHOLD
    value = finite_param(params, "sceneThreshold")
    low, high = THRESHOLD_BOUNDS
    if not low < value < high:
        raise PermanentError(
            "contract_violation",
            f"param 'sceneThreshold' must be above {low:g} and below {high:g} (got {value!r})",
        )
    return value


def _run_ffmpeg(processes: ProcessRunner, argv: list[str], what: str) -> None:
    completed = processes.run(argv)
    if completed.returncode != 0:
        raise tool_failure(
            "ffmpeg", f"{what} failed (exit {completed.returncode}): {last_line(completed.stderr)}"
        )


def _count_frames(path: Path, processes: ProcessRunner) -> int:
    completed = processes.run(
        [
            "ffprobe", "-v", "error", "-protocol_whitelist", "file",
            "-count_frames", "-select_streams", "v:0",
            "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0", str(path),
        ]
    )  # fmt: skip
    if completed.returncode != 0:
        raise tool_failure(
            "ffprobe",
            f"could not count the frames (exit {completed.returncode}): "
            f"{last_line(completed.stderr)}",
        )
    first = completed.stdout.decode("utf-8", "replace").strip().split(",")[0]
    if not first.isdigit() or int(first) <= 0:
        raise tool_failure("ffprobe", f"the normalised clip has no frames (got {first!r})")
    return int(first)


def _detected_bounds(
    normalized: Path, threshold: float, total: int, processes: ProcessRunner
) -> list[int]:
    metadata = normalized.parent / "scenes.txt"
    metadata.unlink(missing_ok=True)
    _run_ffmpeg(
        processes,
        [
            "ffmpeg", *_QUIET, "-nostats",
            "-i", str(normalized),
            "-map", "0:v:0",
            "-vf", f"select='gt(scene,{threshold:.6f})',metadata=mode=print:file={metadata}",
            "-f", "null", "-",
        ],
        "scene detection",
    )  # fmt: skip
    # No frame passed the selection: ffmpeg leaves no metadata file, which means no cut.
    text = metadata.read_text(errors="replace") if metadata.is_file() else ""
    return boundaries(selected_times(text), total)


def handle(
    job: MediaJob,
    inputs: Mapping[str, Path],
    processes: ProcessRunner,
) -> HandlerOutcome:
    if set(inputs) != {"video"}:
        raise PermanentError(
            "contract_violation",
            f"scene_frames needs exactly one input with key 'video' (got {sorted(inputs)})",
        )
    threshold = _threshold(job.params)
    given = given_bounds(job.params.get("bounds"))

    video_path = inputs["video"]
    duration_ms, stream = probe_video(video_path, processes)
    if stream is None:
        raise tool_failure("ffprobe", "the input has no moving picture")
    if duration_ms < MIN_SEC * 1000:
        raise PermanentError(
            "input_rejected", f"the clip lasts {duration_ms} ms, under {MIN_SEC:g} s"
        )
    if duration_ms > MAX_SEC * 1000:
        raise PermanentError(
            "duration_exceeded", f"the clip lasts {duration_ms} ms, over {MAX_SEC:g} s"
        )

    label = aspect(stream.width, stream.height)
    out_w, out_h = STANDARD_SIZES[label]
    workdir = video_path.parent
    normalized = workdir / NORMALIZED_NAME
    _run_ffmpeg(
        processes,
        [
            "ffmpeg", *_QUIET, "-y",
            "-i", str(video_path),
            "-map", f"0:{stream.index}", "-map", "0:a:0?", "-sn", "-dn",
            "-vf", f"scale={out_w}:{out_h}:force_original_aspect_ratio=increase,"
            f"crop={out_w}:{out_h},fps={FPS},setsar=1",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p",
            "-af", "apad", "-c:a", "aac", "-b:a", "128k", "-shortest",
            "-t", f"{duration_ms / 1000:.3f}", "-movflags", "+faststart",
            str(normalized),
        ],
        "normalising the clip",
    )  # fmt: skip

    # `apad` lets the picture set the length (a short audio track is padded, a long one is cut by
    # `-shortest`); counted on the normalised file, since the source container can follow the audio.
    total = _count_frames(normalized, processes)
    if total > MAX_SEC * FPS:
        raise PermanentError(
            "duration_exceeded", f"the clip holds {total} frames at {FPS} fps, over {MAX_SEC:g} s"
        )
    if given is None:
        bounds = _detected_bounds(normalized, threshold, total, processes)
    elif given[-1] != total:
        raise PermanentError(
            "contract_violation",
            f"bounds end at frame {given[-1]} but the normalised clip has {total} frames",
        )
    else:
        bounds = given

    scene_count = len(bounds) - 1
    starts = [bounds[i] for i in range(scene_count)]
    picks = [middle_frame(bounds[i], bounds[i + 1]) for i in range(scene_count)]
    selection = "+".join(f"eq(n\\,{frame})" for frame in picks)
    _run_ffmpeg(
        processes,
        [
            "ffmpeg", *_QUIET, "-y",
            "-i", str(normalized),
            "-map", "0:v:0",
            "-vf", f"select='{selection}'",
            "-fps_mode", "passthrough", "-q:v", "2", "-start_number", "0",
            str(workdir / "frame_%03d.jpg"),
        ],
        "extracting the scene frames",
    )  # fmt: skip

    names = [f"frame_{i:03d}.jpg" for i in range(scene_count)]
    missing = [name for name in names if not (workdir / name).is_file()]
    if missing:
        raise tool_failure("ffmpeg", f"no frame was written for {missing[0]}")

    return HandlerOutcome(
        data={
            "fps": FPS,
            "width": out_w,
            "height": out_h,
            "durationSec": round(total / FPS, 2),
            "aspect": label,
            "normalized": NORMALIZED_NAME,
            "scenes": [
                {"index": i, "startFrame": starts[i], "endFrame": bounds[i + 1], "frame": names[i]}
                for i in range(scene_count)
            ],
        },
        files=[
            OutputFile(NORMALIZED_NAME, NORMALIZED_MIME, normalized),
            *(OutputFile(name, FRAME_MIME, workdir / name) for name in names),
        ],
    )
