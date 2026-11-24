"""role_suggest: input and scene checks, clip gates, frame extraction, detector child mapping."""

from __future__ import annotations

import json
import re
import shutil
import subprocess
from collections.abc import Callable
from pathlib import Path
from types import SimpleNamespace
from typing import Any, cast

import pytest

from flowcortex_worker.contracts.media_job import MediaJob
from flowcortex_worker.errors import PermanentError, TransientError
from flowcortex_worker.handlers import role_suggest
from flowcortex_worker.runtime import ProcessKilled, ProcessRunner
from flowcortex_worker.trend_studio.scenes import MAX_SCENES

pytestmark = pytest.mark.skipif(
    shutil.which("ffmpeg") is None, reason="ffmpeg is installed in the worker image"
)

Script = Callable[[list[str]], "subprocess.CompletedProcess[bytes]"]

SCENES = [
    {"index": 0, "startFrame": 0, "endFrame": 24},
    {"index": 1, "startFrame": 24, "endFrame": 72},
    {"index": 2, "startFrame": 72, "endFrame": 96},
]

# Pixel boxes the scripted detector reports for a 160x90 frame of each colour.
PIXEL_BOXES: dict[str, list[list[float]]] = {
    "red": [[16, 9, 80, 90, 0.8], [0, 0, 10, 10, 0.3]],
    "blue": [[0, 0, 80, 45, 0.6], [80, 45, 160, 90, 0.95]],
    "green": [],
}


def make_job(**params: Any) -> MediaJob:
    # The generated capability literal has no `role_suggest` until the lane owner adds it.
    return cast(MediaJob, SimpleNamespace(params=params))


def mean_colour(frame: Path) -> str:
    raw = subprocess.run(
        [
            "ffmpeg", "-v", "error", "-i", str(frame), "-vf", "scale=1:1",
            "-f", "rawvideo", "-pix_fmt", "rgb24", "-",
        ],
        check=True,
        capture_output=True,
    ).stdout  # fmt: skip
    channels = dict(zip(("red", "green", "blue"), raw[:3], strict=True))
    return max(channels, key=lambda name: channels[name])


def write_report(argv: list[str], report: Any) -> None:
    Path(argv[argv.index("--output") + 1]).write_text(json.dumps(report))


def colour_engine(argv: list[str]) -> subprocess.CompletedProcess[bytes]:
    """The detector child as the real one reports it, but keyed on each frame's colour."""
    frames = argv[argv.index("--output") + 2 :]
    write_report(
        argv,
        [{"width": 160, "height": 90, "boxes": PIXEL_BOXES[mean_colour(Path(f))]} for f in frames],
    )
    return subprocess.CompletedProcess(argv, 0, b"", b"")


def exits(code: int, stderr: str = "") -> Script:
    return lambda argv: subprocess.CompletedProcess(argv, code, b"", stderr.encode())


def reports(report: Any) -> Script:
    def script(argv: list[str]) -> subprocess.CompletedProcess[bytes]:
        write_report(argv, report)
        return subprocess.CompletedProcess(argv, 0, b"", b"")

    return script


class ScriptedRunner(ProcessRunner):
    """Runs ffprobe and ffmpeg for real; answers the detector child (and optionally ffmpeg and
    the ffprobe frame count) from a script. Records every argv."""

    def __init__(
        self,
        engine: Script = colour_engine,
        ffmpeg: Script | None = None,
        frame_count: Script | None = None,
    ) -> None:
        super().__init__()
        self.argvs: list[list[str]] = []
        self._engine = engine
        self._ffmpeg = ffmpeg
        self._frame_count = frame_count

    def run(self, argv: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess[bytes]:
        self.argvs.append(argv)
        if argv[1:3] == ["-m", role_suggest.ENGINE_MODULE]:
            return self._engine(argv)
        if argv[0] == "ffmpeg" and self._ffmpeg is not None:
            return self._ffmpeg(argv)
        if argv[0] == "ffprobe" and "-count_frames" in argv and self._frame_count is not None:
            return self._frame_count(argv)
        return super().run(argv, cwd)

    def engine_runs(self) -> list[list[str]]:
        return [a for a in self.argvs if a[1:3] == ["-m", role_suggest.ENGINE_MODULE]]

    def ffmpeg_runs(self) -> list[list[str]]:
        return [a for a in self.argvs if a[0] == "ffmpeg"]


def render(path: Path, *arguments: str) -> Path:
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", *arguments, "-pix_fmt", "yuv420p", "-f", "mp4", str(path)],
        check=True,
    )
    return path


