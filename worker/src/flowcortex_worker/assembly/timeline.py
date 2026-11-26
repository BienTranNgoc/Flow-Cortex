"""Filter graphs and placement for the timeline and dub-segment tracks."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass


def timeline_graph(
    starts: Sequence[float], track_sec: float, speed_ups: Sequence[float] | None = None
) -> str:
    """Mono 48 kHz mix of input `i` delayed to `starts[i]` seconds, exactly `track_sec` long.

    A `speed_ups[i]` above 1 plays that clip faster at its pitch.
    """
    chains: list[str] = []
    labels = ""
    for index, start in enumerate(starts):
        delay_ms = max(0, round(float(start) * 1000))
        speed = float(speed_ups[index]) if speed_ups else 1.0
        tempo, restamp = "", ""
        if speed > 1:
            tempo = f"atempo={speed:.3f},"
            # ffmpeg 7.1: after atempo the silence adelay inserts carries no timestamps, and amix
            # then drops the delay and the padding.
            restamp = ",asetpts=N/SR/TB"
        chains.append(
            # Mono: upmixing a mono voice clip to stereo costs 3 dB.
            f"[{index}:a]aresample=48000,aformat=channel_layouts=mono,"
            f"{tempo}adelay=delays={delay_ms}:all=1{restamp}[a{index}]"
        )
        labels += f"[a{index}]"
    length = f"{float(track_sec):.3f}"
    chains.append(
        f"{labels}amix=inputs={len(starts)}:normalize=0:dropout_transition=0,"
        # amix takes its timestamps from the first input; when later frames arrive before it
        # reports that input ended they carry none, apad stamps the padding with garbage and
        # atrim drops it, shortening the track. The track is contiguous samples from 0, so the
        # sample count is its time.
        "asetpts=N/SR/TB,"
        f"apad=whole_dur={length},atrim=end={length}[out]"
    )
    return ";".join(chains)


def segment_graph(starts_ms: Sequence[int]) -> str:
    """Stereo 44.1 kHz mix of input `i` delayed to `starts_ms[i]`; the track ends with its last
    clip."""
    if not starts_ms:
        raise ValueError("a dub track needs at least one clip")
    chains = [
        f"[{index}:a]aresample=44100,aformat=channel_layouts=stereo,adelay={ms}|{ms}[a{index}]"
        for index, ms in enumerate(starts_ms)
    ]
    labels = "".join(f"[a{index}]" for index in range(len(starts_ms)))
    chains.append(f"{labels}amix=inputs={len(starts_ms)}:duration=longest:normalize=0[out]")
    return ";".join(chains)


@dataclass(frozen=True)
class Slot:
    """A manifest segment: its index and its slot in the track, in milliseconds."""

    index: int
    start_ms: int
    end_ms: int


@dataclass(frozen=True)
class Placement:
    order: list[Slot]
    track_seconds: float
    adjustments: list[dict[str, int | float]]


def place_segments(slots: Sequence[Slot], clip_seconds: Mapping[int, float]) -> Placement:
    """Places the slots that have a clip by start time; the track ends at the latest
    `start + clip length`, so a last line longer than its slot is never cut.

    `adjustments` follows the listed order: slot length, clip length and their positive
    difference, rounded to 0.01 s.
    """
    placed = [slot for slot in slots if slot.index in clip_seconds]
    adjustments: list[dict[str, int | float]] = []
    track_seconds = 0.0
    for slot in placed:
        start = slot.start_ms / 1000
        target = (slot.end_ms - slot.start_ms) / 1000
        actual = clip_seconds[slot.index]
        track_seconds = max(track_seconds, start + actual)
        adjustments.append(
            {
                "segIdx": slot.index,
                "startSec": round(start, 2),
                "targetSec": round(target, 2),
                "actualSec": round(actual, 2),
                "overlap": round(max(0.0, actual - target), 2),
            }
        )
    return Placement(sorted(placed, key=lambda slot: slot.start_ms), track_seconds, adjustments)
