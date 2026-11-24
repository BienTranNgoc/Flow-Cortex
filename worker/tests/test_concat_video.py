"""The concat_video handler normalises numbered clips and joins them into one MP4."""

from __future__ import annotations

import shutil
import subprocess
from collections.abc import Callable, Iterator
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import pytest
from conftest import job_body

from flowcortex_worker.contracts.media_job import MediaJob
from flowcortex_worker.errors import PermanentError, TransientError
from flowcortex_worker.handlers import concat_video
from flowcortex_worker.runtime import ProcessRunner

needs_ffmpeg = pytest.mark.skipif(
    shutil.which("ffmpeg") is None,
    reason="ffmpeg is installed in the worker image",
)

# The capability enum does not name concat_video yet, and the handler reads only params.
VF_9_16 = (
    "scale=720:1280:force_original_aspect_ratio=decrease:out_color_matrix=bt709:out_range=tv,"
    "pad=720:1280:(ow-iw)/2:(oh-ih)/2:black,setsar=1,format=yuv420p"
)


def job_with(**params: Any) -> MediaJob:
    return MediaJob.model_validate(job_body(capability="extract_audio", params=params))


@dataclass
class Clip:
    seconds: float = 6.0
    video: bool = True
    audio: bool = True
    probe_exit: int = 0
    probe_err: str = ""


