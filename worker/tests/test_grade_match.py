"""The grade_match capability: parameter refusals, the join, the padding and the two files."""

from __future__ import annotations

import shutil
import subprocess
from collections.abc import Mapping
from pathlib import Path
from typing import Any

import pytest
from test_derive import RecordingRunner, describe, job, make_clip, refused

from flowcortex_worker.handlers import grade_match
from flowcortex_worker.runtime import ProcessRunner

needs_ffmpeg = pytest.mark.skipif(
    shutil.which("ffmpeg") is None, reason="ffmpeg is installed in the worker image"
)

ONE_FRAME_MS = 40  # a 25 fps clip


def run_grade(shots: Mapping[str, Path], runner: ProcessRunner, **params: Any) -> Any:
    return grade_match.handle(job(**params), shots, runner)


def make_grey_shot(path: Path, *, luma: int, seconds: float = 2.0) -> Path:
    """A uniform 25 fps shot whose Y plane is `luma`, untagged, with audio.

    Tagging the encode as TV range makes ffmpeg convert the plane and lose a level, so the
    fixture stays untagged; the join reads an untagged shot as BT.709.
    """
    subprocess.run(
        [
            "ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i",
            f"color=c=black:size=320x180:rate=25:duration={seconds},format=yuv420p,"
            f"lutyuv=y={luma}:u=128:v=128",
            "-f", "lavfi", "-i", f"sine=duration={seconds}",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-f", "mp4", str(path),
        ],
        check=True,
    )  # fmt: skip
    return path


def streams_of(path: Path) -> dict[str, Any]:
    streams = describe(path)["streams"]
    assert isinstance(streams, dict)
    return streams


# --- parameters ----------------------------------------------------------------------------------


@pytest.mark.parametrize(
    "keys",
    [
        ["shot_1", "shot_2", "shot_3"],
        ["shot_2"],
        ["video"],
        ["shot_1", "video"],
    ],
)
def test_the_input_keys_must_be_shot_1_and_optionally_shot_2(keys: list[str]) -> None:
    runner = RecordingRunner()

    error = refused(
        lambda: run_grade({k: Path(f"/nonexistent/{k}") for k in keys}, runner, trimMs=1000)
    )

    assert error.code == "contract_violation"
    assert "shot_1" in error.message
    assert runner.argvs == []


@pytest.mark.parametrize(
    ("params", "field"),
    [
        ({}, "trimMs"),
        ({"trimMs": 0}, "trimMs"),
        ({"trimMs": -5}, "trimMs"),
        ({"trimMs": 1.5}, "trimMs"),
        ({"trimMs": "2000"}, "trimMs"),
        ({"trimMs": True}, "trimMs"),
        ({"trimMs": None}, "trimMs"),
        ({"trimMs": 1000, "targetOut": {"y": 300}}, "targetOut"),
        ({"trimMs": 1000, "targetIn": {"u": "9"}}, "targetIn"),
    ],
)
def test_a_bad_parameter_is_a_contract_violation_naming_it_before_any_tool_runs(
    params: dict[str, Any], field: str
) -> None:
    runner = RecordingRunner()

    error = refused(lambda: run_grade({"shot_1": Path("/nonexistent")}, runner, **params))

    assert error.code == "contract_violation"
    assert field in error.message
    assert runner.argvs == []


# --- padding and trimming ------------------------------------------------------------------------


@needs_ffmpeg
def test_a_shortfall_of_300_ms_is_padded_to_the_trim(tmp_path: Path) -> None:
    shot = make_grey_shot(tmp_path / "shot_1", luma=100)

    outcome = run_grade({"shot_1": shot}, RecordingRunner(), trimMs=2300)

    assert outcome.data["padMs"] == 300
    assert abs(outcome.data["durationMs"] - 2300) <= ONE_FRAME_MS
    assert abs(outcome.data["ungraded"]["durationMs"] - 2300) <= ONE_FRAME_MS
    graded = streams_of(tmp_path / "graded.mp4")
    assert "audio" in graded


@needs_ffmpeg
def test_a_shortfall_of_600_ms_is_input_rejected_before_any_decode(tmp_path: Path) -> None:
    shot = make_grey_shot(tmp_path / "shot_1", luma=100)
    runner = RecordingRunner()

    error = refused(lambda: run_grade({"shot_1": shot}, runner, trimMs=2600))

    assert error.code == "input_rejected"
    assert "2600" in error.message
    assert "2000" in error.message
    assert runner.encodes == []


