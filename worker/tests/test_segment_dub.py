"""The segment_dub handler places voiced segments at their start and reports what it dropped."""

from __future__ import annotations

import json
import shutil
import subprocess
from collections.abc import Callable, Iterator
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import pytest
from conftest import job_body

from flowcortex_worker.assembly.timeline import segment_graph
from flowcortex_worker.contracts.media_job import MediaJob
from flowcortex_worker.errors import PermanentError, TransientError
from flowcortex_worker.handlers import segment_dub
from flowcortex_worker.runtime import ProcessRunner

needs_ffmpeg = pytest.mark.skipif(
    shutil.which("ffmpeg") is None,
    reason="ffmpeg is installed in the worker image",
)

# The capability enum does not name segment_dub yet, and the handler never reads the job.
JOB = MediaJob.model_validate(job_body(capability="extract_audio"))


@dataclass
class Clip:
    seconds: float = 1.0
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

    def make(clips: dict[Path, Clip], **mix: Any) -> FakeRunner:
        runner = FakeRunner(clips, **mix)
        made.append(runner)
        return runner

    yield make
    for runner in made:
        runner.close()


def slot(index: int, start: int, end: int) -> dict[str, Any]:
    return {"index": index, "startMs": start, "endMs": end}


def ten_slots() -> list[dict[str, Any]]:
    return [slot(n, n * 2000, n * 2000 + 1500) for n in range(10)]


def staged(
    root: Path,
    manifest: Any,
    present: list[int],
    extra: tuple[str, ...] = (),
) -> dict[str, Path]:
    """The manifest and one clip per index, each in a directory of its own as the store leaves
    them; `manifest` is written as given when it is bytes."""
    keys = ["manifest", *(f"segment_{n}" for n in present), *extra]
    inputs: dict[str, Path] = {}
    for key in keys:
        directory = root / key
        directory.mkdir()
        inputs[key] = directory / "input"
        inputs[key].write_bytes(b"x")
    body = manifest if isinstance(manifest, bytes) else json.dumps(manifest).encode()
    inputs["manifest"].write_bytes(body)
    return inputs


def clips_for(inputs: dict[str, Path], **change: Clip) -> dict[Path, Clip]:
    return {path: change.get(key, Clip()) for key, path in inputs.items()}


def refused(tmp_path: Path, build: Build, manifest: Any, present: list[int]) -> PermanentError:
    inputs = staged(tmp_path, manifest, present)
    runner = build(clips_for(inputs))
    with pytest.raises(PermanentError) as failure:
        segment_dub.handle(JOB, inputs, runner)
    assert runner.calls == []
    assert failure.value.code == "contract_violation"
    return failure.value


def assert_whitelisted(argv: list[str]) -> None:
    assert argv.count("-protocol_whitelist") == argv.count("-i")


def listed(argv: list[str]) -> list[str]:
    return [argv[i + 1] for i, flag in enumerate(argv) if flag == "-i"]


# --- the manifest is checked before any process -------------------------------------------


def test_a_job_without_a_manifest_is_refused(tmp_path: Path, build: Build) -> None:
    inputs = {"segment_0": tmp_path / "segment_0"}
    inputs["segment_0"].write_bytes(b"x")
    runner = build({})

    with pytest.raises(PermanentError) as failure:
        segment_dub.handle(JOB, inputs, runner)

    assert failure.value.code == "contract_violation"
    assert "manifest" in failure.value.message
    assert runner.calls == []


@pytest.mark.parametrize("body", [b"not json", b"", b"[]", b'"segments"', b"{}", b"\xff\xfe"])
def test_a_manifest_that_is_not_the_segment_list_is_refused(
    tmp_path: Path, build: Build, body: bytes
) -> None:
    refused(tmp_path, build, body, [])


@pytest.mark.parametrize("segments", [None, "x", {"index": 0}, [], [1], [None]])
def test_segments_must_be_a_non_empty_list_of_objects(
    tmp_path: Path, build: Build, segments: Any
) -> None:
    refused(tmp_path, build, {"segments": segments}, [])


def test_more_than_500_segments_are_refused(tmp_path: Path, build: Build) -> None:
    failure = refused(
        tmp_path, build, {"segments": [slot(n, n * 10, n * 10 + 5) for n in range(501)]}, []
    )

    assert "500" in failure.message