class FakeRunner(ProcessRunner):
    """Scripts ffprobe from per-file `Clip`s, records every argv, and lets each ffmpeg stage write
    its output unless `failures` names it by file name."""

    def __init__(
        self,
        clips: dict[Path, Clip],
        failures: dict[str, tuple[int, str]] | None = None,
        silent: frozenset[str] = frozenset(),
        final_seconds: float | None = None,
    ) -> None:
        super().__init__()
        self.calls: list[list[str]] = []
        self._clips = clips
        self._failures = failures or {}
        self._silent = silent
        self._final_seconds = final_seconds

    def run(self, argv: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess[bytes]:
        self.calls.append(list(argv))
        if argv[0] == "ffprobe":
            return self._probe(argv)
        name = Path(argv[-1]).name
        if name in self._failures:
            code, err = self._failures[name]
            return subprocess.CompletedProcess(argv, code, b"", err.encode())
        if name not in self._silent:
            Path(argv[-1]).write_bytes(b"mp4")
        return subprocess.CompletedProcess(argv, 0, b"", b"")

    def _probe(self, argv: list[str]) -> subprocess.CompletedProcess[bytes]:
        path = Path(argv[argv.index("-i") + 1])
        clip = self._clips.get(path)
        if clip is None:
            seconds = sum(c.seconds for k, c in self._clips.items() if k.parent.name != "narration")
            if path.name == "video.mp4" and self._final_seconds is not None:
                seconds = self._final_seconds
            return subprocess.CompletedProcess(argv, 0, f"{seconds}\n".encode(), b"")
        if clip.probe_exit != 0:
            return subprocess.CompletedProcess(argv, clip.probe_exit, b"", clip.probe_err.encode())
        if "-select_streams" in argv:
            present = clip.video if "v:0" in argv else clip.audio
            row = (b"0,0\n" if "v:0" in argv else b"0\n") if present else b""
            return subprocess.CompletedProcess(argv, 0, row, b"")
        return subprocess.CompletedProcess(argv, 0, f"{clip.seconds}\n".encode(), b"")

    def stages(self) -> list[list[str]]:
        return [argv for argv in self.calls if argv[0] == "ffmpeg"]

    def normalised(self) -> list[list[str]]:
        return [a for a in self.stages() if Path(a[-1]).name.startswith("norm_")]

    def joins(self) -> list[list[str]]:
        return [a for a in self.stages() if "concat" in a]

    def overlays(self) -> list[list[str]]:
        return [a for a in self.stages() if Path(a[-1]).name == "video.mp4" and "concat" not in a]

    def probed(self) -> list[str]:
        return [argv[argv.index("-i") + 1] for argv in self.calls if argv[0] == "ffprobe"]


Build = Callable[..., FakeRunner]


@pytest.fixture
def build() -> Iterator[Build]:
    made: list[FakeRunner] = []

    def make(clips: dict[Path, Clip], **script: Any) -> FakeRunner:
        runner = FakeRunner(clips, **script)
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


def clip_keys(count: int) -> list[str]:
    return [f"clip_{n}" for n in range(1, count + 1)]


def clips_for(inputs: dict[str, Path], **change: Clip) -> dict[Path, Clip]:
    return {path: change.get(key, Clip()) for key, path in inputs.items()}


def assert_whitelisted(argv: list[str]) -> None:
    assert argv.count("-protocol_whitelist") == argv.count("-i")


def listed(argv: list[str]) -> list[str]:
    return [argv[i + 1] for i, flag in enumerate(argv) if flag == "-i"]


def flag_value(argv: list[str], flag: str) -> str:
    return argv[argv.index(flag) + 1]


def refused(
    tmp_path: Path, build: Build, keys: list[str], code: str = "contract_violation", **params: Any
) -> PermanentError:
    inputs = staged(tmp_path, *keys)
    runner = build(clips_for(inputs))
    with pytest.raises(PermanentError) as failure:
        concat_video.handle(job_with(**params), inputs, runner)
    assert runner.calls == []
    assert failure.value.code == code
    return failure.value


# --- the job's shape is checked before any process ----------------------------------------


def test_one_clip_is_not_a_join(tmp_path: Path, build: Build) -> None:
    failure = refused(tmp_path, build, ["clip_1"])

    assert "2 to 20" in failure.message


def test_twenty_one_clips_are_refused_by_the_bounds(tmp_path: Path, build: Build) -> None:
    failure = refused(tmp_path, build, clip_keys(21))

    assert "2 to 20" in failure.message


def test_a_foreign_key_is_refused_by_name(tmp_path: Path, build: Build) -> None:
    failure = refused(tmp_path, build, ["media", "clip_1", "clip_2", "music"])

    assert "music" in failure.message


def test_a_misspelt_narration_is_refused_by_name(tmp_path: Path, build: Build) -> None:
    failure = refused(tmp_path, build, ["clip_1", "clip_2", "narraton"])

    assert "narraton" in failure.message


def test_a_gap_in_the_numbering_is_refused_by_name(tmp_path: Path, build: Build) -> None:
    failure = refused(tmp_path, build, ["clip_1", "clip_3"])

    assert "clip_2" in failure.message


@pytest.mark.parametrize("ratio", ["4:3", "16x9", "", 1, True])
def test_an_unknown_aspect_ratio_is_refused_by_name(
    tmp_path: Path, build: Build, ratio: Any
) -> None:
    failure = refused(tmp_path, build, clip_keys(2), aspectRatio=ratio)

    assert "aspectRatio" in failure.message


@pytest.mark.parametrize("value", ["true", "false", 1, 0, []])
def test_a_drop_audio_flag_that_is_not_a_boolean_is_refused_by_name(
    tmp_path: Path, build: Build, value: Any
) -> None:
    failure = refused(tmp_path, build, clip_keys(2), dropAudio=value)

    assert "dropAudio" in failure.message


def test_an_unknown_duration_policy_is_refused_by_name(tmp_path: Path, build: Build) -> None:
    failure = refused(tmp_path, build, clip_keys(2), durationPolicy="stretch")

    assert "durationPolicy" in failure.message


# --- probes -------------------------------------------------------------------------------


def test_the_bound_upload_is_ignored_and_never_probed(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, "media", "clip_1", "clip_2")
    runner = build(clips_for({k: v for k, v in inputs.items() if k != "media"}))

    outcome = concat_video.handle(job_with(), inputs, runner)

    assert outcome.data["clipCount"] == 2
    assert str(inputs["media"]) not in runner.probed()
    assert all(str(inputs["media"]) not in call for call in runner.calls)


def test_a_clip_ffprobe_cannot_read_is_rejected_by_name_and_nothing_is_normalised(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, *clip_keys(3))
    err = f"{inputs['clip_2']}: Invalid data found when processing input"
    runner = build(clips_for(inputs, clip_2=Clip(probe_exit=1, probe_err=err)))

    with pytest.raises(PermanentError) as failure:
        concat_video.handle(job_with(), inputs, runner)

    assert failure.value.code == "input_rejected"
    assert "clip_2" in failure.value.message
    assert str(tmp_path) not in failure.value.message
    assert runner.normalised() == []


def test_an_audio_file_given_as_a_clip_is_rejected_by_name(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, *clip_keys(2))
    runner = build(clips_for(inputs, clip_2=Clip(video=False)))

    with pytest.raises(PermanentError) as failure:
        concat_video.handle(job_with(), inputs, runner)

    assert failure.value.code == "input_rejected"
    assert "clip_2" in failure.value.message
    assert runner.normalised() == []


def test_a_probe_ended_by_a_foreign_signal_is_transient(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, *clip_keys(2))
    runner = build(clips_for(inputs, clip_1=Clip(probe_exit=-9)))

    with pytest.raises(TransientError):
        concat_video.handle(job_with(), inputs, runner)


def test_twenty_clips_of_31_seconds_name_the_limit_and_nothing_is_normalised(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, *clip_keys(20))
    runner = build(clips_for(inputs, **{k: Clip(31.0) for k in inputs}))

    with pytest.raises(PermanentError) as failure:
        concat_video.handle(job_with(), inputs, runner)

    assert failure.value.code == "duration_exceeded"
    assert "600" in failure.value.message
    assert runner.normalised() == []


def test_twenty_clips_of_30_seconds_are_at_the_limit_and_join(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, *clip_keys(20))
    runner = build(clips_for(inputs, **{k: Clip(30.0) for k in inputs}))

    outcome = concat_video.handle(job_with(), inputs, runner)

    assert outcome.data["clipCount"] == 20
    assert len(runner.normalised()) == 20


# --- normalising --------------------------------------------------------------------------


def test_a_clip_is_scaled_padded_and_re_encoded_to_the_aspect_frame(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, *clip_keys(2))
    runner = build(clips_for(inputs))

    concat_video.handle(job_with(aspectRatio="9:16"), inputs, runner)

    first, second = runner.normalised()
    assert flag_value(first, "-vf") == VF_9_16
    assert listed(first)[0] == str(inputs["clip_1"])
    assert listed(second)[0] == str(inputs["clip_2"])
    flags = " ".join(first)
    assert "-r 30" in flags
    assert "-c:v libx264 -preset fast -crf 23" in flags
    assert "-colorspace bt709 -color_range tv" in flags
    assert "-c:a aac -b:a 128k -ar 48000 -ac 2" in flags
    assert "-nostdin" in first
    assert "anullsrc" not in flags
    assert "-shortest" not in first


@pytest.mark.parametrize(
    ("ratio", "frame"),
    [(None, "1280:720"), ("16:9", "1280:720"), ("9:16", "720:1280"), ("1:1", "1080:1080")],
)
def test_the_aspect_ratio_picks_the_frame_and_16_9_is_the_default(
    tmp_path: Path, build: Build, ratio: Any, frame: str
) -> None:
    inputs = staged(tmp_path, *clip_keys(2))
    runner = build(clips_for(inputs))
    params = {} if ratio is None else {"aspectRatio": ratio}

    concat_video.handle(job_with(**params), inputs, runner)

    vf = flag_value(runner.normalised()[0], "-vf")
    assert vf.startswith(f"scale={frame}:")
    assert f"pad={frame}:" in vf


def test_a_clip_without_audio_gets_a_silent_stereo_track_of_its_own_length(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, *clip_keys(3))
    runner = build(clips_for(inputs, clip_2=Clip(audio=False)))

    concat_video.handle(job_with(), inputs, runner)

    first, silent, third = runner.normalised()
    assert "anullsrc=r=48000:cl=stereo" in listed(silent)
    assert "-shortest" in silent
    assert "-ar" in silent and "48000" in silent
    assert "-map" in silent
    assert all("anullsrc" not in " ".join(argv) for argv in (first, third))
    assert all("-shortest" not in argv for argv in (first, third))


def test_dropping_the_audio_mutes_every_normalised_clip_and_the_join(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, "clip_1", "clip_2", "narration")
    runner = build(clips_for(inputs))

    concat_video.handle(job_with(dropAudio=True), inputs, runner)

    for argv in runner.normalised():
        assert "-an" in argv
        assert "-c:a" not in argv
        assert "anullsrc" not in " ".join(argv)
    (join,) = runner.joins()
    assert "-an" in join and "-c:a" not in join
    assert runner.overlays() == []
    assert str(inputs["narration"]) not in runner.probed()


def test_the_clips_are_normalised_and_joined_in_numeric_order(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, *clip_keys(10))
    runner = build(clips_for(inputs))

    concat_video.handle(job_with(), inputs, runner)

    assert [listed(argv)[0] for argv in runner.normalised()] == [
        str(inputs[key]) for key in clip_keys(10)
    ]
    listing = (runner.scratch_dir() / "list.txt").read_text().splitlines()
    assert listing == [f"file '{Path(argv[-1])}'" for argv in runner.normalised()]
    assert Path(runner.normalised()[-1][-1]).name == "norm_10.mp4"


# --- joining ------------------------------------------------------------------------------


def test_the_join_copies_both_streams_through_the_concat_demuxer(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, *clip_keys(3))
    runner = build(clips_for(inputs))

    outcome = concat_video.handle(job_with(), inputs, runner)

    (join,) = runner.joins()
    assert "-f concat -safe 0" in " ".join(join)
    assert listed(join) == [str(runner.scratch_dir() / "list.txt")]
    assert "-c:v copy -c:a copy" in " ".join(join)
    assert "-movflags +faststart" in " ".join(join)
    (file,) = outcome.files
    assert (file.name, file.mime) == ("video.mp4", "video/mp4")
    assert isinstance(file.body, Path) and file.body.is_file()
    assert Path(join[-1]).name == "video.mp4"
    assert runner.overlays() == []
    assert outcome.data == {"clipCount": 3, "durationMs": 18000}


def test_a_narration_longer_than_the_film_extends_the_last_frame_and_keeps_every_word(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, "clip_1", "clip_2", "clip_3", "narration")
    runner = build(clips_for(inputs, narration=Clip(25.0, video=False)), final_seconds=25.0)

    outcome = concat_video.handle(job_with(), inputs, runner)

    (overlay,) = runner.overlays()
    assert listed(overlay) == [str(runner.scratch_dir() / "joined.mp4"), str(inputs["narration"])]
    assert "tpad=stop_mode=clone:stop_duration=7.000" in flag_value(overlay, "-vf")
    assert "libx264" in overlay
    assert "-c:v copy" not in " ".join(overlay)
    assert float(flag_value(overlay, "-t")) == pytest.approx(25.0)
    assert "-map 0:v:0 -map 1:a:0" in " ".join(overlay)
    assert Path(overlay[-1]).name == "video.mp4"
    assert outcome.data == {"clipCount": 3, "durationMs": 25000}


def test_a_narration_shorter_than_the_film_replaces_the_audio_without_re_encoding_the_picture(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, "clip_1", "clip_2", "narration")
    runner = build(clips_for(inputs, narration=Clip(5.0, video=False)))

    concat_video.handle(job_with(), inputs, runner)

    (overlay,) = runner.overlays()
    assert "-c:v copy" in " ".join(overlay)
    assert "-vf" not in overlay
    assert float(flag_value(overlay, "-t")) == pytest.approx(12.0)


def test_pad_extends_the_film_with_black_instead_of_the_last_frame(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, "clip_1", "clip_2", "narration")
    runner = build(clips_for(inputs, narration=Clip(20.0, video=False)))

    concat_video.handle(job_with(durationPolicy="pad"), inputs, runner)

    assert "tpad=stop_mode=add:stop_duration=8.000" in flag_value(runner.overlays()[0], "-vf")


def test_a_narration_without_audio_is_named(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, "clip_1", "clip_2", "narration")
    runner = build(clips_for(inputs, narration=Clip(video=False, audio=False)))

    with pytest.raises(PermanentError) as failure:
        concat_video.handle(job_with(), inputs, runner)

    assert failure.value.code == "no_audio_stream"
    assert "narration" in failure.value.message
    assert runner.normalised() == []


def test_every_argv_carries_the_whitelist_once_per_input(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, "clip_1", "clip_2", "clip_3", "narration")
    runner = build(clips_for(inputs, clip_2=Clip(audio=False), narration=Clip(25.0, video=False)))

    concat_video.handle(job_with(), inputs, runner)

    assert len(runner.calls) > 10
    for call in runner.calls:
        assert_whitelisted(call)


# --- failures -----------------------------------------------------------------------------


def test_a_clip_that_fails_to_normalise_is_named(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, *clip_keys(3))
    err = f"reading {inputs['clip_2']}\nError while decoding {inputs['clip_2']}"
    runner = build(clips_for(inputs), failures={"norm_2.mp4": (1, err)})

    with pytest.raises(PermanentError) as failure:
        concat_video.handle(job_with(), inputs, runner)

    assert failure.value.code == "video_concat_failed"
    assert "clip_2" in failure.value.message
    assert "Error while decoding" in failure.value.message
    assert str(inputs["clip_2"].parent) not in failure.value.message
    assert runner.joins() == []


def test_a_failed_join_ships_no_file(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, *clip_keys(2))
    runner = build(
        clips_for(inputs), failures={"video.mp4": (1, "Error opening input files: bad list")}
    )

    with pytest.raises(PermanentError) as failure:
        concat_video.handle(job_with(), inputs, runner)

    assert failure.value.code == "video_concat_failed"
    assert "join" in failure.value.message
    assert "bad list" in failure.value.message


def test_a_failed_narration_overlay_is_named_by_its_stage(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, "clip_1", "clip_2", "narration")
    runner = build(
        clips_for(inputs, narration=Clip(20.0, video=False)),
        failures={"video.mp4": (1, "Error while filtering")},
    )
    # The join writes joined.mp4 when a narration follows, so video.mp4 is the overlay's output.

    with pytest.raises(PermanentError) as failure:
        concat_video.handle(job_with(), inputs, runner)

    assert failure.value.code == "video_concat_failed"
    assert "narration" in failure.value.message


@pytest.mark.parametrize("stage", ["norm_1.mp4", "video.mp4"])
def test_a_stage_that_writes_nothing_is_a_failure(tmp_path: Path, build: Build, stage: str) -> None:
    inputs = staged(tmp_path, *clip_keys(2))
    runner = build(clips_for(inputs), silent=frozenset({stage}))

    with pytest.raises(PermanentError) as failure:
        concat_video.handle(job_with(), inputs, runner)

    assert failure.value.code == "video_concat_failed"


@pytest.mark.parametrize("stage", ["norm_1.mp4", "video.mp4"])
def test_a_stage_killed_by_the_kernel_is_transient(
    tmp_path: Path, build: Build, stage: str
) -> None:
    inputs = staged(tmp_path, *clip_keys(2))
    runner = build(clips_for(inputs), failures={stage: (-9, "")})

    with pytest.raises(TransientError):
        concat_video.handle(job_with(), inputs, runner)


# --- real ffmpeg --------------------------------------------------------------------------


def lavfi(path: Path, *sources: str, extra: tuple[str, ...] = ()) -> Path:
    argv = ["ffmpeg", "-v", "error", "-y"]
    for source in sources:
        argv += ["-f", "lavfi", "-i", source]
    subprocess.run([*argv, *extra, str(path)], check=True)
    return path


def clip(
    path: Path, size: str, seconds: float, sample_rate: int | None, pix_fmt: str = "yuv420p"
) -> Path:
    sources = [f"testsrc=duration={seconds}:size={size}:rate=25"]
    extra = ["-c:v", "libx264", "-pix_fmt", pix_fmt]
    if sample_rate is not None:
        sources.append(f"sine=frequency=330:sample_rate={sample_rate}:duration={seconds}")
        extra += ["-c:a", "aac"]
    return lavfi(path, *sources, extra=tuple(extra))


def ffprobe(path: Path, *args: str) -> str:
    completed = subprocess.run(
        ["ffprobe", "-v", "error", *args, "-of", "csv=p=0", str(path)],
        check=True,
        capture_output=True,
        text=True,
    )
    return completed.stdout.strip()


def joined(inputs: dict[str, Path], **params: Any) -> tuple[Path, ProcessRunner]:
    runner = ProcessRunner()
    outcome = concat_video.handle(job_with(**params), inputs, runner)
    (file,) = outcome.files
    assert isinstance(file.body, Path)
    return file.body, runner


@needs_ffmpeg
def test_a_clip_with_sound_and_a_silent_still_join_into_one_9_16_film(tmp_path: Path) -> None:
    inputs = {
        "clip_1": clip(tmp_path / "a.mp4", "1080x1920", 1.0, 24000),
        "clip_2": clip(tmp_path / "b.mp4", "720x1280", 1.0, None, pix_fmt="yuvj420p"),
    }
    output, runner = joined(inputs, aspectRatio="9:16")
    try:
        frame = ffprobe(
            output,
            *("-select_streams", "v:0"),
            *("-show_entries", "stream=width,height,color_range,nb_frames"),
        )
        sound = ffprobe(
            output,
            *("-select_streams", "a"),
            *("-show_entries", "stream=sample_rate,channels"),
        )
        length = float(ffprobe(output, "-show_entries", "format=duration"))
    finally:
        runner.close()

    # Two 1 s clips re-timed to 30 fps hold 60 frames; r_frame_rate is not read because a
    # stream-copy join reports the container's time base (120/1) for it.
    assert frame == "720,1280,tv,60"
    assert sound == "48000,2"
    assert length == pytest.approx(2.0, abs=0.1)


@needs_ffmpeg
def test_dropping_the_audio_leaves_no_audio_stream_even_with_a_narration(tmp_path: Path) -> None:
    inputs = {
        "clip_1": clip(tmp_path / "a.mp4", "320x180", 1.0, 24000),
        "clip_2": clip(tmp_path / "b.mp4", "320x180", 1.0, 24000),
        "narration": lavfi(tmp_path / "n.wav", "sine=duration=3"),
    }
    output, runner = joined(inputs, dropAudio=True)
    try:
        streams = ffprobe(output, "-show_entries", "stream=codec_type").splitlines()
    finally:
        runner.close()

    assert streams == ["video"]


@needs_ffmpeg
def test_a_narration_longer_than_the_film_holds_the_last_frame(tmp_path: Path) -> None:
    inputs = {
        "clip_1": clip(tmp_path / "a.mp4", "320x180", 1.0, 24000),
        "clip_2": clip(tmp_path / "b.mp4", "320x180", 1.0, 24000),
        "narration": lavfi(tmp_path / "n.wav", "sine=duration=3.5"),
    }
    output, runner = joined(inputs)
    try:
        length = float(ffprobe(output, "-show_entries", "format=duration"))
        video = float(ffprobe(output, "-select_streams", "v:0", "-show_entries", "stream=duration"))
    finally:
        runner.close()

    assert length == pytest.approx(3.5, abs=0.1)
    assert video == pytest.approx(3.5, abs=0.15)


@needs_ffmpeg
def test_an_m4a_with_cover_art_is_not_a_clip(tmp_path: Path) -> None:
    cover = lavfi(tmp_path / "cover.png", "color=c=red:s=64x64", extra=("-frames:v", "1"))
    song = tmp_path / "song.m4a"
    subprocess.run(
        [
            "ffmpeg", "-v", "error", "-y", "-i", str(cover), "-f", "lavfi", "-i", "sine=duration=1",
            "-map", "1:a", "-map", "0:v", "-c:a", "aac", "-c:v", "mjpeg",
            "-disposition:v", "attached_pic", str(song),
        ],
        check=True,
    )  # fmt: skip
    runner = ProcessRunner()
    try:
        with pytest.raises(PermanentError) as failure:
            concat_video.handle(
                job_with(),
                {"clip_1": clip(tmp_path / "a.mp4", "320x180", 1.0, 24000), "clip_2": song},
                runner,
            )
    finally:
        runner.close()

    assert failure.value.code == "input_rejected"
    assert "clip_2" in failure.value.message
