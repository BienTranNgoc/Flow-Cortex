"""Speaker child: `python -m flowcortex_worker.engines.speakers --input a.wav --output t.json`.

silero VAD finds speech, CAM++ embeds 1.5 s chunks every 0.75 s through sherpa-onnx, the chunks are
clustered (`speaker_clustering`), and raw `[start_s, end_s, label]` segments are written as JSON.
Exits 3 when sherpa-onnx or a model cannot be loaded.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from . import EXIT_UNLOADABLE

MODEL_DIR = Path("/app/models/speakers")
VAD_MODEL = "silero_vad.onnx"
EMBEDDING_MODEL = "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"
SAMPLE_RATE = 16000
CHUNK_SECONDS = 1.5
CHUNK_STEP_SECONDS = 0.75
EMBEDDING_THREADS = 2
VAD_THREADS = 1
# Headroom on the VAD ring buffer beyond the clip length.
VAD_BUFFER_EXTRA_SECONDS = 30


def chunk(
    start: float, end: float, duration: float = CHUNK_SECONDS, step: float = CHUNK_STEP_SECONDS
) -> list[tuple[float, float]]:
    out: list[tuple[float, float]] = []
    cursor = start
    while cursor + duration < end + step:
        out.append((cursor, min(cursor + duration, end)))
        cursor += step
    return out


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="speakers")
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--models-dir", type=Path, default=MODEL_DIR)
    args = parser.parse_args(argv)

    try:
        import numpy as np
        import sherpa_onnx
        import soundfile

        from .speaker_clustering import cluster, compressed_seg

        audio, rate = soundfile.read(args.input, dtype="float32", always_2d=True)
        if rate != SAMPLE_RATE:
            print(f"expected {SAMPLE_RATE} Hz, got {rate} Hz", file=sys.stderr)
            return 1
        samples = np.ascontiguousarray(audio[:, 0])

        vad_config = sherpa_onnx.VadModelConfig()
        vad_config.silero_vad.model = str(args.models_dir / VAD_MODEL)
        vad_config.sample_rate = SAMPLE_RATE
        vad_config.num_threads = VAD_THREADS
        extractor = sherpa_onnx.SpeakerEmbeddingExtractor(
            sherpa_onnx.SpeakerEmbeddingExtractorConfig(
                model=str(args.models_dir / EMBEDDING_MODEL), num_threads=EMBEDDING_THREADS
            )
        )
        vad = sherpa_onnx.VoiceActivityDetector(
            vad_config,
            buffer_size_in_seconds=len(samples) / SAMPLE_RATE + VAD_BUFFER_EXTRA_SECONDS,
        )
        window = vad_config.silero_vad.window_size
    except Exception as failed:
        print(f"the speaker engine cannot be loaded: {failed!r}", file=sys.stderr)
        return EXIT_UNLOADABLE

    spans: list[tuple[float, float]] = []

    def drain() -> None:
        while not vad.empty():
            front: Any = vad.front
            spans.append(
                (front.start / SAMPLE_RATE, (front.start + len(front.samples)) / SAMPLE_RATE)
            )
            vad.pop()

    for offset in range(0, len(samples), window):
        vad.accept_waveform(samples[offset : offset + window])
        drain()
    vad.flush()
    drain()

    chunks = [piece for start, end in spans for piece in chunk(start, end)]
    full = int(CHUNK_SECONDS * SAMPLE_RATE)
    embeddings = []
    for start, end in chunks:
        piece = samples[int(start * SAMPLE_RATE) : int(end * SAMPLE_RATE)]
        if len(piece) < full:
            piece = np.tile(piece, int(np.ceil(full / max(1, len(piece)))))[:full]
        stream = extractor.create_stream()
        stream.accept_waveform(SAMPLE_RATE, piece)
        stream.input_finished()
        embeddings.append(np.array(extractor.compute(stream), dtype=np.float32))

    segments: list[list[float | int]] = []
    if embeddings:
        labels = cluster(np.stack(embeddings))
        merged = compressed_seg(
            [[start, end, int(label)] for (start, end), label in zip(chunks, labels, strict=True)]
        )
        segments = [[float(s), float(e), int(c)] for s, e, c in merged if e > s]
    args.output.write_text(json.dumps(segments), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