def test_500_segments_are_accepted(tmp_path: Path, build: Build) -> None:
    segments = [slot(n, n * 10, n * 10 + 5) for n in range(500)]
    inputs = staged(tmp_path, {"segments": segments}, list(range(500)))
    runner = build(clips_for(inputs, **{f"segment_{n}": Clip(0.005) for n in range(500)}))

    outcome = segment_dub.handle(JOB, inputs, runner)

    assert outcome.data["segmentCount"] == 500


def test_a_manifest_over_the_size_limit_is_refused(tmp_path: Path, build: Build) -> None:
    body = json.dumps({"segments": [slot(0, 0, 10)], "pad": "x" * (9 * 1024 * 1024)}).encode()

    failure = refused(tmp_path, build, body, [0])

    assert "manifest" in failure.message


@pytest.mark.parametrize("index", [-1, "1", 1.0, True, None])
def test_an_index_that_is_not_a_non_negative_integer_is_refused(
    tmp_path: Path, build: Build, index: Any
) -> None:
    refused(tmp_path, build, {"segments": [slot(0, 0, 10) | {"index": index}]}, [])


def test_a_repeated_index_is_refused_by_name(tmp_path: Path, build: Build) -> None:
    failure = refused(tmp_path, build, {"segments": [slot(2, 0, 10), slot(2, 20, 30)]}, [2])

    assert "2" in failure.message


@pytest.mark.parametrize(
    ("start", "end"),
    [(5000, 4000), (5000, 5000), (-1, 10), (1.5, 10), ("0", 10), (0, 10.5), (True, 10), (0, None)],
)
def test_a_slot_that_is_not_an_ordered_pair_of_integers_names_the_index(
    tmp_path: Path, build: Build, start: Any, end: Any
) -> None:
    failure = refused(tmp_path, build, {"segments": [slot(0, 0, 10), slot(3, start, end)]}, [0, 3])

    assert "3" in failure.message


def test_a_clip_for_an_unlisted_segment_is_refused_by_name(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, {"segments": [slot(0, 0, 10), slot(1, 20, 30)]}, [0, 1, 7])
    runner = build(clips_for(inputs))

    with pytest.raises(PermanentError) as failure:
        segment_dub.handle(JOB, inputs, runner)

    assert failure.value.code == "contract_violation"
    assert "segment_7" in failure.value.message
    assert runner.calls == []


@pytest.mark.parametrize("key", ["music", "segment_01", "segment_x", "audio_1"])
def test_a_foreign_key_is_refused_by_name(tmp_path: Path, build: Build, key: str) -> None:
    inputs = staged(tmp_path, {"segments": [slot(0, 0, 10)]}, [0], extra=(key,))
    runner = build(clips_for(inputs))

    with pytest.raises(PermanentError) as failure:
        segment_dub.handle(JOB, inputs, runner)

    assert failure.value.code == "contract_violation"
    assert key in failure.value.message
    assert runner.calls == []


def test_a_manifest_from_the_voice_step_is_read_for_its_slots_only(
    tmp_path: Path, build: Build
) -> None:
    manifest = {
        "voice": "vi-female-1",
        "model": "tts-1",
        "segments": [
            {"index": 0, "startMs": 0, "endMs": 2000, "vi": "Xin chao", "extra": {"a": 1}},
            {"index": 1, "startMs": 3000, "endMs": 4000, "vi": "Tam biet"},
        ],
    }
    inputs = staged(tmp_path, manifest, [0, 1])
    runner = build(clips_for(inputs))

    outcome = segment_dub.handle(JOB, inputs, runner)

    assert outcome.data["segmentCount"] == 2


