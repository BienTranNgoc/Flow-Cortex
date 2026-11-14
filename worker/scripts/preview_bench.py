#!/usr/bin/env python3
"""Render-cost measurement of the preview_video handler, run inside the preview-runtime image.

Renders every shot of every storyboard document in a fixtures directory at 9:16, as the preview
and as a job with send: true (the preview render plus the send render), through the real handler
and ProcessRunner. Prints per-run seconds, then P50 and P95 per shot and per frame for each
of the two, with the CPU model, the Blender version and the GL renderer. Exits 1, printing no
figures, unless every run reports a llvmpipe renderer: a GPU render would understate the cost of
production, which has no GPU.

The container runs under `--cpus=4` and no /dev/dri. A shot renders in a fresh scratch
directory, and the wall time of the handler call is what the job watchdog would count.

Usage: preview_bench.py <fixtures directory>
"""

import json
import math
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path
from types import SimpleNamespace
from typing import Any, cast

from flowcortex_worker.contracts.media_job import MediaJob
from flowcortex_worker.handlers import preview_video
from flowcortex_worker.runtime import ProcessRunner

ASPECT = "9:16"
VERSIONS = (("preview", False), ("send job", True))


@dataclass
class Run:
    version: str
    document: str
    shot: str
    frames: int
    seconds: float
    render_seconds: float
    renderer: str
    blender: str


def percentile(values: list[float], fraction: float) -> float:
    """Nearest rank: the smallest value with at least `fraction` of the runs at or below it."""
    ordered = sorted(values)
    return ordered[max(math.ceil(fraction * len(ordered)), 1) - 1]


def storyboards(folder: Path) -> list[tuple[str, dict[str, Any]]]:
    found = []
    for path in sorted(folder.glob("*.json")):
        doc = json.loads(path.read_text(encoding="utf-8"))
        if isinstance(doc, dict) and isinstance(doc.get("story"), dict):
            found.append((path.name, doc))
    return found


def cpu_model() -> str:
    listing = subprocess.run(["lscpu"], capture_output=True, text=True, check=True).stdout
    for line in listing.splitlines():
        if line.startswith("Model name:"):
            return line.split(":", 1)[1].strip()
    raise RuntimeError("lscpu lists no model name")


def render(name: str, doc: dict[str, Any], shot: str, version: str, send: bool) -> Run:
    with tempfile.TemporaryDirectory(prefix="preview-bench-") as scratch:
        document = Path(scratch) / "doc.json"
        document.write_text(json.dumps(doc), encoding="utf-8")
        params = {"shotId": shot, "aspect": ASPECT, "send": send}
        job = cast(MediaJob, SimpleNamespace(params=params))
        started = time.monotonic()
        outcome = preview_video.handle(job, {"doc": document}, ProcessRunner())
        seconds = time.monotonic() - started
    data = outcome.data
    return Run(
        version=version,
        document=name,
        shot=shot,
        frames=data["frames"],
        seconds=seconds,
        render_seconds=data["renderSec"],
        renderer=data["renderer"],
        blender=data["blender"],
    )


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: preview_bench.py <fixtures directory>", file=sys.stderr)
        return 2
    runs: list[Run] = []
    for name, doc in storyboards(Path(argv[1])):
        for shot in doc["story"]["shots"]:
            for version, send in VERSIONS:
                run = render(name, doc, shot["id"], version, send)
                runs.append(run)
                print(
                    f"{version:8} {name} {shot['id']}: {run.frames} frames, {run.seconds:.2f} s "
                    f"({run.seconds / run.frames:.3f} s/frame, blender {run.render_seconds:.2f} s)",
                    flush=True,
                )
    if not runs:
        print("preview bench: no storyboard document in the fixtures directory", file=sys.stderr)
        return 1
    off_cpu = sorted({run.renderer for run in runs if "llvmpipe" not in run.renderer})
    if off_cpu:
        print(f"preview bench: not rendered on llvmpipe: {off_cpu}", file=sys.stderr)
        return 1

    print(f"\nCPU: {cpu_model()}")
    print(f"Blender: {runs[0].blender}")
    print(f"Renderer: {runs[0].renderer}")
    print(f"Aspect: {ASPECT}, shots per version: {len(runs) // len(VERSIONS)}")
    for version, _ in VERSIONS:
        mine = [run for run in runs if run.version == version]
        per_shot = [run.seconds for run in mine]
        per_frame = [run.seconds / run.frames for run in mine]
        print(
            f"{version:8} per shot P50 {percentile(per_shot, 0.5):.2f} s "
            f"P95 {percentile(per_shot, 0.95):.2f} s | "
            f"per frame P50 {percentile(per_frame, 0.5):.3f} s "
            f"P95 {percentile(per_frame, 0.95):.3f} s"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
