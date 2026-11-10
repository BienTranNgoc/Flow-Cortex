"""CPU wall time and peak memory of the overlay child on generated captioned images.

Run inside the overlay image: python /bench/bench_clean_overlay.py [--sizes 1024x1024 ...]
Every run is its own child process. A size within the pixel cap (overlay.protocol.MAX_PIXELS)
is over budget when a run exceeds the wall-time limit, is killed by a signal (the OOM killer),
exits non-zero, or peaks above the memory limit. A size above the cap must be refused by the
child with exit code 3 (input_rejected): that is reported as expected and never fails the run,
while any other outcome for it does. Exit code 0 when every size is within budget, 1 otherwise.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import resource
import statistics
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass, field
from pathlib import Path

import cv2
import numpy as np
from numpy.typing import NDArray

from flowcortex_worker.overlay.protocol import INPUT_REJECTED, MAX_PIXELS

SEED = 20260101
WALL_LIMIT_SECONDS = 900.0
RSS_LIMIT_KIB = 6 * 1024 * 1024
# (width, height, warm-up runs, timed runs). Small sizes are timed 5 times; the large ones twice
# and the maximum stands in for P95.
DEFAULT_PLAN = (
    (1024, 1024, 1, 5),
    (2048, 2048, 1, 5),
    (3840, 2160, 0, 2),
    (5000, 5000, 0, 2),
)
PARAMS = {
    "region": "auto",
    "boxes": [],
    "strength": 3,
    "dilatePx": 3,
    "extractText": True,
}


@dataclass
class SizeResult:
    width: int
    height: int
    seconds: list[float] = field(default_factory=list)
    peak_kib: int = 0
    failure: str | None = None

    @property
    def over_cap(self) -> bool:
        return self.width * self.height > MAX_PIXELS

    @property
    def over_budget(self) -> bool:
        if self.over_cap:
            return self.failure is not None
        return (
            self.failure is not None
            or self.peak_kib > RSS_LIMIT_KIB
            or (bool(self.seconds) and p95(self.seconds) > WALL_LIMIT_SECONDS)
        )


def p95(values: list[float]) -> float:
    """Nearest-rank 95th percentile; with two runs it is the larger one."""
    ordered = sorted(values)
    return ordered[max(math.ceil(0.95 * len(ordered)) - 1, 0)]


def caption_image(width: int, height: int) -> NDArray[np.uint8]:
    """Noise background with two black-on-white captions scaled to the frame."""
    rng = np.random.default_rng(SEED)
    low = rng.integers(60, 200, size=(height // 16 + 1, width // 16 + 1, 3)).astype(np.uint8)
    image = np.asarray(cv2.resize(low, (width, height), interpolation=cv2.INTER_CUBIC), np.uint8)
    scale = max(width, height) / 1024
    for text, row in (("SALE 50% OFF TODAY", 0.25), ("FREE SHIPPING OVER 99", 0.7)):
        origin = (int(width * 0.08), int(height * row))
        thickness = max(int(3 * scale), 2)
        cv2.putText(
            image, text, origin, cv2.FONT_HERSHEY_SIMPLEX, 1.6 * scale, (255, 255, 255),
            thickness + max(int(4 * scale), 2), cv2.LINE_AA,
        )  # fmt: skip
        cv2.putText(
            image, text, origin, cv2.FONT_HERSHEY_SIMPLEX, 1.6 * scale, (0, 0, 0),
            thickness, cv2.LINE_AA,
        )  # fmt: skip
    return image


def run_child(image: Path, params: Path, out: Path) -> tuple[float, int | None, str | None]:
    """Seconds, the child's exit code (None on timeout) and why the run failed, if it did."""
    started = time.monotonic()
    try:
        done = subprocess.run(
            [sys.executable, "-m", "flowcortex_worker.overlay", str(image), str(params), str(out)],
            capture_output=True,
            text=True,
            timeout=WALL_LIMIT_SECONDS,
            check=False,
        )
    except subprocess.TimeoutExpired:
        return time.monotonic() - started, None, f"over {WALL_LIMIT_SECONDS:.0f} s"
    elapsed = time.monotonic() - started
    if done.returncode < 0:
        return elapsed, done.returncode, f"killed by signal {-done.returncode}"
    if done.returncode != 0:
        tail = done.stderr.strip().splitlines()[-1:] or ["no stderr"]
        return elapsed, done.returncode, f"exit {done.returncode}: {tail[0]}"
    if not (out / "clean.png").is_file():
        return elapsed, done.returncode, "exit 0 without clean.png"
    return elapsed, done.returncode, None


