"""`segment_dub`: places one voiced clip per manifest segment on a stereo MP3 track."""

from __future__ import annotations

import json
import re
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any, TypeGuard

from ..assembly.drops import MAX_DROP_RATIO, DropReason, drop_report
from ..assembly.inputs import is_run_bound
from ..assembly.timeline import Slot, place_segments, segment_graph
from ..contracts.media_job import MediaJob
from ..errors import PermanentError
from ..runtime import ProcessRunner
from . import HandlerOutcome, OutputFile
from ._assembly_io import (
    ceil_ms,
    duration_seconds,
    has_stream,
    input_args,
    mask_dir,
    run_ffmpeg,
)
from ._audio_input import require_within

MANIFEST_KEY = "manifest"
CLIP_PREFIX = "segment_"
MAX_SEGMENTS = 500
# A voice manifest carries the text of every line; this bound stops a hostile input from being
# parsed whole, not a real one.
MAX_MANIFEST_BYTES = 8 * 1024 * 1024
LIMIT_SECONDS = 1800
BITRATE = "128k"
OUTPUT_NAME = "dub.mp3"
OUTPUT_MIME = "audio/mpeg"

_CLIP_KEY = re.compile(rf"{CLIP_PREFIX}(0|[1-9][0-9]*)")


def handle(
    _job: MediaJob,
    inputs: Mapping[str, Path],
    processes: ProcessRunner,
) -> HandlerOutcome:
    slots = _read_manifest(inputs)
    clips = _clips(inputs, {slot.index for slot in slots})

    clip_seconds: dict[int, float] = {}
    dropped: list[tuple[int, DropReason]] = []
    for slot in slots:
        path = clips.get(slot.index)
        if path is None:
            dropped.append((slot.index, "missing"))
            continue
        seconds = _clip_seconds(path, processes)
        if seconds is None:
            dropped.append((slot.index, "unreadable"))
            continue
        clip_seconds[slot.index] = seconds

    report = drop_report(len(slots), dropped)
    if not report.ok:
        raise PermanentError(
            "too_many_segments_dropped",
            f"{report.count} of {report.total} segments were dropped, "
            f"above the limit of {MAX_DROP_RATIO:.0%}",
        )

    placement = place_segments(slots, clip_seconds)
    duration_ms = ceil_ms(placement.track_seconds)
    require_within(duration_ms, LIMIT_SECONDS)

    sources = [clips[slot.index] for slot in placement.order]
    scratch = processes.scratch_dir()
    target = scratch / OUTPUT_NAME
    run_ffmpeg(
        _mix_argv(sources, [slot.start_ms for slot in placement.order], target),
        processes,
        "audio_mix_failed",
        "the mix",
        mask_dir([inputs[MANIFEST_KEY], *sources], scratch),
        output=target,
    )
    return HandlerOutcome(
        data={
            "segmentCount": len(sources),
            "durationMs": duration_ms,
            "speedAdjustments": placement.adjustments,
            "droppedCount": report.count,
            "droppedSegments": report.dropped,
        },
        files=[OutputFile(OUTPUT_NAME, OUTPUT_MIME, target)],
    )


def _read_manifest(inputs: Mapping[str, Path]) -> list[Slot]:
    path = inputs.get(MANIFEST_KEY)
    if path is None:
        raise PermanentError("contract_violation", f"the job needs an input '{MANIFEST_KEY}'")
    if path.stat().st_size > MAX_MANIFEST_BYTES:
        raise PermanentError(
            "contract_violation", f"the manifest is larger than {MAX_MANIFEST_BYTES} bytes"
        )
    try:
        document = json.loads(path.read_bytes())
    except (ValueError, RecursionError) as failed:
        raise PermanentError("contract_violation", "the manifest is not valid JSON") from failed
    segments = document.get("segments") if isinstance(document, dict) else None
    if not isinstance(segments, list) or not segments:
        raise PermanentError("contract_violation", "the manifest needs a non-empty list 'segments'")
    if len(segments) > MAX_SEGMENTS:
        raise PermanentError(
            "contract_violation",
            f"the manifest lists {len(segments)} segments, above the limit of {MAX_SEGMENTS}",
        )
    slots: list[Slot] = []
    seen: set[int] = set()
    for position, segment in enumerate(segments):
        slot = _slot(segment, position)
        if slot.index in seen:
            raise PermanentError(
                "contract_violation", f"the manifest lists segment index {slot.index} twice"
            )
        seen.add(slot.index)
        slots.append(slot)
    return slots


def _is_int(value: Any) -> TypeGuard[int]:
    return isinstance(value, int) and not isinstance(value, bool)


def _slot(segment: Any, position: int) -> Slot:
    if not isinstance(segment, dict):
        raise PermanentError("contract_violation", f"segments[{position}] must be an object")
    index = segment.get("index")
    if not _is_int(index) or index < 0:
        raise PermanentError(
            "contract_violation",
            f"segments[{position}]: 'index' must be a non-negative integer (got {index!r})",
        )
    start, end = segment.get("startMs"), segment.get("endMs")
    if not (_is_int(start) and _is_int(end) and 0 <= start < end):
        raise PermanentError(
            "contract_violation",
            f"segment {index}: 'startMs' and 'endMs' must be integers with 0 <= startMs < endMs "
            f"(got {start!r}, {end!r})",
        )
    return Slot(index, start, end)


def _clips(inputs: Mapping[str, Path], listed: set[int]) -> dict[int, Path]:
    """The clip of each listed segment; any key beyond the manifest, those clips and the
    run-bound keys is refused by name."""
    clips: dict[int, Path] = {}
    for key in sorted(inputs):
        if key == MANIFEST_KEY or is_run_bound(key):
            continue
        match = _CLIP_KEY.fullmatch(key)
        if match is None or int(match.group(1)) not in listed:
            raise PermanentError("contract_violation", f"unexpected input key '{key}'")
        clips[int(match.group(1))] = inputs[key]
    return clips


def _clip_seconds(path: Path, processes: ProcessRunner) -> float | None:
    """The clip's length, or None when it has no decodable audio (the segment is dropped). A
    probe ended by a signal the runner did not send is transient and not a drop."""
    try:
        if not has_stream(path, "audio", processes, "audio_extraction_failed"):
            return None
        seconds = duration_seconds(path, processes, "audio_extraction_failed")
    except PermanentError:
        return None
    return seconds if seconds > 0 else None


def _mix_argv(sources: Sequence[Path], starts_ms: Sequence[int], target: Path) -> list[str]:
    argv = ["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y"]
    for source in sources:
        argv += input_args(source)
    argv += [
        "-filter_complex", segment_graph(starts_ms),
        "-map", "[out]", "-vn",
        "-c:a", "libmp3lame", "-b:a", BITRATE,
        str(target),
    ]  # fmt: skip
    return argv