@pytest.fixture(scope="module")
def colour_clip(tmp_path_factory: pytest.TempPathFactory) -> Path:
    """Red for 1 s, blue for 2 s, green for 1 s at 24 fps, 160x90."""
    return render(
        tmp_path_factory.mktemp("clips") / "colours.mp4",
        "-f", "lavfi", "-i", "color=c=red:s=160x90:d=1:r=24",
        "-f", "lavfi", "-i", "color=c=blue:s=160x90:d=2:r=24",
        "-f", "lavfi", "-i", "color=c=green:s=160x90:d=1:r=24",
        "-filter_complex", "[0:v][1:v][2:v]concat=n=3:v=1:a=0[v]",
        "-map", "[v]",
    )  # fmt: skip


@pytest.fixture
def clip(colour_clip: Path, tmp_path: Path) -> Path:
    """A private copy named like a stored input (no extension): the handler writes beside it."""
    target = tmp_path / "input"
    shutil.copy(colour_clip, target)
    return target


def refused(runner: ProcessRunner, source: Path, **params: Any) -> PermanentError:
    job = make_job(**params)
    with pytest.raises(PermanentError) as caught:
        role_suggest.handle(job, {"video": source}, runner)
    return caught.value


@pytest.mark.parametrize("keys", [{"media"}, {"video", "frames"}])
def test_the_inputs_are_exactly_video(keys: set[str]) -> None:
    runner = ScriptedRunner()
    job = make_job(scenes=SCENES)
    inputs = {key: Path("/nonexistent") for key in keys}
    with pytest.raises(PermanentError) as caught:
        role_suggest.handle(job, inputs, runner)
    assert caught.value.code == "contract_violation"
    assert "video" in caught.value.message
    assert runner.argvs == []


@pytest.mark.parametrize("scenes", [[], "0,24", None])
def test_a_bad_scene_list_is_refused_before_any_process_runs(scenes: Any) -> None:
    runner = ScriptedRunner()
    error = refused(runner, Path("/nonexistent"), scenes=scenes)
    assert error.code == "contract_violation"
    assert "scenes" in error.message
    assert runner.argvs == []


def test_a_scene_gap_is_refused_naming_its_position_before_any_process_runs() -> None:
    runner = ScriptedRunner()
    scenes = [SCENES[0], {"index": 1, "startFrame": 30, "endFrame": 72}]
    error = refused(runner, Path("/nonexistent"), scenes=scenes)
    assert error.code == "contract_violation"
    assert "scene 1" in error.message
    assert runner.argvs == []


def test_three_colour_scenes_become_three_suggestions_through_one_child(clip: Path) -> None:
    runner = ScriptedRunner()
    scenes = [{**scene, "frame": f"frame_{scene['index']:03d}.jpg"} for scene in SCENES]

    outcome = role_suggest.handle(make_job(scenes=scenes), {"video": clip}, runner)

    assert outcome.data == {
        "suggestions": [
            {"scene": 0, "boxes": [[0.1, 0.1, 0.5, 1.0, 0.8]]},
            {"scene": 1, "boxes": [[0.5, 0.5, 1.0, 1.0, 0.95], [0.0, 0.0, 0.5, 0.5, 0.6]]},
            {"scene": 2, "boxes": []},
        ]
    }
    assert outcome.files == []

    [extraction] = runner.ffmpeg_runs()
    assert extraction[extraction.index("-protocol_whitelist") + 1] == "file"
    assert "(eq(n\\,12)+(eq(n\\,48)+eq(n\\,84)))" in extraction[extraction.index("-vf") + 1]
    [engine] = runner.engine_runs()
    frames = engine[engine.index("--output") + 2 :]
    assert [Path(f).name for f in frames] == [f"role_frame_{i:03d}.jpg" for i in range(3)]
    assert {Path(f).parent for f in frames} == {clip.parent}