def test_the_bound_upload_and_product_images_are_ignored_and_never_probed(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(
        tmp_path, {"segments": [slot(0, 0, 2000)]}, [0], extra=("media", "image_1", "image_2")
    )
    runner = build(clips_for({"segment_0": inputs["segment_0"]}))

    outcome = segment_dub.handle(JOB, inputs, runner)

    assert outcome.data["segmentCount"] == 1
    for key in ("media", "image_1", "image_2"):
        assert str(inputs[key]) not in runner.probed()
        assert all(str(inputs[key]) not in call for call in runner.calls)


# --- dropped segments ---------------------------------------------------------------------


def test_one_missing_segment_of_ten_is_dropped_and_the_other_nine_are_placed(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, {"segments": ten_slots()}, [n for n in range(10) if n != 4])
    runner = build(clips_for(inputs))

    outcome = segment_dub.handle(JOB, inputs, runner)

    assert outcome.data["segmentCount"] == 9
    assert outcome.data["droppedCount"] == 1
    assert outcome.data["droppedSegments"] == [{"segIdx": 4, "reason": "missing"}]
    (argv,) = runner.mixes()
    assert len(listed(argv)) == 9


def test_a_clip_without_an_audio_stream_is_dropped_as_unreadable(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, {"segments": ten_slots()}, list(range(10)))
    runner = build(clips_for(inputs, segment_5=Clip(audio=False)))

    outcome = segment_dub.handle(JOB, inputs, runner)

    assert outcome.data["droppedSegments"] == [{"segIdx": 5, "reason": "unreadable"}]
    assert outcome.data["segmentCount"] == 9


def test_a_clip_that_ffprobe_cannot_read_is_dropped_and_the_rest_are_placed(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, {"segments": ten_slots()}, list(range(10)))
    err = f"{inputs['segment_3']}: Invalid data found when processing input"
    runner = build(clips_for(inputs, segment_3=Clip(probe_exit=1, probe_err=err)))

    outcome = segment_dub.handle(JOB, inputs, runner)

    assert outcome.data["droppedSegments"] == [{"segIdx": 3, "reason": "unreadable"}]
    assert outcome.data["segmentCount"] == 9
    assert str(inputs["segment_3"]) not in listed(runner.mixes()[0])


def test_a_clip_of_no_length_is_dropped_as_unreadable(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, {"segments": ten_slots()}, list(range(10)))
    runner = build(clips_for(inputs, segment_2=Clip(seconds=0.0)))

    outcome = segment_dub.handle(JOB, inputs, runner)

    assert outcome.data["droppedSegments"] == [{"segIdx": 2, "reason": "unreadable"}]


def test_a_probe_ended_by_a_foreign_signal_is_transient_not_a_drop(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, {"segments": ten_slots()}, list(range(10)))
    runner = build(clips_for(inputs, segment_6=Clip(probe_exit=-9)))

    with pytest.raises(TransientError):
        segment_dub.handle(JOB, inputs, runner)

    assert runner.mixes() == []


def test_nothing_dropped_reports_an_empty_list(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, {"segments": ten_slots()}, list(range(10)))

    outcome = segment_dub.handle(JOB, inputs, build(clips_for(inputs)))

    assert outcome.data["droppedCount"] == 0
    assert outcome.data["droppedSegments"] == []


def test_two_of_ten_dropped_is_at_the_limit_and_succeeds(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, {"segments": ten_slots()}, list(range(2, 10)))
    runner = build(clips_for(inputs))

    outcome = segment_dub.handle(JOB, inputs, runner)

    assert outcome.data["droppedCount"] == 2
    assert outcome.data["segmentCount"] == 8


def test_three_of_ten_dropped_fails_naming_the_counts_and_ships_no_file(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, {"segments": ten_slots()}, list(range(3, 10)))
    runner = build(clips_for(inputs))

    with pytest.raises(PermanentError) as failure:
        segment_dub.handle(JOB, inputs, runner)

    assert failure.value.code == "too_many_segments_dropped"
    assert "3 of 10" in failure.value.message
    assert runner.mixes() == []


def test_a_single_segment_that_is_dropped_fails(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, {"segments": [slot(0, 0, 1000)]}, [])
    runner = build({})

    with pytest.raises(PermanentError) as failure:
        segment_dub.handle(JOB, inputs, runner)

    assert failure.value.code == "too_many_segments_dropped"
    assert "1 of 1" in failure.value.message


def test_unreadable_and_missing_drops_count_together(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, {"segments": ten_slots()}, [n for n in range(10) if n != 0])
    runner = build(clips_for(inputs, segment_1=Clip(audio=False), segment_2=Clip(audio=False)))

    with pytest.raises(PermanentError) as failure:
        segment_dub.handle(JOB, inputs, runner)

    assert failure.value.code == "too_many_segments_dropped"
    assert "3 of 10" in failure.value.message


# --- the track ----------------------------------------------------------------------------


def test_a_track_over_thirty_minutes_names_the_limit_and_no_mix_runs(
    tmp_path: Path, build: Build
) -> None:
    manifest = {"segments": [slot(0, 0, 1000), slot(1, 1_790_000, 1_795_000)]}
    inputs = staged(tmp_path, manifest, [0, 1])
    runner = build(clips_for(inputs, segment_1=Clip(seconds=20.0)))

    with pytest.raises(PermanentError) as failure:
        segment_dub.handle(JOB, inputs, runner)

    assert failure.value.code == "duration_exceeded"
    assert "1800" in failure.value.message
    assert runner.mixes() == []


def test_the_last_line_longer_than_its_slot_is_not_cut(tmp_path: Path, build: Build) -> None:
    manifest = {"segments": [slot(0, 0, 2000), slot(1, 3000, 4000)]}
    inputs = staged(tmp_path, manifest, [0, 1])
    runner = build(clips_for(inputs, segment_0=Clip(1.8), segment_1=Clip(1.6)))

    outcome = segment_dub.handle(JOB, inputs, runner)

    assert outcome.data["durationMs"] == 4600
    assert outcome.data["speedAdjustments"][1] == {
        "segIdx": 1,
        "startSec": 3.0,
        "targetSec": 1.0,
        "actualSec": 1.6,
        "overlap": 0.6,
    }


def test_the_mix_places_the_clips_by_start_in_a_128k_mp3(tmp_path: Path, build: Build) -> None:
    manifest = {
        "segments": [slot(2, 6000, 7000), slot(0, 0, 2000), slot(1, 3000, 4000)],
        "voice": "x",
    }
    inputs = staged(tmp_path, manifest, [0, 1, 2])
    runner = build(clips_for(inputs))

    outcome = segment_dub.handle(JOB, inputs, runner)

    (argv,) = runner.mixes()
    assert argv[argv.index("-filter_complex") + 1] == segment_graph([0, 3000, 6000])
    assert listed(argv) == [str(inputs[f"segment_{n}"]) for n in (0, 1, 2)]
    assert argv[argv.index("-map") + 1] == "[out]"
    for flag in ("-vn", "-nostdin", "libmp3lame", "128k"):
        assert flag in argv
    for call in runner.calls:
        assert_whitelisted(call)
    (file,) = outcome.files
    assert (file.name, file.mime) == ("dub.mp3", "audio/mpeg")
    assert isinstance(file.body, Path) and file.body.is_file()
    assert [row["segIdx"] for row in outcome.data["speedAdjustments"]] == [2, 0, 1]


def test_the_manifest_and_the_unplaced_inputs_are_never_mixed(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, {"segments": ten_slots()}, [n for n in range(10) if n != 9])
    runner = build(clips_for(inputs))

    segment_dub.handle(JOB, inputs, runner)

    assert str(inputs["manifest"]) not in listed(runner.mixes()[0])


def test_a_failed_mix_ships_no_file_and_hides_the_work_dir(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, {"segments": [slot(0, 0, 1000)]}, [0])
    runner = build(
        clips_for(inputs),
        mix_exit=1,
        mix_err=f"reading {inputs['segment_0']}\nError while filtering {inputs['segment_0']}",
    )

    with pytest.raises(PermanentError) as failure:
        segment_dub.handle(JOB, inputs, runner)

    assert failure.value.code == "audio_mix_failed"
    assert "Error while filtering" in failure.value.message
    assert str(inputs["segment_0"].parent) not in failure.value.message
    assert str(runner.scratch_dir()) not in failure.value.message


def test_a_mix_that_writes_nothing_is_a_failure(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, {"segments": [slot(0, 0, 1000)]}, [0])
    runner = build(clips_for(inputs), mix_writes=False)

    with pytest.raises(PermanentError) as failure:
        segment_dub.handle(JOB, inputs, runner)

    assert failure.value.code == "audio_mix_failed"


def test_a_mix_cut_by_a_foreign_signal_is_transient(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, {"segments": [slot(0, 0, 1000)]}, [0])
    runner = build(clips_for(inputs), mix_exit=-9)

    with pytest.raises(TransientError):
        segment_dub.handle(JOB, inputs, runner)


# --- real ffmpeg --------------------------------------------------------------------------


def sine(path: Path, seconds: float, frequency: int = 440) -> Path:
    subprocess.run(
        [
            "ffmpeg", "-v", "error", "-y", "-f", "lavfi",
            "-i", f"sine=frequency={frequency}:sample_rate=24000:duration={seconds}",
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
def test_two_lines_make_a_4_6_second_stereo_44k_track(tmp_path: Path) -> None:
    manifest = tmp_path / "manifest.json"
    manifest.write_text(json.dumps({"segments": [slot(0, 0, 2000), slot(1, 3000, 4000)]}))
    runner = ProcessRunner()
    try:
        outcome = segment_dub.handle(
            JOB,
            {
                "manifest": manifest,
                "segment_0": sine(tmp_path / "first.wav", 1.8),
                "segment_1": sine(tmp_path / "second.wav", 1.6, 880),
            },
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

    assert length == pytest.approx(4.6, abs=0.05)
    assert shape == "44100,2"
