"""Joins one or two shots, trims them exactly and moves their colour onto the neighbouring shots.

Two ffmpeg runs: a measuring pass (`signalstats` over the joined, padded, trimmed stream, no
encode) and one encode whose graph splits into the graded and the ungraded file. A single graph
cannot both measure and apply the offset.
"""

from __future__ import annotations

from collections.abc import Mapping
from pathlib import Path
from typing import Any

from ..contracts.media_job import MediaJob
from ..errors import PermanentError
from ..runtime import ProcessRunner
from ..video_analysis.streams import finite_param, last_line, tool_failure
from ..video_ops import grade
from ..video_ops.media import Clip, describe_output, probe_clip
from . import HandlerOutcome, OutputFile

GRADED_NAME = "graded.mp4"
UNGRADED_NAME = "ungraded.mp4"
OUTPUT_MIME = "video/mp4"
SHOT_KEYS = ("shot_1", "shot_2")
_REPORTED = ("width", "height", "durationMs")
_FFMPEG = ["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y"]


def _trim_ms(params: Mapping[str, Any]) -> int:
    trim = finite_param(params, "trimMs")
    if trim <= 0 or not trim.is_integer():
        raise PermanentError(
            "contract_violation",
            f"param 'trimMs' must be a whole number of milliseconds above 0 (got {trim:g})",
        )
    return int(trim)


def _stats_file(work: Path, channel: str) -> Path:
    return work / f"stats_{channel}.txt"


def _measure(
    join: grade.Join, work: Path, trim_s: float, fps: float, processes: ProcessRunner
) -> tuple[grade.Offsets, grade.Offsets]:
    """The mean of each channel over the opening and the ending of the joined stream."""
    parts = list(join.parts)
    if join.any_audio:
        parts.append("[a]anullsink")
    # Each metadata filter truncates its file on open, so every channel gets its own.
    printers = ",".join(
        f"metadata=print:key=lavfi.signalstats.{ch}AVG:file={_stats_file(work, ch)}"
        for ch in grade.CHANNELS
    )
    parts.append(f"[v]signalstats,{printers}[vm]")
    done = processes.run(
        [
            *_FFMPEG, *join.inputs, "-filter_complex", ";".join(parts),
            "-map", "[vm]", "-t", f"{trim_s:.3f}", "-f", "null", "-",
        ]
    )  # fmt: skip
    if done.returncode != 0:
        raise tool_failure(
            "ffmpeg",
            f"could not measure the shots (exit {done.returncode}): {last_line(done.stderr)}",
        )
    series: dict[str, list[float]] = {}
    for ch in grade.CHANNELS:
        try:
            text = _stats_file(work, ch).read_text(encoding="utf-8", errors="replace")
        except OSError:
            text = ""
        series[ch] = grade.parse_signal_means(text, ch)
    return grade.edge_means(series, fps)


def _encode_argv(
    join: grade.Join, graded: Path, ungraded: Path, d_in: grade.Offsets, d_out: grade.Offsets,
    trim_s: float,
) -> list[str]:  # fmt: skip
    ramp = grade.grade_filter(d_in, d_out, trim_s) or "null"
    parts = [*join.parts, "[v]split=2[vp][vg]", f"[vg]{ramp}[vgo]"]
    if join.any_audio:
        parts.append("[a]asplit=2[ap][ag]")
    video = [
        "-c:v", "libx264", "-preset", "veryfast", "-crf", "17", "-pix_fmt", "yuv420p",
        "-colorspace", "bt709", "-color_range", "tv", "-movflags", "+faststart",
        "-t", f"{trim_s:.3f}",
    ]  # fmt: skip
    audio = ["-c:a", "aac", "-b:a", "128k"] if join.any_audio else []

    def output(video_label: str, audio_label: str, path: Path) -> list[str]:
        maps = ["-map", video_label, *(["-map", audio_label] if join.any_audio else [])]
        return [*maps, *video, *audio, str(path)]

    return [
        *_FFMPEG, *join.inputs, "-filter_complex", ";".join(parts),
        *output("[vgo]", "[ag]", graded), *output("[vp]", "[ap]", ungraded),
    ]  # fmt: skip


def _triple(offsets: grade.Offsets) -> dict[str, float]:
    return {"dy": offsets["Y"], "du": offsets["U"], "dv": offsets["V"]}


def handle(
    job: MediaJob,
    inputs: Mapping[str, Path],
    processes: ProcessRunner,
) -> HandlerOutcome:
    if "shot_1" not in inputs or not set(inputs) <= set(SHOT_KEYS):
        raise PermanentError(
            "contract_violation",
            f"grade_match needs the input 'shot_1' and optionally 'shot_2' (got {sorted(inputs)})",
        )
    trim_ms = _trim_ms(job.params)
    target_in, target_out = grade.read_targets(job.params)

    shots: list[tuple[Path, Clip]] = [
        (inputs[key], probe_clip(inputs[key], processes)) for key in SHOT_KEYS if key in inputs
    ]
    pad_ms = grade.shortfall(sum(clip.duration_ms for _, clip in shots), trim_ms)
    trim_s, pad_s = trim_ms / 1000, pad_ms / 1000
    join = grade.join_graph(shots, trim_s, pad_s)
    work = shots[0][0].parent

    d_in = {ch: 0.0 for ch in grade.CHANNELS}
    d_out = dict(d_in)
    if grade.has_target(target_in, target_out):
        opening, ending = _measure(join, work, trim_s, shots[0][1].video.fps, processes)
        d_in, d_out = grade.offsets(target_in, target_out, opening, ending)

    graded, ungraded = work / GRADED_NAME, work / UNGRADED_NAME
    encoded = processes.run(_encode_argv(join, graded, ungraded, d_in, d_out, trim_s))
    if encoded.returncode != 0:
        raise tool_failure(
            "ffmpeg",
            f"could not grade the shots (exit {encoded.returncode}): {last_line(encoded.stderr)}",
        )

    described = describe_output(graded, processes)
    plain = describe_output(ungraded, processes)
    data = {key: described[key] for key in _REPORTED}
    return HandlerOutcome(
        data={
            **data,
            "padMs": pad_ms,
            "grade": {"in": _triple(d_in), "out": _triple(d_out)},
            "ungraded": {key: plain[key] for key in _REPORTED},
        },
        files=[
            OutputFile(GRADED_NAME, OUTPUT_MIME, graded),
            OutputFile(UNGRADED_NAME, OUTPUT_MIME, ungraded),
        ],
    )
