#!/usr/bin/env python3
"""Cost measurement of the role_suggest handler, run inside the trend-runtime image.

Generates three 480x854, 24 fps clips with ffmpeg (a single scene of 24 frames, ten scenes of
24 frames, and the worst case of 288 scenes of 5 frames over 60 s), then calls the real handler
with a real ProcessRunner three times per clip. Prints per-run seconds, then P50 and P95 per job
and per scene for each clip, the model-load seconds and torch thread count the detector child
reports, the CPU model and the children's peak resident memory. The per-scene time is the job
time minus the child's measured model load, divided by the scene count. Exits 1 when any run
fails or returns a suggestion list that does not match the scenes.

The container runs under `--cpus=4`; the child sets its torch threads from its CPU set, so the
figures describe a 4-CPU pool. The detection time of a fixed-size detector does not depend on
the picture content, so a synthetic clip measures the same cost as one with people.

Usage: role_suggest_bench.py
"""

import math
import re
import resource
import shutil
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path
from types import SimpleNamespace
from typing import cast

from flowcortex_worker.contracts.media_job import MediaJob
from flowcortex_worker.errors import PermanentError, TransientError
from flowcortex_worker.handlers import role_suggest
from flowcortex_worker.runtime import ProcessRunner

SIZE = "480x854"
FPS = 24
RUNS_PER_CLIP = 3
LOAD_LINE = re.compile(r"trend-engine: load=(?P<load>\d+(?:\.\d+)?) threads=(?P<threads>\d+)")


@dataclass(frozen=True)
class Clip:
    label: str
    scenes: int
    frames_per_scene: int


CLIPS = (
    Clip("1 scene x 24 frames", 1, 24),
    Clip("10 scenes x 24 frames", 10, 24),
    Clip("288 scenes x 5 frames", 288, 5),
)


@dataclass
class Run:
    clip: Clip
    seconds: float
    load_seconds: float
    threads: int

    @property
    def per_scene_seconds(self) -> float:
        return (self.seconds - self.load_seconds) / self.clip.scenes


class RecordingRunner(ProcessRunner):
    """A real runner that keeps the detector child's stderr, which the handler does not return."""

    def __init__(self) -> None:
        super().__init__()
        self.detector_stderr = b""

    def run(self, argv: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess[bytes]:
        completed = super().run(argv, cwd)
        if role_suggest.ENGINE_MODULE in argv:
            self.detector_stderr = completed.stderr
        return completed


def percentile(values: list[float], fraction: float) -> float:
    """Nearest rank: the smallest value with at least `fraction` of the runs at or below it."""
    ordered = sorted(values)
    return ordered[max(math.ceil(fraction * len(ordered)), 1) - 1]


def cpu_model() -> str:
    listing = subprocess.run(["lscpu"], capture_output=True, text=True, check=True).stdout
    for line in listing.splitlines():
        if line.startswith("Model name:"):
            return line.split(":", 1)[1].strip()
    raise RuntimeError("lscpu lists no model name")


def generate(clip: Clip, target: Path) -> None:
    seconds = clip.scenes * clip.frames_per_scene / FPS
    subprocess.run(
        [
            *("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y"),
            *("-f", "lavfi", "-i", f"testsrc2=s={SIZE}:r={FPS}:d={seconds}"),
            *("-c:v", "libx264", "-pix_fmt", "yuv420p", str(target)),
        ],
        check=True,
        capture_output=True,
    )


def scenes_of(clip: Clip) -> list[dict[str, int]]:
    size = clip.frames_per_scene
    return [
        {"index": i, "startFrame": i * size, "endFrame": (i + 1) * size} for i in range(clip.scenes)
    ]


def measure(clip: Clip, source: Path) -> Run:
    with tempfile.TemporaryDirectory(prefix="role-suggest-bench-") as scratch:
        video = Path(scratch) / "clip.mp4"
        shutil.copyfile(source, video)
        job = cast(MediaJob, SimpleNamespace(params={"scenes": scenes_of(clip)}))
        runner = RecordingRunner()
        started = time.monotonic()
        outcome = role_suggest.handle(job, {"video": video}, runner)
        seconds = time.monotonic() - started
    suggestions = outcome.data["suggestions"]
    if [item["scene"] for item in suggestions] != list(range(clip.scenes)):
        raise RuntimeError(f"the suggestions do not match the {clip.scenes} scenes")
    loaded = LOAD_LINE.search(runner.detector_stderr.decode("utf-8", "replace"))
    if loaded is None:
        raise RuntimeError("the detector child printed no 'trend-engine: load=' line")
    return Run(clip, seconds, float(loaded["load"]), int(loaded["threads"]))


def main() -> int:
    runs: list[Run] = []
    with tempfile.TemporaryDirectory(prefix="role-suggest-clips-") as folder:
        for clip in CLIPS:
            source = Path(folder) / f"{clip.scenes}x{clip.frames_per_scene}.mp4"
            generate(clip, source)
            for attempt in range(1, RUNS_PER_CLIP + 1):
                try:
                    run = measure(clip, source)
                except (PermanentError, TransientError, RuntimeError, OSError) as failed:
                    print(
                        f"role suggest bench: {clip.label}, run {attempt} failed: {failed!r}",
                        file=sys.stderr,
                    )
                    return 1
                runs.append(run)
                print(
                    f"{clip.label}, run {attempt}: {run.seconds:.2f} s job, "
                    f"{run.per_scene_seconds:.3f} s/scene, model load {run.load_seconds:.2f} s, "
                    f"{run.threads} threads",
                    flush=True,
                )

    peak_mib = resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss / 1024
    print(f"\nCPU: {cpu_model()}")
    print(f"Torch threads: {sorted({run.threads for run in runs})}")
    print(f"Children peak resident memory: {peak_mib:.0f} MiB (ru_maxrss over every child)")
    print(f"Frame size: {SIZE}, runs per clip: {RUNS_PER_CLIP}")
    for clip in CLIPS:
        mine = [run for run in runs if run.clip is clip]
        jobs = [run.seconds for run in mine]
        scenes = [run.per_scene_seconds for run in mine]
        loads = [run.load_seconds for run in mine]
        job_p50, job_p95 = percentile(jobs, 0.5), percentile(jobs, 0.95)
        scene_p50, scene_p95 = percentile(scenes, 0.5), percentile(scenes, 0.95)
        print(
            f"{clip.label}: job P50 {job_p50:.2f} s P95 {job_p95:.2f} s"
            f" | per scene P50 {scene_p50:.3f} s P95 {scene_p95:.3f} s"
            f" | model load P50 {percentile(loads, 0.5):.2f} s"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
