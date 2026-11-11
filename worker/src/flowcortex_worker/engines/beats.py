"""beat_this `final0` beat and downbeat child: `python -m flowcortex_worker.engines.beats`.

Writes `{"beats": [s], "downbeats": [s], "rms": [...], "frameMs": n}` for a mono WAV.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from . import EXIT_UNLOADABLE

CHECKPOINT = Path("/app/models/beat_this/final0.ckpt")
RMS_FRAME = 2048
RMS_HOP = 512


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="beats")
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--checkpoint", type=Path, default=CHECKPOINT)
    args = parser.parse_args(argv)

    try:
        import librosa
        import soundfile
        from beat_this.inference import Audio2Beats

        if not args.checkpoint.is_file():
            raise FileNotFoundError(f"checkpoint {args.checkpoint} is missing")
        tracker = Audio2Beats(checkpoint_path=str(args.checkpoint), device="cpu", dbn=False)
    except Exception as failed:
        print(f"beat_this cannot be loaded: {failed!r}", file=sys.stderr)
        return EXIT_UNLOADABLE

    samples, rate = soundfile.read(str(args.input), dtype="float32", always_2d=False)
    beats, downbeats = tracker(samples, rate)
    rms = librosa.feature.rms(y=samples, frame_length=RMS_FRAME, hop_length=RMS_HOP)[0]
    args.output.write_text(
        json.dumps(
            {
                "beats": [float(t) for t in beats],
                "downbeats": [float(t) for t in downbeats],
                "rms": [float(v) for v in rms],
                "frameMs": RMS_HOP * 1000 / rate,
            }
        ),
        encoding="utf-8",
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
