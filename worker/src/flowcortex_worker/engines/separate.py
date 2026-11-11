"""demucs `htdemucs` vocal separation child: `python -m flowcortex_worker.engines.separate`.

Reads a 44.1 kHz stereo WAV window by window (the whole clip never sits in memory), and writes
`vocals.wav` and `no_vocals.wav` (the sum of the other sources) to the output directory.
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path
from typing import Any

from . import EXIT_UNLOADABLE

MODEL = "htdemucs"
RATE = 44100
CHANNELS = 2
# Floor on the whole-clip standard deviation: a constant signal would divide by zero.
MIN_STD = 1e-8


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="separate")
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args(argv)

    try:
        import numpy as np
        import soundfile
        import torch
        from demucs.apply import apply_model
        from demucs.pretrained import get_model

        torch.set_num_threads(len(os.sched_getaffinity(0)))
        model = get_model(MODEL)
        model.eval()
        vocals_index = model.sources.index("vocals")
    except Exception as failed:
        print(f"{MODEL} cannot be loaded: {failed!r}", file=sys.stderr)
        return EXIT_UNLOADABLE

    from ..audio.windows import MeanStd, blend_weights, window_plan

    with soundfile.SoundFile(args.input) as source:
        if source.samplerate != RATE or source.channels != CHANNELS:
            print(
                f"expected {RATE} Hz stereo, got {source.samplerate} Hz x {source.channels}",
                file=sys.stderr,
            )
            return 1
        plan = window_plan(source.frames, RATE)

        stats = MeanStd()
        for block in source.blocks(blocksize=RATE * 30, dtype="float32", always_2d=True):
            ref = block.mean(axis=1)
            stats.merge(
                len(ref), float(ref.mean()), float(((ref - ref.mean()) ** 2).sum())
            )
        mean, std = stats.mean, max(stats.std, MIN_STD)

        args.output_dir.mkdir(parents=True, exist_ok=True)
        sinks: dict[str, Any] = {
            name: soundfile.SoundFile(
                args.output_dir / f"{name}.wav",
                mode="w",
                samplerate=RATE,
                channels=CHANNELS,
                subtype="PCM_16",
            )
            for name in ("vocals", "no_vocals")
        }
        tails: dict[str, Any] = {}
        try:
            for window in plan:
                source.seek(window.start)
                audio = source.read(window.length, dtype="float32", always_2d=True)
                mix = torch.from_numpy(audio.T.copy())
                with torch.no_grad():
                    separated = apply_model(
                        model, ((mix - mean) / std)[None], device="cpu", progress=False
                    )[0]
                separated = separated * std + mean
                parts = {
                    "vocals": separated[vocals_index],
                    "no_vocals": separated.sum(dim=0) - separated[vocals_index],
                }
                for name, part in parts.items():
                    block = part.numpy().T
                    carried = tails.pop(name, None)
                    if carried is not None:
                        weights = np.asarray(
                            blend_weights(len(carried)), dtype="float32"
                        )[:, None]
                        block[: len(carried)] = (
                            carried * (1 - weights) + block[: len(carried)] * weights
                        )
                    keep = len(block) - window.shared
                    if window.shared:
                        tails[name] = block[keep:].copy()
                    sinks[name].write(np.clip(block[:keep], -1.0, 1.0))
        finally:
            for sink in sinks.values():
                sink.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
