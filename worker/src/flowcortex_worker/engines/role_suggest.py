"""RT-DETR person detector child: `python -m flowcortex_worker.engines.role_suggest`.

Writes `[{"width": w, "height": h, "boxes": [[x0, y0, x1, y1, score], ...]}, ...]`, one item per
frame path in the order given, with pixel boxes of the `person` label only. The model loads once
for all frames, from a local directory, never from the network.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path
from typing import Any

from . import EXIT_UNLOADABLE

MODEL_DIR = Path("/app/models/trend/rtdetr_r50vd")
THRESHOLD = 0.5
PERSON_LABEL = "person"


def person_ids(config: Any) -> set[int]:
    return {int(index) for index, name in config.id2label.items() if name == PERSON_LABEL}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="role_suggest")
    parser.add_argument("--model-dir", type=Path, default=MODEL_DIR)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("frames", nargs="+", type=Path)
    args = parser.parse_args(argv)

    started = time.perf_counter()
    try:
        import torch

        torch.set_num_threads(len(os.sched_getaffinity(0)))
        from PIL import Image
        from transformers import RTDetrForObjectDetection, RTDetrImageProcessor

        processor = RTDetrImageProcessor.from_pretrained(args.model_dir, local_files_only=True)
        model = RTDetrForObjectDetection.from_pretrained(args.model_dir, local_files_only=True)
        model.eval()
    except (ImportError, OSError) as failed:
        print(f"RT-DETR cannot be loaded: {failed!r}", file=sys.stderr)
        return EXIT_UNLOADABLE

    people = person_ids(model.config)
    if not people:
        print(f"RT-DETR in {args.model_dir} has no {PERSON_LABEL!r} label", file=sys.stderr)
        return EXIT_UNLOADABLE
    print(
        f"trend-engine: load={time.perf_counter() - started:.2f} threads={torch.get_num_threads()}",
        file=sys.stderr,
    )

    report: list[dict[str, Any]] = []
    for path in args.frames:
        with Image.open(path) as opened:
            image = opened.convert("RGB")
        width, height = image.size
        inputs = processor(images=image, return_tensors="pt")
        with torch.no_grad():
            outputs = model(**inputs)
        found = processor.post_process_object_detection(
            outputs, threshold=THRESHOLD, target_sizes=[[height, width]]
        )[0]
        boxes = [
            [*box, score]
            for score, label, box in zip(
                found["scores"].tolist(),
                found["labels"].tolist(),
                found["boxes"].tolist(),
                strict=True,
            )
            if int(label) in people
        ]
        report.append({"width": width, "height": height, "boxes": boxes})

    args.output.write_text(json.dumps(report), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