def test_the_selection_of_the_most_scenes_stays_a_shallow_tree() -> None:
    # ffmpeg 7.1 (the image's distro build) refuses a flat sum of more than 100 terms.
    numbers = [i * 5 + 2 for i in range(MAX_SCENES)]

    selection = role_suggest.frame_selection(numbers)

    assert selection.count("eq(n\\,") == MAX_SCENES
    assert all(f"eq(n\\,{number})" in selection for number in numbers)
    depth = deepest = 0
    for char in selection:
        depth += {"(": 1, ")": -1}.get(char, 0)
        deepest = max(deepest, depth)
    assert depth == 0
    assert deepest <= 12
    assert re.search(r"\+eq\(n\\,\d+\)\+", selection) is None


def test_a_middle_frame_beyond_the_clip_names_the_scene_and_the_frame(clip: Path) -> None:
    runner = ScriptedRunner()
    error = refused(runner, clip, scenes=[{"index": 0, "startFrame": 0, "endFrame": 400}])
    assert error.code == "contract_violation"
    assert "scene 0" in error.message
    assert "200" in error.message
    assert runner.engine_runs() == []


def test_a_failed_frame_extraction_is_probe_failed_and_masks_the_work_directory(
    clip: Path,
) -> None:
    runner = ScriptedRunner(ffmpeg=exits(1, f"{clip}: Invalid data found"))
    error = refused(runner, clip, scenes=SCENES)
    assert error.code == "probe_failed"
    assert "Invalid data found" in error.message
    assert str(clip.parent) not in error.message
    assert runner.engine_runs() == []


def prints(stdout: str) -> Script:
    return lambda argv: subprocess.CompletedProcess(argv, 0, stdout.encode(), b"")


@pytest.mark.parametrize(
    "frame_count",
    [exits(1, "moov atom not found"), prints("0\n"), prints("N/A\n"), prints("")],
    ids=["ffprobe-exits-non-zero", "zero-frames", "not-a-number", "empty-output"],
)
def test_a_frame_count_that_fails_or_is_unusable_is_probe_failed_before_any_decode(
    clip: Path, frame_count: Script
) -> None:
    runner = ScriptedRunner(frame_count=frame_count)
    error = refused(runner, clip, scenes=SCENES)
    assert error.code == "probe_failed"
    assert runner.ffmpeg_runs() == []
    assert runner.engine_runs() == []


def test_a_frame_that_ffmpeg_did_not_write_is_probe_failed_and_never_detected(clip: Path) -> None:
    runner = ScriptedRunner(ffmpeg=exits(0))
    error = refused(runner, clip, scenes=SCENES)
    assert error.code == "probe_failed"
    assert "scene 0" in error.message
    assert runner.engine_runs() == []


def test_a_clip_over_sixty_seconds_is_duration_exceeded_before_any_decode(tmp_path: Path) -> None:
    long_clip = render(
        tmp_path / "input", "-f", "lavfi", "-i", "testsrc=duration=61:size=160x90:rate=24"
    )
    runner = ScriptedRunner()
    error = refused(runner, long_clip, scenes=[{"index": 0, "startFrame": 0, "endFrame": 24}])
    assert error.code == "duration_exceeded"
    assert runner.ffmpeg_runs() == []


def test_a_clip_that_is_not_24_fps_names_its_rate_before_any_decode(tmp_path: Path) -> None:
    slow = render(tmp_path / "input", "-f", "lavfi", "-i", "testsrc=duration=2:size=160x90:rate=30")
    runner = ScriptedRunner()
    error = refused(runner, slow, scenes=[{"index": 0, "startFrame": 0, "endFrame": 24}])
    assert error.code == "contract_violation"
    assert "30/1" in error.message
    assert runner.ffmpeg_runs() == []


def test_an_audio_only_clip_is_probe_failed(tmp_path: Path) -> None:
    audio = render(tmp_path / "input", "-f", "lavfi", "-i", "sine=duration=2", "-c:a", "aac")
    runner = ScriptedRunner()
    error = refused(runner, audio, scenes=SCENES)
    assert error.code == "probe_failed"
    assert runner.ffmpeg_runs() == []


