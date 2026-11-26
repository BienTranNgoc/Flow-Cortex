"""The concat_audio handler joins numbered audio inputs into one MP3."""

from __future__ import annotations

import shutil
import subprocess
from collections.abc import Callable, Iterator
from dataclasses import dataclass
from pathlib import Path

import pytest
from conftest import job_body

from flowcortex_worker.contracts.media_job import MediaJob
from flowcortex_worker.errors import PermanentError, TransientError
from flowcortex_worker.handlers import concat_audio
from flowcortex_worker.runtime import ProcessRunner

needs_ffmpeg = pytest.mark.skipif(
    shutil.which("ffmpeg") is None,
    reason="ffmpeg is installed in the worker image",
)

# The capability enum does not name concat_audio yet, and the handler never reads the job.
JOB = MediaJob.model_validate(job_body(capability="extract_audio"))


@dataclass
class Clip:
    seconds: float = 2.0
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
            if "-select_streams" in argv:
                return subprocess.CompletedProcess(argv, 0, b"0\n" if clip.audio else b"", b"")
            return subprocess.CompletedProcess(argv, 0, f"{clip.seconds}\n".encode(), b"")
        exit_code, err, writes = self._mix
        if exit_code == 0 and writes:
            Path(argv[-1]).write_bytes(b"mp3")
        return subprocess.CompletedProcess(argv, exit_code, b"", err.encode())

    def mixes(self) -> list[list[str]]:
        return [argv for argv in self.calls if argv[0] == "ffmpeg"]

    def probed(self) -> list[str]:
        return [argv[argv.index("-i") + 1] for argv in self.calls if argv[0] == "ffprobe"]


Build = Callable[..., FakeRunner]


@pytest.fixture
def build() -> Iterator[Build]:
    made: list[FakeRunner] = []

    def make(
        clips: dict[Path, Clip],
        mix_exit: int = 0,
        mix_err: str = "",
        mix_writes: bool = True,
    ) -> FakeRunner:
        runner = FakeRunner(clips, mix_exit, mix_err, mix_writes)
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


def numbered_keys(count: int) -> list[str]:
    return [f"audio_{n}" for n in range(1, count + 1)]


def clips_for(inputs: dict[str, Path], **change: Clip) -> dict[Path, Clip]:
    return {path: change.get(key, Clip()) for key, path in inputs.items()}


def assert_whitelisted(argv: list[str]) -> None:
    assert argv.count("-protocol_whitelist") == argv.count("-i")


# --- shape errors come before any process -------------------------------------------------


def test_one_file_is_not_a_join(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, "audio_1")
    runner = build(clips_for(inputs))

    with pytest.raises(PermanentError) as failure:
        concat_audio.handle(JOB, inputs, runner)

    assert failure.value.code == "contract_violation"
    assert "2 to 50" in failure.value.message
    assert runner.calls == []


# --- probes -------------------------------------------------------------------------------


def test_an_input_without_audio_is_named_and_no_mix_runs(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, "audio_1", "audio_2")
    runner = build(clips_for(inputs, audio_2=Clip(audio=False)))

    with pytest.raises(PermanentError) as failure:
        concat_audio.handle(JOB, inputs, runner)

    assert failure.value.code == "no_audio_stream"
    assert "audio_2" in failure.value.message
    assert runner.mixes() == []


def test_a_corrupt_input_is_an_extraction_failure_without_a_local_path(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, "audio_1", "audio_2")
    err = f"{inputs['audio_2']}: Invalid data found when processing input"
    runner = build(clips_for(inputs, audio_2=Clip(probe_exit=1, probe_err=err)))

    with pytest.raises(PermanentError) as failure:
        concat_audio.handle(JOB, inputs, runner)

    assert failure.value.code == "audio_extraction_failed"
    assert "audio_2" in failure.value.message
    assert str(tmp_path) not in failure.value.message
    assert runner.mixes() == []


def test_a_probe_ended_by_a_foreign_signal_is_transient(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, "audio_1", "audio_2")
    runner = build(clips_for(inputs, audio_1=Clip(probe_exit=-9)))

    with pytest.raises(TransientError):
        concat_audio.handle(JOB, inputs, runner)


def test_a_join_over_thirty_minutes_is_refused_before_the_mix(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, *numbered_keys(3))
    runner = build({path: Clip(seconds=700.0) for path in inputs.values()})

    with pytest.raises(PermanentError) as failure:
        concat_audio.handle(JOB, inputs, runner)

    assert failure.value.code == "duration_exceeded"
    assert "1800" in failure.value.message
    assert runner.mixes() == []


def test_the_bound_upload_is_ignored_and_never_probed(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, "media", "audio_1", "audio_2")
    runner = build(
        clips_for({key: path for key, path in inputs.items() if key != "media"}),
    )

    outcome = concat_audio.handle(JOB, inputs, runner)

    assert outcome.data["segmentCount"] == 2
    assert str(inputs["media"]) not in runner.probed()
    assert str(inputs["media"]) not in runner.mixes()[0]


