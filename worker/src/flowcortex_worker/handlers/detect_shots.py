"""`detect_shots`: hard cuts of one video inside a source window, found with `scdet`."""

from __future__ import annotations

from collections.abc import Mapping
from pathlib import Path

from ..contracts.media_job import MediaJob
from ..errors import PermanentError
from ..runtime import ProcessRunner
from ..video_analysis import frame_metadata
from ..video_analysis.streams import finite_param, last_line, ms, probe_video, tool_failure
from . import HandlerOutcome

# scdet's threshold is a percentage of the maximum change.
THRESHOLD_RANGE = (0.0, 100.0)
SCENES_NAME = "scdet.txt"


def handle(job: MediaJob, inputs: Mapping[str, Path], processes: ProcessRunner) -> HandlerOutcome:
    if set(inputs) != {"video"}:
        raise PermanentError("contract_violation", "detect_shots needs exactly one input, 'video'")
    src_in = finite_param(job.params, "srcInMs")
    src_out = finite_param(job.params, "srcOutMs")
    if src_in < 0:
        raise PermanentError(
            "contract_violation", f"param 'srcInMs' must be 0 or more, got {src_in}"
        )
    if src_out <= src_in:
        raise PermanentError(
            "contract_violation", f"param 'srcOutMs' ({src_out}) must be above 'srcInMs' ({src_in})"
        )
    threshold = finite_param(job.params, "threshold")
    low, high = THRESHOLD_RANGE
    if not low <= threshold <= high:
        raise PermanentError(
            "contract_violation",
            f"param 'threshold' must be within {low:g}..{high:g}, got {threshold}",
        )
    start, end = int(src_in), int(src_out)

    video = inputs["video"]
    duration_ms, stream = probe_video(video, processes)
    if stream is None:
        raise tool_failure("ffprobe", "the input has no moving-picture stream")
    if start >= duration_ms:
        raise PermanentError(
            "contract_violation",
            f"param 'srcInMs' ({start}) must be before the end of the video "
            f"(durationMs {duration_ms})",
        )
    end = min(end, duration_ms)
    if end <= start:
        raise PermanentError(
            "contract_violation",
            f"params 'srcInMs' ({src_in}) and 'srcOutMs' ({src_out}) span less than 1 ms",
        )

    scores_file = video.parent / SCENES_NAME
    completed = processes.run(
        [
            "ffmpeg", "-nostdin", "-hide_banner", "-nostats", "-loglevel", "error",
            "-protocol_whitelist", "file",
            "-ss", f"{start / 1000:.3f}", "-t", f"{(end - start) / 1000:.3f}",
            "-i", str(video), "-map", f"0:{stream.index}", "-an", "-sn", "-dn",
            "-vf",
            f"scdet=threshold={threshold:g}:sc_pass=1,metadata=mode=print:file={scores_file}",
            "-f", "null", "-",
        ]
    )  # fmt: skip
    if completed.returncode != 0:
        raise tool_failure(
            "ffmpeg",
            f"could not scan the window (exit {completed.returncode}): "
            f"{last_line(completed.stderr)}",
        )
    # ffmpeg writes no file when no frame passes the threshold.
    text = scores_file.read_text(encoding="utf-8") if scores_file.is_file() else ""

    cuts: list[int] = []
    scores: list[float] = []
    for seconds, score in frame_metadata.scdet_scores(text):
        at = start + ms(seconds)
        if start < at < end and (not cuts or at > cuts[-1]):
            cuts.append(at)
            scores.append(round(score, 3))
    return HandlerOutcome(
        data={
            "sourceCutsMs": cuts,
            "detector": {
                "filter": "scdet",
                "threshold": float(threshold),
                "srcInMs": start,
                "srcOutMs": end,
                "scores": scores,
                "ffmpegVersion": _ffmpeg_version(processes),
            },
        },
        files=[],
    )


def _ffmpeg_version(processes: ProcessRunner) -> str | None:
    completed = processes.run(["ffmpeg", "-version"])
    if completed.returncode != 0:
        return None
    first = completed.stdout.decode("utf-8", "replace").splitlines()[:1]
    words = first[0].split() if first else []
    return words[2] if len(words) >= 3 else None
