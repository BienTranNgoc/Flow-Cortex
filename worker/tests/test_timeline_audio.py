"""The timeline_audio handler lays voice clips on one track of an exact length."""

from __future__ import annotations

import shutil
import subprocess
from collections.abc import Callable, Iterator
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import pytest
from conftest import job_body

from flowcortex_worker.assembly.timeline import timeline_graph
from flowcortex_worker.contracts.media_job import MediaJob
from flowcortex_worker.errors import PermanentError, TransientError
from flowcortex_worker.handlers import _assembly_io, timeline_audio
from flowcortex_worker.runtime import ProcessRunner

needs_ffmpeg = pytest.mark.skipif(
    shutil.which("ffmpeg") is None,
    reason="ffmpeg is installed in the worker image",
)


def job_with(params: dict[str, Any]) -> MediaJob:
    # The capability enum does not name timeline_audio yet, and the handler reads only params.
    return MediaJob.model_validate(job_body(capability="extract_audio", params=params))


@dataclass
class Clip:
    audio: bool = True
    probe_exit: int = 0
    probe_err: str = ""


class FakeRunner(ProcessRunner):
    """Scripts ffprobe from per-file `Clip`s and records every argv; ffmpeg writes its output."""

    def __init__(
        self,
        clips: dict[Path, Clip],
        mix_exit: int = 0,
        mix_err: str = "",
        mix_writes: bool = True,
    ) -> None:
        super().__init__()
        self.calls: list[list[str]] = []
        self._clips = clips
        self._mix = (mix_exit, mix_err, mix_writes)

    def run(self, argv: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess[bytes]:
        self.calls.append(list(argv))
        if argv[0] == "ffprobe":
            clip = self._clips[Path(argv[argv.index("-i") + 1])]
            if clip.probe_exit != 0:
                return subprocess.CompletedProcess(
                    argv, clip.probe_exit, b"", clip.probe_err.encode()
                )
            return subprocess.CompletedProcess(argv, 0, b"0\n" if clip.audio else b"", b"")
        exit_code, err, writes = self._mix
        if exit_code == 0 and writes:
            Path(argv[-1]).write_bytes(b"mp3")
        return subprocess.CompletedProcess(argv, exit_code, b"", err.encode())

    def mixes(self) -> list[list[str]]:
        return [argv for argv in self.calls if argv[0] == "ffmpeg"]


Build = Callable[..., FakeRunner]


@pytest.fixture
def build() -> Iterator[Build]:
    made: list[FakeRunner] = []

    def make(clips: dict[Path, Clip], **mix: Any) -> FakeRunner:
        runner = FakeRunner(clips, **mix)
        made.append(runner)
        return runner

    yield make
    for runner in made:
        runner.close()


def staged(root: Path, *keys: str) -> dict[str, Path]:
    """Each input in a directory of its own, as the store download leaves them."""
    inputs: dict[str, Path] = {}
    for key in keys:
        directory = root / key
        directory.mkdir()
        inputs[key] = directory / "input"
        inputs[key].write_bytes(b"x")
    return inputs


def clips_for(inputs: dict[str, Path], **change: Clip) -> dict[Path, Clip]:
    return {path: change.get(key, Clip()) for key, path in inputs.items()}


def place(name: str, start: float, **more: Any) -> dict[str, Any]:
    return {"input": name, "start": start, **more}


def params(*clips: dict[str, Any], track: Any = 10) -> dict[str, Any]:
    return {"trackSec": track, "clips": list(clips)}


def refused(tmp_path: Path, build: Build, keys: list[str], given: dict[str, Any]) -> PermanentError:
    inputs = staged(tmp_path, *keys)
    runner = build(clips_for(inputs))
    with pytest.raises(PermanentError) as failure:
        timeline_audio.handle(job_with(given), inputs, runner)
    assert runner.calls == []
    assert failure.value.code == "contract_violation"
    return failure.value


def assert_whitelisted(argv: list[str]) -> None:
    assert argv.count("-protocol_whitelist") == argv.count("-i")


# --- the job's shape is checked before any process ----------------------------------------


@pytest.mark.parametrize("track", [0, -1, 1801, "10", True, None, float("nan")])
def test_a_track_length_outside_zero_to_1800_is_refused(
    tmp_path: Path, build: Build, track: Any
) -> None:
    failure = refused(tmp_path, build, ["clip_1"], params(place("clip_1", 0), track=track))

    assert "trackSec" in failure.message


def test_a_track_over_the_limit_names_the_limit(tmp_path: Path, build: Build) -> None:
    failure = refused(tmp_path, build, ["clip_1"], params(place("clip_1", 0), track=1801))

    assert "1800" in failure.message


@pytest.mark.parametrize("clips", [None, [], "clip_1", {"input": "clip_1", "start": 0}])
def test_missing_or_empty_clips_are_refused(tmp_path: Path, build: Build, clips: Any) -> None:
    refused(tmp_path, build, ["clip_1"], {"trackSec": 10, "clips": clips})


def test_a_clip_that_is_not_an_object_is_refused(tmp_path: Path, build: Build) -> None:
    refused(tmp_path, build, ["clip_1"], {"trackSec": 10, "clips": ["clip_1"]})


def test_a_clip_naming_an_absent_input_is_refused_by_name(tmp_path: Path, build: Build) -> None:
    failure = refused(tmp_path, build, ["clip_1"], params(place("clip_1", 0), place("clip_9", 1)))

    assert "clip_9" in failure.message


def test_a_clip_naming_the_bound_upload_is_refused(tmp_path: Path, build: Build) -> None:
    failure = refused(
        tmp_path, build, ["media", "clip_1"], params(place("clip_1", 0), place("media", 1))
    )

    assert "media" in failure.message


@pytest.mark.parametrize("named", [None, 1, ""])
def test_a_clip_without_an_input_name_is_refused(tmp_path: Path, build: Build, named: Any) -> None:
    refused(tmp_path, build, ["clip_1"], params({"input": named, "start": 0}))


def test_an_input_named_twice_is_refused(tmp_path: Path, build: Build) -> None:
    failure = refused(tmp_path, build, ["clip_1"], params(place("clip_1", 0), place("clip_1", 5)))

    assert "clip_1" in failure.message


def test_an_input_placed_by_nobody_is_refused_by_name(tmp_path: Path, build: Build) -> None:
    failure = refused(tmp_path, build, ["clip_1", "clip_2"], params(place("clip_1", 0)))

    assert "clip_2" in failure.message


def test_a_start_at_the_end_of_the_track_names_the_clip(tmp_path: Path, build: Build) -> None:
    failure = refused(
        tmp_path, build, ["clip_1", "clip_2"], params(place("clip_1", 0), place("clip_2", 10))
    )

    assert "clip_2" in failure.message


@pytest.mark.parametrize("start", [-1, "3", None, True, float("inf")])
def test_a_start_that_is_not_inside_the_track_is_refused(
    tmp_path: Path, build: Build, start: Any
) -> None:
    refused(tmp_path, build, ["clip_1"], params({"input": "clip_1", "start": start}))


def test_a_clip_without_a_start_is_refused(tmp_path: Path, build: Build) -> None:
    refused(tmp_path, build, ["clip_1"], params({"input": "clip_1"}))


@pytest.mark.parametrize("speed", [2.5, 0.5, 0, -1, "2", True, float("nan")])
def test_a_speed_up_outside_one_to_two_is_refused(tmp_path: Path, build: Build, speed: Any) -> None:
    failure = refused(tmp_path, build, ["clip_1"], params(place("clip_1", 0, speedUp=speed)))

    assert "clip_1" in failure.message


def test_a_foreign_input_key_is_refused_by_name(tmp_path: Path, build: Build) -> None:
    failure = refused(tmp_path, build, ["clip_1", "music"], params(place("clip_1", 0)))

    assert "music" in failure.message


def test_a_gap_in_the_numbering_is_refused_by_name(tmp_path: Path, build: Build) -> None:
    failure = refused(
        tmp_path, build, ["clip_1", "clip_3"], params(place("clip_1", 0), place("clip_3", 2))
    )

    assert "clip_2" in failure.message


def test_a_job_without_clips_is_refused_by_the_bounds(tmp_path: Path, build: Build) -> None:
    failure = refused(tmp_path, build, ["media"], params(place("clip_1", 0)))

    assert "1 to 200" in failure.message


def test_a_track_at_the_limit_is_built(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, "clip_1")
    runner = build(clips_for(inputs))

    outcome = timeline_audio.handle(
        job_with(params(place("clip_1", 0), track=1800)), inputs, runner
    )

    assert outcome.data == {"clipCount": 1, "durationMs": 1800000}


def test_a_speed_up_of_exactly_one_and_two_is_accepted(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, "clip_1", "clip_2")
    runner = build(clips_for(inputs))

    timeline_audio.handle(
        job_with(params(place("clip_1", 0, speedUp=1), place("clip_2", 5, speedUp=2))),
        inputs,
        runner,
    )

    graph = runner.mixes()[0][runner.mixes()[0].index("-filter_complex") + 1]
    assert "atempo=2.000" in graph
    assert graph.count("atempo") == 1


# --- probes -------------------------------------------------------------------------------


def test_a_clip_without_audio_is_named_and_no_mix_runs(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, "clip_1", "clip_2")
    runner = build(clips_for(inputs, clip_2=Clip(audio=False)))

    with pytest.raises(PermanentError) as failure:
        timeline_audio.handle(
            job_with(params(place("clip_1", 0), place("clip_2", 5))), inputs, runner
        )

    assert failure.value.code == "no_audio_stream"
    assert "clip_2" in failure.value.message
    assert runner.mixes() == []


def test_a_corrupt_clip_is_an_extraction_failure_without_a_local_path(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, "clip_1", "clip_2", "clip_3")
    err = f"{inputs['clip_3']}: Invalid data found when processing input"
    runner = build(clips_for(inputs, clip_3=Clip(probe_exit=1, probe_err=err)))

    with pytest.raises(PermanentError) as failure:
        timeline_audio.handle(
            job_with(params(place("clip_1", 0), place("clip_2", 3), place("clip_3", 6))),
            inputs,
            runner,
        )

    assert failure.value.code == "audio_extraction_failed"
    assert "clip_3" in failure.value.message
    assert str(tmp_path) not in failure.value.message
    assert runner.mixes() == []


def test_a_probe_ended_by_a_foreign_signal_is_transient(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, "clip_1")
    runner = build(clips_for(inputs, clip_1=Clip(probe_exit=-9)))

    with pytest.raises(TransientError):
        timeline_audio.handle(job_with(params(place("clip_1", 0))), inputs, runner)


def test_the_bound_upload_is_ignored_and_never_probed(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, "media", "clip_1")
    runner = build(clips_for({"clip_1": inputs["clip_1"]}))

    outcome = timeline_audio.handle(job_with(params(place("clip_1", 1))), inputs, runner)

    assert outcome.data["clipCount"] == 1
    assert all(str(inputs["media"]) not in call for call in runner.calls)


# --- the mix ------------------------------------------------------------------------------


def test_the_mix_uses_the_timeline_graph_and_writes_a_192k_mp3(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, "clip_1", "clip_2")
    runner = build(clips_for(inputs))

    outcome = timeline_audio.handle(
        job_with(params(place("clip_1", 1.0), place("clip_2", 6.25))), inputs, runner
    )

    (argv,) = runner.mixes()
    assert argv[argv.index("-filter_complex") + 1] == timeline_graph([1.0, 6.25], 10.0)
    assert argv[argv.index("-map") + 1] == "[out]"
    for flag in ("-vn", "-nostdin", "libmp3lame", "192k"):
        assert flag in argv
    assert [argv[i + 1] for i, flag in enumerate(argv) if flag == "-i"] == [
        str(inputs["clip_1"]),
        str(inputs["clip_2"]),
    ]
    for call in runner.calls:
        assert_whitelisted(call)
    (file,) = outcome.files
    assert (file.name, file.mime) == ("timeline.mp3", "audio/mpeg")
    assert isinstance(file.body, Path) and file.body.is_file()
    assert outcome.data == {"clipCount": 2, "durationMs": 10000}


def test_starts_and_speed_ups_follow_the_input_number_not_the_listed_order(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, "clip_1", "clip_2")
    runner = build(clips_for(inputs))

    timeline_audio.handle(
        job_with(params(place("clip_2", 8.0, speedUp=1.25), place("clip_1", 1.0))),
        inputs,
        runner,
    )

    (argv,) = runner.mixes()
    assert argv[argv.index("-filter_complex") + 1] == timeline_graph([1.0, 8.0], 10.0, [1.0, 1.25])
    assert [argv[i + 1] for i, flag in enumerate(argv) if flag == "-i"] == [
        str(inputs["clip_1"]),
        str(inputs["clip_2"]),
    ]


def test_ten_clips_are_ordered_by_number_not_by_key_text(tmp_path: Path, build: Build) -> None:
    keys = [f"clip_{n}" for n in range(1, 11)]
    inputs = staged(tmp_path, *keys)
    runner = build(clips_for(inputs))

    timeline_audio.handle(
        job_with(params(*(place(key, float(n)) for n, key in enumerate(keys)), track=20)),
        inputs,
        runner,
    )

    (argv,) = runner.mixes()
    assert [argv[i + 1] for i, flag in enumerate(argv) if flag == "-i"] == [
        str(inputs[key]) for key in keys
    ]


def test_a_failed_mix_ships_no_file_and_hides_the_work_dir(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, "clip_1", "clip_2")
    runner = build(
        clips_for(inputs),
        mix_exit=1,
        mix_err=f"reading {inputs['clip_1']}\nError while filtering {inputs['clip_1']}",
    )

    with pytest.raises(PermanentError) as failure:
        timeline_audio.handle(
            job_with(params(place("clip_1", 0), place("clip_2", 5))), inputs, runner
        )

    assert failure.value.code == "audio_mix_failed"
    assert "Error while filtering" in failure.value.message
    assert str(inputs["clip_1"].parent) not in failure.value.message
    assert str(runner.scratch_dir()) not in failure.value.message


def test_a_mix_that_writes_nothing_is_a_failure(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, "clip_1")
    runner = build(clips_for(inputs), mix_writes=False)

    with pytest.raises(PermanentError) as failure:
        timeline_audio.handle(job_with(params(place("clip_1", 0))), inputs, runner)

    assert failure.value.code == "audio_mix_failed"


def test_a_mix_cut_by_a_foreign_signal_is_transient(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, "clip_1")
    runner = build(clips_for(inputs), mix_exit=-9)

    with pytest.raises(TransientError):
        timeline_audio.handle(job_with(params(place("clip_1", 0))), inputs, runner)


def test_the_length_is_the_track_length_in_whole_milliseconds(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, "clip_1")
    runner = build(clips_for(inputs))

    outcome = timeline_audio.handle(
        job_with(params(place("clip_1", 0), track=2.0006)), inputs, runner
    )

    assert outcome.data["durationMs"] == 2001


# --- real ffmpeg --------------------------------------------------------------------------


def sine(path: Path, seconds: float, frequency: int = 440, rate: int = 24000) -> Path:
    subprocess.run(
        [
            "ffmpeg", "-v", "error", "-y",
            "-f", "lavfi",
            "-i", f"sine=frequency={frequency}:sample_rate={rate}:duration={seconds}",
            "-ac", "1", "-c:a", "pcm_s16le", str(path),
        ],
        check=True,
    )  # fmt: skip
    return path


def ffprobe(path: Path, *args: str) -> str:
    completed = subprocess.run(
        ["ffprobe", "-v", "error", *args, "-of", "csv=p=0", str(path)],
        check=True,
        capture_output=True,
        text=True,
    )
    return completed.stdout.strip()


@needs_ffmpeg
def test_two_lines_make_a_ten_second_mono_track(tmp_path: Path) -> None:
    first = sine(tmp_path / "first.wav", 2.0)
    second = sine(tmp_path / "second.wav", 1.5, 880)
    runner = ProcessRunner()
    try:
        outcome = timeline_audio.handle(
            job_with(params(place("clip_1", 1.0), place("clip_2", 6.25))),
            {"clip_1": first, "clip_2": second},
            runner,
        )
        (file,) = outcome.files
        assert isinstance(file.body, Path)

        length = float(ffprobe(file.body, "-show_entries", "format=duration"))
        shape = ffprobe(
            file.body, "-select_streams", "a:0", "-show_entries", "stream=sample_rate,channels"
        )
    finally:
        runner.close()

    assert length == pytest.approx(10.0, abs=0.05)
    assert shape == "48000,1"


@needs_ffmpeg
def test_a_single_clip_keeps_its_loudness_in_the_track(tmp_path: Path) -> None:
    # At the track's own rate and long enough that the silence around the clip does not skew the
    # gated measure: ebur128 reads the same sine 0.1 LU lower after a 24 kHz to 48 kHz resample.
    clip = sine(tmp_path / "clip.wav", 6.0, rate=48000)
    runner = ProcessRunner()
    try:
        outcome = timeline_audio.handle(
            job_with(params(place("clip_1", 1.0))), {"clip_1": clip}, runner
        )
        (file,) = outcome.files
        assert isinstance(file.body, Path)

        before = _assembly_io.loudness(clip, "a:0", runner, "audio_mix_failed")
        after = _assembly_io.loudness(file.body, "a:0", runner, "audio_mix_failed")
    finally:
        runner.close()

    assert before is not None and after is not None
    assert after == pytest.approx(before, abs=0.5)