@needs_ffmpeg
def test_shots_longer_than_the_trim_are_cut_to_it_exactly(tmp_path: Path) -> None:
    shot = make_grey_shot(tmp_path / "shot_1", luma=100, seconds=3)

    outcome = run_grade({"shot_1": shot}, RecordingRunner(), trimMs=1200)

    assert outcome.data["padMs"] == 0
    assert abs(outcome.data["durationMs"] - 1200) <= ONE_FRAME_MS


# --- grading -------------------------------------------------------------------------------------


@needs_ffmpeg
def test_a_grey_shot_is_lifted_onto_each_target(tmp_path: Path) -> None:
    shot = make_grey_shot(tmp_path / "shot_1", luma=100)

    outcome = run_grade(
        {"shot_1": shot},
        RecordingRunner(),
        trimMs=2000,
        targetIn={"y": 110},
        targetOut={"y": 120},
    )

    grade = outcome.data["grade"]
    assert grade["in"]["dy"] == pytest.approx(10, abs=0.5)
    assert grade["out"]["dy"] == pytest.approx(20, abs=0.5)
    assert grade["in"]["du"] == 0
    assert grade["out"]["dv"] == 0


@needs_ffmpeg
def test_the_graded_file_is_brighter_than_the_ungraded_one(tmp_path: Path) -> None:
    shot = make_grey_shot(tmp_path / "shot_1", luma=100)

    run_grade({"shot_1": shot}, RecordingRunner(), trimMs=2000, targetIn={"y": 120})

    def mean_luma(path: Path) -> float:
        out = subprocess.run(
            ["ffmpeg", "-hide_banner", "-i", str(path), "-vf", "signalstats,metadata=print",
             "-f", "null", "-"],
            capture_output=True, text=True, check=True,
        )  # fmt: skip
        values = [
            float(line.split("=")[1])
            for line in (out.stderr + out.stdout).splitlines()
            if "lavfi.signalstats.YAVG=" in line
        ]
        return sum(values) / len(values)

    assert mean_luma(tmp_path / "graded.mp4") == pytest.approx(120, abs=1.5)
    assert mean_luma(tmp_path / "ungraded.mp4") == pytest.approx(100, abs=1.5)


@needs_ffmpeg
def test_without_a_target_no_measuring_pass_runs_and_every_offset_is_zero(
    tmp_path: Path,
) -> None:
    shot = make_grey_shot(tmp_path / "shot_1", luma=100)
    runner = RecordingRunner()

    outcome = run_grade({"shot_1": shot}, runner, trimMs=2000)

    assert not any("signalstats" in a for argv in runner.encodes for a in argv)
    zero = {"dy": 0.0, "du": 0.0, "dv": 0.0}
    assert outcome.data["grade"] == {"in": zero, "out": zero}


@needs_ffmpeg
def test_a_target_runs_one_measuring_pass_before_the_encode(tmp_path: Path) -> None:
    shot = make_grey_shot(tmp_path / "shot_1", luma=100)
    runner = RecordingRunner()

    run_grade({"shot_1": shot}, runner, trimMs=2000, targetOut={"v": 128})

    measuring = [a for a in runner.encodes if any("signalstats" in x for x in a)]
    assert len(measuring) == 1
    assert measuring[0][-3:] == ["-f", "null", "-"]
    assert "-c:v" not in measuring[0]


# --- the join ------------------------------------------------------------------------------------


@needs_ffmpeg
def test_a_portrait_second_shot_is_covered_to_the_size_of_the_first(tmp_path: Path) -> None:
    first = make_clip(tmp_path / "shot_1", size="1280x720", seconds=2, audio=True)
    second = make_clip(tmp_path / "shot_2", size="720x1280", seconds=2, audio=False)

    outcome = run_grade({"shot_1": first, "shot_2": second}, RecordingRunner(), trimMs=4000)

    assert (outcome.data["width"], outcome.data["height"]) == (1280, 720)
    assert (outcome.data["ungraded"]["width"], outcome.data["ungraded"]["height"]) == (1280, 720)
    assert abs(outcome.data["durationMs"] - 4000) <= ONE_FRAME_MS
    probed = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "a", "-show_entries",
         "stream=codec_type,duration", "-of", "csv=p=0", str(tmp_path / "graded.mp4")],
        capture_output=True, text=True, check=True,
    )  # fmt: skip
    assert len(probed.stdout.split()) == 1  # one audio stream, silence for the silent shot