def measure(width: int, height: int, warmups: int, runs: int, work: Path) -> SizeResult:
    result = SizeResult(width, height)
    image = work / f"in-{width}x{height}.png"
    if not cv2.imwrite(str(image), caption_image(width, height)):
        result.failure = "could not write the generated image"
        return result
    params = work / "params.json"
    params.write_text(json.dumps(PARAMS), encoding="utf-8")
    if result.over_cap:
        _, code, failure = run_child(image, params, work / f"out-{width}x{height}")
        if code != INPUT_REJECTED:
            result.failure = failure or f"exit {code}, expected {INPUT_REJECTED} (input_rejected)"
        return result
    for index in range(warmups + runs):
        out = work / f"out-{width}x{height}-{index}"
        elapsed, _, failure = run_child(image, params, out)
        # Children are reaped one by one, so this high-water mark is the largest child so far;
        # sizes ascend, which makes it the peak of the current size.
        result.peak_kib = max(
            result.peak_kib, resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss
        )
        if failure is not None:
            result.failure = failure
            return result
        if index >= warmups:
            result.seconds.append(elapsed)
    return result


def cpu_model() -> str:
    try:
        lscpu = subprocess.run(["lscpu"], capture_output=True, text=True, check=False).stdout
    except OSError:
        return "unknown (lscpu not available)"
    for line in lscpu.splitlines():
        if line.startswith("Model name:"):
            return line.split(":", 1)[1].strip()
    return "unknown"


def parse_sizes(raw: list[str]) -> list[tuple[int, int, int, int]]:
    plan = {(w, h): (w, h, warm, runs) for w, h, warm, runs in DEFAULT_PLAN}
    chosen: list[tuple[int, int, int, int]] = []
    for item in raw:
        width, _, height = item.partition("x")
        key = (int(width), int(height))
        chosen.append(plan.get(key, (key[0], key[1], 0, 2)))
    return chosen


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sizes", nargs="*", help="WIDTHxHEIGHT, ascending; default is the plan")
    args = parser.parse_args(argv)
    plan = parse_sizes(args.sizes) if args.sizes else list(DEFAULT_PLAN)

    threads = len(os.sched_getaffinity(0))
    print(f"cpu: {cpu_model()}")
    print(f"torch threads used by the child: {threads} (one per schedulable CPU)")
    print(f"limits: {WALL_LIMIT_SECONDS:.0f} s wall, {RSS_LIMIT_KIB / 1024 / 1024:.0f} GiB RSS")

    failed = False
    with tempfile.TemporaryDirectory(prefix="bench-overlay-") as tmp:
        for width, height, warmups, runs in plan:
            result = measure(width, height, warmups, runs, Path(tmp))
            label = f"{width}x{height}"
            if result.over_cap:
                if result.failure:
                    print(f"{label}: OVER BUDGET, not refused by the pixel cap ({result.failure})")
                else:
                    print(f"{label}: rejected by pixel cap (expected), cap {MAX_PIXELS} px")
                failed = failed or result.over_budget
                continue
            peak = f"{result.peak_kib / 1024 / 1024:.2f} GiB"
            if result.seconds:
                p50 = f"{statistics.median(result.seconds):.1f} s"
                worst = f"{p95(result.seconds):.1f} s"
            else:
                p50 = worst = "n/a"
            verdict = "OVER BUDGET" if result.over_budget else "within budget"
            note = f" ({result.failure})" if result.failure else ""
            print(f"{label}: P50 {p50}, P95 {worst}, peak RSS {peak}, {verdict}{note}")
            failed = failed or result.over_budget
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
