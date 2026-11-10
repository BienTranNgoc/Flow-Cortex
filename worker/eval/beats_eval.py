"""Offline accuracy check of the `beats` engine on local, already downloaded datasets.

Run inside the audio image, where the engine and its checkpoint live. It downloads nothing.

  --gtzan DIR / --ballroom DIR   audio folders; the beat_this annotations (`<stem>.beats`, one
                                 `seconds [position]` line per beat, position 1 = downbeat) are
                                 looked up by file stem under --annotations.
  --soundcloud DIR               audio folder with a `<stem>.drops.json` beside each file holding
                                 the labelled drop times in seconds (a list, or {"drops": [...]}).

Prints the mean F-measure at 70 ms for beats and downbeats per dataset, and drop precision,
recall and F1 for each ratio of the sweep, naming the best ratio.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import tempfile
from collections.abc import Iterator, Sequence
from pathlib import Path
from typing import Any
from unittest import mock

from flowcortex_worker.audio import beat_grid
from flowcortex_worker.engines.beats import CHECKPOINT

AUDIO_SUFFIXES = {".wav", ".mp3", ".flac", ".m4a", ".ogg", ".au"}
RATE = 22050
BEAT_TOLERANCE_MS = 70
# A predicted drop counts when it lies within this many ms of a labelled one.
DROP_TOLERANCE_MS = 2000
DEFAULT_RATIOS = (1.5, 1.75, 2.0, 2.25, 2.5, 3.0)


def f_measure(truth_ms: Sequence[int], predicted_ms: Sequence[int], tolerance_ms: int) -> float:
    """F1 of a one-to-one greedy matching of sorted marks within the tolerance."""
    if not truth_ms and not predicted_ms:
        return 1.0
    if not truth_ms or not predicted_ms:
        return 0.0
    truth, predicted = sorted(truth_ms), sorted(predicted_ms)
    hits = 0
    i = j = 0
    while i < len(truth) and j < len(predicted):
        gap = predicted[j] - truth[i]
        if abs(gap) <= tolerance_ms:
            hits += 1
            i += 1
            j += 1
        elif gap < 0:
            j += 1
        else:
            i += 1
    precision, recall = hits / len(predicted), hits / len(truth)
    return 0.0 if hits == 0 else 2 * precision * recall / (precision + recall)


def drop_scores(truth_ms: Sequence[int], predicted_ms: Sequence[int]) -> tuple[int, int, int]:
    """(true positives, predicted, labelled) of drops, each labelled drop matched at most once."""
    unmatched = list(truth_ms)
    hits = 0
    for at in predicted_ms:
        near = [t for t in unmatched if abs(t - at) <= DROP_TOLERANCE_MS]
        if near:
            unmatched.remove(min(near, key=lambda t: abs(t - at)))
            hits += 1
    return hits, len(predicted_ms), len(truth_ms)


def read_beats(path: Path) -> tuple[list[int], list[int]]:
    """Beat and downbeat marks in whole ms from a `.beats` annotation file."""
    beats: list[float] = []
    downbeats: list[float] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        fields = line.split()
        if not fields:
            continue
        at = float(fields[0])
        beats.append(at)
        if len(fields) > 1 and fields[1].split(".")[0] == "1":
            downbeats.append(at)
    duration_ms = int(max(beats, default=0) * 1000) + 1
    return beat_grid.marks_ms(beats, duration_ms), beat_grid.marks_ms(downbeats, duration_ms)


def audio_files(folder: Path, limit: int | None) -> Iterator[Path]:
    found = sorted(p for p in folder.rglob("*") if p.suffix.lower() in AUDIO_SUFFIXES)
    yield from found[:limit] if limit else found


def run_engine(audio: Path, work: Path, checkpoint: Path) -> tuple[dict[str, Any], int]:
    """Decodes `audio` and runs the engine child; returns its raw JSON and the duration in ms."""
    wav, raw = work / "input.wav", work / "raw.json"
    subprocess.run(
        ["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-i", str(audio),
         "-vn", "-ac", "1", "-ar", str(RATE), "-c:a", "pcm_s16le", str(wav)],
        check=True,
    )  # fmt: skip
    subprocess.run(
        [sys.executable, "-m", "flowcortex_worker.engines.beats", "--input", str(wav),
         "--output", str(raw), "--checkpoint", str(checkpoint)],
        check=True,
    )  # fmt: skip
    import soundfile

    info = soundfile.info(str(wav))
    return json.loads(raw.read_text(encoding="utf-8")), int(info.frames * 1000 / info.samplerate)


def shaped(raw: dict[str, Any], duration_ms: int) -> dict[str, Any] | None:
    beats, downbeats, rms = (list(raw[k]) for k in ("beats", "downbeats", "rms"))
    try:
        return beat_grid.shape_result(
            beats,
            downbeats,
            rms,
            float(raw["frameMs"]),
            duration_ms,
        )
    except beat_grid.NoBeats:
        return None


def evaluate_beats(
    name: str, folder: Path, annotations: Path, limit: int | None, checkpoint: Path
) -> None:
    beat_f: list[float] = []
    down_f: list[float] = []
    for audio in audio_files(folder, limit):
        notes = next(annotations.rglob(f"{audio.stem}.beats"), None)
        if notes is None:
            continue
        truth_beats, truth_downbeats = read_beats(notes)
        with tempfile.TemporaryDirectory() as tmp:
            raw, duration_ms = run_engine(audio, Path(tmp), checkpoint)
        result = shaped(raw, duration_ms)
        beats = result["beats"] if result else []
        downbeats = result["downbeats"] if result else []
        beat_f.append(f_measure(truth_beats, beats, BEAT_TOLERANCE_MS))
        down_f.append(f_measure(truth_downbeats, downbeats, BEAT_TOLERANCE_MS))
    if not beat_f:
        print(f"{name}: no annotated audio found")
        return
    print(
        f"{name}: files={len(beat_f)} beat F@{BEAT_TOLERANCE_MS}ms={sum(beat_f) / len(beat_f):.3f}"
        f" downbeat F@{BEAT_TOLERANCE_MS}ms={sum(down_f) / len(down_f):.3f}"
    )


def evaluate_drops(
    folder: Path, ratios: Sequence[float], limit: int | None, checkpoint: Path
) -> None:
    labelled: list[tuple[dict[str, Any], int, list[int]]] = []
    for audio in audio_files(folder, limit):
        notes = audio.with_suffix(".drops.json")
        if not notes.is_file():
            continue
        loaded = json.loads(notes.read_text(encoding="utf-8"))
        seconds = loaded["drops"] if isinstance(loaded, dict) else loaded
        with tempfile.TemporaryDirectory() as tmp:
            raw, duration_ms = run_engine(audio, Path(tmp), checkpoint)
        labelled.append((raw, duration_ms, beat_grid.marks_ms(seconds, 10**9)))
    if not labelled:
        print("soundcloud: no labelled audio found")
        return
    best: tuple[float, float] | None = None
    for ratio in ratios:
        hits = predicted = truth = 0
        with mock.patch.object(beat_grid, "DROP_RATIO", ratio):
            for raw, duration_ms, drops_ms in labelled:
                result = shaped(raw, duration_ms)
                found = [int(d["atMs"]) for d in result["drops"]] if result else []
                h, p, t = drop_scores(drops_ms, found)
                hits, predicted, truth = hits + h, predicted + p, truth + t
        precision = hits / predicted if predicted else 0.0
        recall = hits / truth if truth else 0.0
        f1 = 2 * precision * recall / (precision + recall) if hits else 0.0
        print(f"ratio={ratio:.2f} precision={precision:.3f} recall={recall:.3f} f1={f1:.3f}")
        if best is None or f1 > best[1]:
            best = (ratio, f1)
    if best:
        print(f"best ratio={best[0]:.2f} f1={best[1]:.3f}")


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--annotations", type=Path, help="folder holding the .beats files")
    parser.add_argument("--gtzan", type=Path)
    parser.add_argument("--ballroom", type=Path)
    parser.add_argument("--soundcloud", type=Path)
    parser.add_argument("--limit", type=int, help="most files per dataset")
    parser.add_argument("--checkpoint", type=Path, default=CHECKPOINT)
    parser.add_argument(
        "--ratios", type=float, nargs="+", default=list(DEFAULT_RATIOS), help="drop ratio sweep"
    )
    args = parser.parse_args(argv)

    if (args.gtzan or args.ballroom) and not args.annotations:
        parser.error("--gtzan and --ballroom need --annotations")
    for name, folder in (("gtzan", args.gtzan), ("ballroom", args.ballroom)):
        if folder:
            evaluate_beats(name, folder, args.annotations, args.limit, args.checkpoint)
    if args.soundcloud:
        evaluate_drops(args.soundcloud, args.ratios, args.limit, args.checkpoint)
    return 0


if __name__ == "__main__":
    sys.exit(main())