@needs_ffmpeg
def test_only_the_first_shot_has_audio_and_the_audio_lasts_the_trim(tmp_path: Path) -> None:
    first = make_clip(tmp_path / "shot_1", size="320x180", seconds=2, audio=True)
    second = make_clip(tmp_path / "shot_2", size="320x180", seconds=2, audio=False)

    run_grade({"shot_1": first, "shot_2": second}, RecordingRunner(), trimMs=4000)

    probed = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries",
         "stream=duration", "-of", "csv=p=0", str(tmp_path / "graded.mp4")],
        capture_output=True, text=True, check=True,
    )  # fmt: skip
    assert float(probed.stdout) == pytest.approx(4.0, abs=0.06)


@needs_ffmpeg
def test_shots_without_any_audio_make_files_without_audio(tmp_path: Path) -> None:
    shot = make_clip(tmp_path / "shot_1", size="320x180", seconds=2, audio=False)

    run_grade({"shot_1": shot}, RecordingRunner(), trimMs=2000)

    assert "audio" not in streams_of(tmp_path / "graded.mp4")
    assert "audio" not in streams_of(tmp_path / "ungraded.mp4")


@needs_ffmpeg
def test_one_encode_writes_both_files_and_the_outcome_holds_them(tmp_path: Path) -> None:
    shot = make_grey_shot(tmp_path / "shot_1", luma=100)
    runner = RecordingRunner()

    outcome = run_grade({"shot_1": shot}, runner, trimMs=2000)

    encodes = [argv for argv in runner.encodes if "libx264" in argv]
    assert len(encodes) == 1
    assert [a for a in encodes[0] if a.endswith(".mp4")] == [
        str(tmp_path / "graded.mp4"),
        str(tmp_path / "ungraded.mp4"),
    ]
    assert [(f.name, f.mime) for f in outcome.files] == [
        ("graded.mp4", "video/mp4"),
        ("ungraded.mp4", "video/mp4"),
    ]
    assert set(outcome.data) == {"width", "height", "durationMs", "padMs", "grade", "ungraded"}
    assert set(outcome.data["ungraded"]) == {"width", "height", "durationMs"}
    assert outcome.data["durationMs"] == outcome.data["ungraded"]["durationMs"]


@needs_ffmpeg
def test_the_files_are_h264_crf17_bt709_tv_with_aac_audio(tmp_path: Path) -> None:
    shot = make_grey_shot(tmp_path / "shot_1", luma=100)
    runner = RecordingRunner()

    run_grade({"shot_1": shot}, runner, trimMs=2000)

    [argv] = [a for a in runner.encodes if "libx264" in a]
    assert argv.count("-crf") == 2
    assert argv[argv.index("-crf") + 1] == "17"
    assert argv.count("bt709") >= 2
    assert argv.count("tv") >= 2
    assert argv.count("-protocol_whitelist") == 1
    for name in ("graded.mp4", "ungraded.mp4"):
        info = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries",
             "stream=codec_name,pix_fmt,color_space,color_range", "-of", "csv=p=0",
             str(tmp_path / name)],
            capture_output=True, text=True, check=True,
        )  # fmt: skip
        assert "h264,yuv420p,tv,bt709" in info.stdout
        assert "aac" in info.stdout


@needs_ffmpeg
def test_a_failed_encode_is_probe_failed_naming_ffmpeg(tmp_path: Path) -> None:
    shot = make_grey_shot(tmp_path / "shot_1", luma=100)
    runner = RecordingRunner(fail_when=lambda argv: "libx264" in argv)

    error = refused(lambda: run_grade({"shot_1": shot}, runner, trimMs=2000))

    assert error.code == "probe_failed"
    assert "ffmpeg" in error.message
    assert "simulated muxer failure" in error.message


@needs_ffmpeg
def test_a_failed_measuring_pass_is_probe_failed_naming_ffmpeg(tmp_path: Path) -> None:
    shot = make_grey_shot(tmp_path / "shot_1", luma=100)
    runner = RecordingRunner(fail_when=lambda argv: any("signalstats" in a for a in argv))

    error = refused(lambda: run_grade({"shot_1": shot}, runner, trimMs=2000, targetIn={"y": 1}))

    assert error.code == "probe_failed"
    assert "ffmpeg" in error.message
    assert not any("libx264" in a for a in runner.encodes)
