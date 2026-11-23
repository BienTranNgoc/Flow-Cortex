"""The derive capability: parameter refusals, the cut and effect ops, the output it describes."""

from __future__ import annotations

import json
import shutil
import subprocess
from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest
from conftest import job_body

from flowcortex_worker.contracts.media_job import MediaJob
from flowcortex_worker.errors import PermanentError
from flowcortex_worker.handlers import derive
from flowcortex_worker.runtime import ProcessKilled, ProcessRunner

needs_ffmpeg = pytest.mark.skipif(
    shutil.which("ffmpeg") is None, reason="ffmpeg is installed in the worker image"
)

ONE_FRAME_MS = 40  # a 25 fps clip


class RecordingRunner(ProcessRunner):
    """Runs everything for real, except an ffmpeg call `fail_when` picks, which exits 1."""

    def __init__(self, fail_when: Callable[[list[str]], bool] = lambda argv: False) -> None:
        super().__init__()
        self.argvs: list[list[str]] = []
        self._fail_when = fail_when

    def run(self, argv: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess[bytes]:
        self.argvs.append(argv)
        if argv[0] == "ffmpeg" and self._fail_when(argv):
            return subprocess.CompletedProcess(argv, 1, b"", b"Error: simulated muxer failure\n")
        return super().run(argv, cwd)

    @property
    def encodes(self) -> list[list[str]]:
        return [argv for argv in self.argvs if argv[0] == "ffmpeg"]


def job(**params: Any) -> MediaJob:
    return MediaJob.model_validate(job_body(params=params))


def make_clip(
    path: Path, *, seconds: int = 3, size: str = "320x180", audio: bool = True, rotation: int = 0
) -> Path:
    """A 25 fps test pattern, with an AAC sine when `audio`, tagged `rotation` degrees."""
    command = ["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i"]
    command += [f"testsrc=size={size}:rate=25:duration={seconds}"]
    if audio:
        command += ["-f", "lavfi", "-i", f"sine=duration={seconds}"]
    command += ["-c:v", "libx264", "-pix_fmt", "yuv420p"]
    if audio:
        command += ["-c:a", "aac"]
    target = path.with_name("upright") if rotation else path
    subprocess.run([*command, "-f", "mp4", str(target)], check=True)
    if rotation:
        subprocess.run(
            [
                "ffmpeg", "-v", "error", "-y", "-display_rotation", str(rotation),
                "-i", str(target), "-c", "copy", "-f", "mp4", str(path),
            ],
            check=True,
        )  # fmt: skip
    return path


def describe(path: Path) -> dict[str, Any]:
    out = subprocess.run(
        [
            "ffprobe", "-v", "error",
            "-show_entries", "stream=codec_type,codec_name,width,height,bit_rate"
            ":stream_side_data=rotation:stream_tags=rotate:format=duration",
            "-of", "json", str(path),
        ],
        check=True, capture_output=True,
    )  # fmt: skip
    report = json.loads(out.stdout)
    streams = {s["codec_type"]: s for s in report["streams"]}
    return {"streams": streams, "duration_ms": round(float(report["format"]["duration"]) * 1000)}


def refused(call: Callable[[], object]) -> PermanentError:
    with pytest.raises(PermanentError) as caught:
        call()
    return caught.value


def run_derive(video: Path, runner: ProcessRunner, **params: Any) -> Any:
    return derive.handle(job(**params), {"video": video}, runner)


@pytest.mark.parametrize(
    ("params", "field"),
    [
        ({"op": "blur"}, "op"),
        ({}, "op"),
        ({"op": 3}, "op"),
        ({"op": "cut", "srcInMs": 2000, "srcOutMs": 1000}, "srcOutMs"),
        ({"op": "cut", "srcInMs": 1000, "srcOutMs": 1000}, "srcOutMs"),
        ({"op": "cut", "srcInMs": -1}, "srcInMs"),
        ({"op": "cut", "srcInMs": 1.5}, "srcInMs"),
        ({"op": "cut", "srcOutMs": True}, "srcOutMs"),
        ({"op": "cut", "srcOutMs": None}, "srcOutMs"),
        ({"op": "cut", "keepAudio": "false"}, "keepAudio"),
        ({"op": "cut", "keepAudio": None}, "keepAudio"),
        ({"op": "cut", "opParams": []}, "opParams"),
        ({"op": "effect"}, "effect"),
        ({"op": "effect", "opParams": {"effect": "vhs"}}, "effect"),
        ({"op": "effect", "opParams": {"effect": 3}}, "effect"),
    ],
)
def test_a_bad_parameter_is_a_contract_violation_naming_it_before_any_tool_runs(
    params: dict[str, Any], field: str
) -> None:
    runner = RecordingRunner()

    error = refused(lambda: run_derive(Path("/nonexistent"), runner, **params))

    assert error.code == "contract_violation"
    assert field in error.message
    assert runner.argvs == []


def test_the_input_key_must_be_video() -> None:
    runner = RecordingRunner()

    error = refused(lambda: derive.handle(job(op="cut"), {"image": Path("/nonexistent")}, runner))

    assert error.code == "contract_violation"
    assert "video" in error.message
    assert runner.argvs == []


@needs_ffmpeg
def test_cut_keeps_one_second_from_the_middle_and_returns_empty_extras(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input")
    runner = RecordingRunner()

    outcome = run_derive(clip, runner, op="cut", srcInMs=1000, srcOutMs=2000)

    made = tmp_path / "derive.mp4"
    assert [(f.name, f.mime, f.body) for f in outcome.files] == [("derive.mp4", "video/mp4", made)]
    assert abs(outcome.data["durationMs"] - 1000) <= ONE_FRAME_MS
    assert outcome.data["extras"] == {}
    assert (outcome.data["width"], outcome.data["height"]) == (320, 180)
    assert outcome.data["hasAudio"] is True
    assert set(outcome.data) == {"width", "height", "durationMs", "hasAudio", "extras"}
    [encode] = runner.encodes
    assert encode.index("-ss") < encode.index("-i") < encode.index("-t")
    assert encode[encode.index("-ss") + 1] == "1.000"
    assert encode[encode.index("-t") + 1] == "1.000"
    assert encode[encode.index("-protocol_whitelist") + 1] == "file"
    audio_at = encode.index("-c:a")
    assert encode[audio_at : audio_at + 4] == ["-c:a", "aac", "-b:a", "128k"]
    streams = describe(made)["streams"]
    assert streams["video"]["codec_name"] == "h264"
    assert streams["audio"]["codec_name"] == "aac"


@needs_ffmpeg
def test_cut_without_a_window_covers_the_whole_clip_and_passes_no_seek_or_length(
    tmp_path: Path,
) -> None:
    clip = make_clip(tmp_path / "input")
    runner = RecordingRunner()

    outcome = run_derive(clip, runner, op="cut")

    [encode] = runner.encodes
    assert "-ss" not in encode
    assert "-t" not in encode
    assert abs(outcome.data["durationMs"] - 3000) <= 100


@needs_ffmpeg
def test_an_end_past_the_clip_is_cut_to_the_end(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input")

    outcome = run_derive(clip, RecordingRunner(), op="cut", srcInMs=2000, srcOutMs=60_000)

    assert abs(outcome.data["durationMs"] - 1000) <= 100


@needs_ffmpeg
def test_a_start_at_the_end_of_the_clip_is_a_contract_violation_naming_srcInMs(
    tmp_path: Path,
) -> None:
    clip = make_clip(tmp_path / "input")
    runner = RecordingRunner()

    error = refused(lambda: run_derive(clip, runner, op="cut", srcInMs=3000))

    assert error.code == "contract_violation"
    assert "srcInMs" in error.message
    assert runner.encodes == []


@needs_ffmpeg
def test_a_rotated_phone_take_is_baked_upright_with_no_rotation_left(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input", size="640x360", rotation=90)

    outcome = run_derive(clip, RecordingRunner(), op="cut")

    assert (outcome.data["width"], outcome.data["height"]) == (360, 640)
    video = describe(tmp_path / "derive.mp4")["streams"]["video"]
    assert (video["width"], video["height"]) == (360, 640)
    assert "side_data_list" not in video
    assert "rotate" not in video.get("tags", {})


@needs_ffmpeg
def test_keep_audio_false_leaves_no_audio_stream(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input")
    runner = RecordingRunner()

    outcome = run_derive(clip, runner, op="cut", keepAudio=False)

    assert outcome.data["hasAudio"] is False
    assert "audio" not in describe(tmp_path / "derive.mp4")["streams"]
    [encode] = runner.encodes
    assert "-an" in encode
    assert "0:a:0" not in encode


@needs_ffmpeg
def test_a_clip_without_audio_has_none_even_when_audio_is_asked_for(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input", audio=False)
    runner = RecordingRunner()

    outcome = run_derive(clip, runner, op="cut", keepAudio=True)

    assert outcome.data["hasAudio"] is False
    assert "audio" not in describe(tmp_path / "derive.mp4")["streams"]
    assert "-an" in runner.encodes[0]


@needs_ffmpeg
def test_slow_motion_fills_a_two_second_window_and_keeps_the_audio_at_source_speed(
    tmp_path: Path,
) -> None:
    clip = make_clip(tmp_path / "input", seconds=2)

    outcome = run_derive(
        clip,
        RecordingRunner(),
        op="effect",
        srcInMs=0,
        srcOutMs=2000,
        opParams={"effect": "slowmo"},
    )

    assert abs(outcome.data["durationMs"] - 2000) <= ONE_FRAME_MS
    assert outcome.data["hasAudio"] is True
    assert outcome.data["extras"] == {}
    assert "audio" in describe(tmp_path / "derive.mp4")["streams"]


@needs_ffmpeg
@pytest.mark.parametrize("name", ["flash", "zoom", "shake", "glitch", "slowmo"])
def test_every_effect_lasts_the_window_taken_from_the_middle_of_the_clip(
    tmp_path: Path, name: str
) -> None:
    clip = make_clip(tmp_path / "input")

    outcome = run_derive(
        clip,
        RecordingRunner(),
        op="effect",
        srcInMs=500,
        srcOutMs=2500,
        opParams={"effect": name},
    )

    assert abs(outcome.data["durationMs"] - 2000) <= 2 * ONE_FRAME_MS
    assert (outcome.data["width"], outcome.data["height"]) == (320, 180)
    assert outcome.data["hasAudio"] is True


@needs_ffmpeg
def test_shake_runs_the_same_filter_graph_each_time(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input", size="640x360", seconds=2)
    first, second = RecordingRunner(), RecordingRunner()

    run_derive(clip, first, op="effect", opParams={"effect": "shake"})
    run_derive(clip, second, op="effect", opParams={"effect": "shake"})

    def graph(runner: RecordingRunner) -> str:
        [encode] = runner.encodes
        return encode[encode.index("-vf") + 1]

    assert graph(first) == graph(second)
    assert "scale=664:374" in graph(first)
    assert "crop=640:360" in graph(first)


@needs_ffmpeg
def test_an_effect_on_a_clip_without_audio_encodes_with_no_audio_stream(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input", audio=False)

    outcome = run_derive(clip, RecordingRunner(), op="effect", opParams={"effect": "flash"})

    assert outcome.data["hasAudio"] is False


@needs_ffmpeg
def test_an_aac_only_input_is_probe_failed_before_any_encode(tmp_path: Path) -> None:
    sound = tmp_path / "input"
    subprocess.run(
        [
            "ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "sine=duration=1",
            "-c:a", "aac", "-f", "mp4", str(sound),
        ],
        check=True,
    )  # fmt: skip
    runner = RecordingRunner()

    error = refused(lambda: run_derive(sound, runner, op="cut"))

    assert error.code == "probe_failed"
    assert runner.encodes == []


@needs_ffmpeg
def test_random_bytes_are_probe_failed_naming_ffprobe(tmp_path: Path) -> None:
    noise = tmp_path / "input"
    noise.write_bytes(bytes(range(256)) * 16)

    error = refused(lambda: run_derive(noise, RecordingRunner(), op="cut"))

    assert error.code == "probe_failed"
    assert "ffprobe" in error.message


@needs_ffmpeg
def test_a_failed_encode_is_probe_failed_naming_ffmpeg_and_its_last_line(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input")
    runner = RecordingRunner(lambda argv: True)

    error = refused(lambda: run_derive(clip, runner, op="cut"))

    assert error.code == "probe_failed"
    assert "ffmpeg" in error.message
    assert "simulated muxer failure" in error.message
    assert len(runner.encodes) == 1


@needs_ffmpeg
def test_a_kill_during_the_encode_propagates(tmp_path: Path) -> None:
    clip = make_clip(tmp_path / "input")

    class Killed(RecordingRunner):
        def run(
            self, argv: list[str], cwd: Path | None = None
        ) -> subprocess.CompletedProcess[bytes]:
            if argv[0] == "ffmpeg":
                raise ProcessKilled("run_timeout")
            return super().run(argv, cwd)

    with pytest.raises(ProcessKilled):
        run_derive(clip, Killed(), op="cut")