# --- the mix ------------------------------------------------------------------------------


def test_the_mix_joins_every_input_with_the_concat_filter_into_a_192k_mp3(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, "audio_1", "audio_2")
    runner = build(clips_for(inputs))

    concat_audio.handle(JOB, inputs, runner)

    (argv,) = runner.mixes()
    assert argv[argv.index("-filter_complex") + 1] == "[0:a][1:a]concat=n=2:v=0:a=1[out]"
    assert argv[argv.index("-map") + 1] == "[out]"
    for flag in ("-vn", "-nostdin", "libmp3lame", "192k"):
        assert flag in argv
    assert [argv[i + 1] for i, flag in enumerate(argv) if flag == "-i"] == [
        str(inputs["audio_1"]),
        str(inputs["audio_2"]),
    ]
    for call in runner.calls:
        assert_whitelisted(call)


def test_inputs_are_joined_in_numeric_order_not_key_order(tmp_path: Path, build: Build) -> None:
    keys = numbered_keys(10)
    inputs = staged(tmp_path, *keys)
    runner = build(clips_for(inputs))

    concat_audio.handle(JOB, inputs, runner)

    (argv,) = runner.mixes()
    joined = [argv[i + 1] for i, flag in enumerate(argv) if flag == "-i"]
    assert joined == [str(inputs[key]) for key in keys]
    assert "concat=n=10:v=0:a=1[out]" in argv[argv.index("-filter_complex") + 1]


def test_a_failed_mix_ships_no_file_and_hides_the_work_dir(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, "audio_1", "audio_2")
    runner = build(
        clips_for(inputs),
        mix_exit=1,
        mix_err=f"reading {inputs['audio_1']}\nError while filtering {inputs['audio_1']}",
    )

    with pytest.raises(PermanentError) as failure:
        concat_audio.handle(JOB, inputs, runner)

    assert failure.value.code == "audio_mix_failed"
    assert "Error while filtering" in failure.value.message
    assert str(inputs["audio_1"].parent) not in failure.value.message
    assert str(runner.scratch_dir()) not in failure.value.message


def test_a_mix_that_writes_nothing_is_a_failure(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, "audio_1", "audio_2")
    runner = build(clips_for(inputs), mix_writes=False)

    with pytest.raises(PermanentError) as failure:
        concat_audio.handle(JOB, inputs, runner)

    assert failure.value.code == "audio_mix_failed"


def test_a_mix_cut_by_a_foreign_signal_is_transient(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, "audio_1", "audio_2")
    runner = build(clips_for(inputs), mix_exit=-9)

    with pytest.raises(TransientError):
        concat_audio.handle(JOB, inputs, runner)


def test_success_returns_one_mp3_and_the_summed_length(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, "audio_1", "audio_2")
    runner = build(clips_for(inputs, audio_1=Clip(seconds=3.0), audio_2=Clip(seconds=2.0)))

    outcome = concat_audio.handle(JOB, inputs, runner)

    assert outcome.data == {"segmentCount": 2, "durationMs": 5000}
    (file,) = outcome.files
    assert (file.name, file.mime) == ("audio.mp3", "audio/mpeg")
    assert isinstance(file.body, Path) and file.body.is_file()


def test_the_length_is_rounded_up_to_a_whole_millisecond(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, "audio_1", "audio_2")
    runner = build(clips_for(inputs, audio_1=Clip(seconds=1.0004), audio_2=Clip(seconds=2.1)))

    outcome = concat_audio.handle(JOB, inputs, runner)

    assert outcome.data["durationMs"] == 3101


# --- real ffmpeg --------------------------------------------------------------------------


def lavfi(path: Path, rate: int, channels: int, seconds: float, codec: str) -> Path:
    subprocess.run(
        [
            "ffmpeg", "-v", "error", "-y",
            "-f", "lavfi", "-i", f"sine=frequency=440:sample_rate={rate}:duration={seconds}",
            "-ac", str(channels), "-c:a", codec, str(path),
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
def test_two_formats_join_into_one_five_second_stream(tmp_path: Path) -> None:
    first = lavfi(tmp_path / "first.wav", 44100, 2, 3.0, "pcm_s16le")
    second = lavfi(tmp_path / "second.m4a", 24000, 1, 2.0, "aac")
    runner = ProcessRunner()
    try:
        outcome = concat_audio.handle(JOB, {"audio_1": first, "audio_2": second}, runner)
        (file,) = outcome.files
        assert isinstance(file.body, Path)

        length = float(ffprobe(file.body, "-show_entries", "format=duration"))
        streams = ffprobe(file.body, "-show_entries", "stream=codec_type").splitlines()
    finally:
        runner.close()

    assert length == pytest.approx(5.0, abs=0.1)
    assert streams == ["audio"]
    assert outcome.data["segmentCount"] == 2
    assert outcome.data["durationMs"] == pytest.approx(5000, abs=100)