def test_a_playlist_saved_as_the_clip_is_probe_failed_and_never_decoded(tmp_path: Path) -> None:
    playlist = tmp_path / "input"
    playlist.write_text("#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:2.0,\nsegment.ts\n#EXT-X-ENDLIST\n")
    runner = ScriptedRunner()
    error = refused(runner, playlist, scenes=SCENES)
    assert error.code == "probe_failed"
    assert runner.ffmpeg_runs() == []
    assert runner.engine_runs() == []


def test_a_detector_that_cannot_load_is_provider_capability_unavailable(clip: Path) -> None:
    error = refused(
        ScriptedRunner(engine=exits(3, "RT-DETR cannot be loaded: ImportError")),
        clip,
        scenes=SCENES,
    )
    assert error.code == "provider_capability_unavailable"
    assert "ImportError" in error.message


def test_a_detector_crash_is_provider_request_failed_with_its_last_line_and_no_path(
    clip: Path,
) -> None:
    stderr = f"Traceback\n{clip.parent}/x.py line 9\nRuntimeError: boom\n"
    error = refused(ScriptedRunner(engine=exits(1, stderr)), clip, scenes=SCENES)
    assert error.code == "provider_request_failed"
    assert "RuntimeError: boom" in error.message
    assert str(clip.parent) not in error.message


def test_a_detector_ended_by_a_foreign_signal_is_transient(clip: Path) -> None:
    job = make_job(scenes=SCENES)
    runner = ScriptedRunner(engine=exits(-9))
    with pytest.raises(TransientError):
        role_suggest.handle(job, {"video": clip}, runner)


def test_a_detector_that_exits_zero_without_a_report_is_provider_response_invalid(
    clip: Path,
) -> None:
    error = refused(ScriptedRunner(engine=exits(0)), clip, scenes=SCENES)
    assert error.code == "provider_response_invalid"


GOOD_ITEM: dict[str, Any] = {"width": 160, "height": 90, "boxes": []}


@pytest.mark.parametrize(
    "report",
    [
        [GOOD_ITEM, GOOD_ITEM],
        {"frames": [GOOD_ITEM] * 3},
        ["not an object"] * 3,
        [{"width": 160, "height": 90}] * 3,
        [{**GOOD_ITEM, "width": 0}] * 3,
        [{**GOOD_ITEM, "height": True}] * 3,
        [{**GOOD_ITEM, "boxes": [[0, 0, 80, 45]]}] * 3,
        [{**GOOD_ITEM, "boxes": [[0, 0, 80, 45, "0.9"]]}] * 3,
        [{**GOOD_ITEM, "boxes": [[0, 0, float("nan"), 45, 0.9]]}] * 3,
        [{**GOOD_ITEM, "boxes": [[0, 0, 80, 45, float("inf")]]}] * 3,
        [{**GOOD_ITEM, "boxes": "none"}] * 3,
    ],
    ids=[
        "two-frames-for-three-scenes", "not-a-list", "item-not-an-object", "no-boxes-key",
        "zero-width", "boolean-height", "four-number-box", "string-score", "nan-coordinate",
        "infinite-score", "boxes-not-a-list",
    ],
)  # fmt: skip
def test_a_report_that_does_not_match_the_frames_sent_is_provider_response_invalid(
    clip: Path, report: Any
) -> None:
    error = refused(ScriptedRunner(engine=reports(report)), clip, scenes=SCENES)
    assert error.code == "provider_response_invalid"


def test_a_report_that_is_not_json_is_provider_response_invalid(clip: Path) -> None:
    def garbled(argv: list[str]) -> subprocess.CompletedProcess[bytes]:
        Path(argv[argv.index("--output") + 1]).write_bytes(b"\xff not json")
        return subprocess.CompletedProcess(argv, 0, b"", b"")

    error = refused(ScriptedRunner(engine=garbled), clip, scenes=SCENES)
    assert error.code == "provider_response_invalid"


def test_a_kill_during_detection_propagates_and_is_never_an_error_result(clip: Path) -> None:
    def killed(argv: list[str]) -> subprocess.CompletedProcess[bytes]:
        raise ProcessKilled("watchdog")

    job = make_job(scenes=SCENES)
    runner = ScriptedRunner(engine=killed)
    with pytest.raises(ProcessKilled):
        role_suggest.handle(job, {"video": clip}, runner)
