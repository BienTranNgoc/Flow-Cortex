"""The compose_dub handler puts a voice, and optionally music and effects, onto a picture."""

from __future__ import annotations

import shutil
import subprocess
from collections.abc import Callable, Iterator
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import pytest
from conftest import job_body

from flowcortex_worker.assembly.compose import audio_graph
from flowcortex_worker.contracts.media_job import MediaJob
from flowcortex_worker.errors import PermanentError, TransientError
from flowcortex_worker.handlers import compose_dub
from flowcortex_worker.runtime import ProcessRunner

needs_ffmpeg = pytest.mark.skipif(
    shutil.which("ffmpeg") is None,
    reason="ffmpeg is installed in the worker image",
)

SUMMARY = "[Parsed_ebur128_0 @ 0x1] Summary:\n\n  Integrated loudness:\n    I:  {lufs} LUFS\n"


def job_with(**params: Any) -> MediaJob:
    return MediaJob.model_validate(job_body(capability="compose_dub", params=params))


@dataclass
class Media:
    seconds: float = 10.0
    video: bool = True
    audio: bool = True
    lufs: float | None = -20.0
    probe_exit: int = 0
    probe_err: str = ""
    measure_exit: int = 0
    measure_err: str = ""


class FakeRunner(ProcessRunner):
    """Scripts ffprobe and the ebur128 measurement from per-file `Media`, records every argv, and
    lets the encode write its output."""

    def __init__(
        self,
        media: dict[Path, Media],
        encode_exit: int = 0,
        encode_err: str = "",
        encode_writes: bool = True,
    ) -> None:
        super().__init__()
        self.calls: list[list[str]] = []
        self._media = media
        self._encode = (encode_exit, encode_err, encode_writes)

    def run(self, argv: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess[bytes]:
        self.calls.append(list(argv))
        source = self._media[Path(argv[argv.index("-i") + 1])] if "-i" in argv else None
        if argv[0] == "ffprobe":
            assert source is not None
            if source.probe_exit != 0:
                return subprocess.CompletedProcess(
                    argv, source.probe_exit, b"", source.probe_err.encode()
                )
            if "-select_streams" in argv:
                present = source.video if "v:0" in argv else source.audio
                row = (b"0,0\n" if "v:0" in argv else b"0\n") if present else b""
                return subprocess.CompletedProcess(argv, 0, row, b"")
            return subprocess.CompletedProcess(argv, 0, f"{source.seconds}\n".encode(), b"")
        if "ebur128=framelog=quiet" in argv:
            assert source is not None
            if source.measure_exit != 0:
                return subprocess.CompletedProcess(
                    argv, source.measure_exit, b"", source.measure_err.encode()
                )
            text = "" if source.lufs is None else SUMMARY.format(lufs=source.lufs)
            return subprocess.CompletedProcess(argv, 0, b"", text.encode())
        exit_code, err, writes = self._encode
        if exit_code == 0 and writes:
            Path(argv[-1]).write_bytes(b"mp4")
        return subprocess.CompletedProcess(argv, exit_code, b"", err.encode())

    def encodes(self) -> list[list[str]]:
        return [
            argv
            for argv in self.calls
            if argv[0] == "ffmpeg" and "ebur128=framelog=quiet" not in argv
        ]

    def measures(self) -> list[list[str]]:
        return [argv for argv in self.calls if "ebur128=framelog=quiet" in argv]

    def probed(self) -> list[str]:
        return [argv[argv.index("-i") + 1] for argv in self.calls if argv[0] == "ffprobe"]


Build = Callable[..., FakeRunner]


@pytest.fixture
def build() -> Iterator[Build]:
    made: list[FakeRunner] = []

    def make(media: dict[Path, Media], **encode: Any) -> FakeRunner:
        runner = FakeRunner(media, **encode)
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


def media_for(inputs: dict[str, Path], **change: Media) -> dict[Path, Media]:
    defaults = {"voice": Media(video=False), "background": Media(video=False)}
    return {path: change.get(key, defaults.get(key, Media())) for key, path in inputs.items()}


def assert_whitelisted(argv: list[str]) -> None:
    assert argv.count("-protocol_whitelist") == argv.count("-i")


def listed(argv: list[str]) -> list[str]:
    return [argv[i + 1] for i, flag in enumerate(argv) if flag == "-i"]


def graph_of(argv: list[str]) -> str:
    return argv[argv.index("-filter_complex") + 1]


def refused(
    tmp_path: Path, build: Build, keys: list[str], code: str = "contract_violation", **params: Any
) -> PermanentError:
    inputs = staged(tmp_path, *keys)
    runner = build(media_for(inputs))
    with pytest.raises(PermanentError) as failure:
        compose_dub.handle(job_with(**params), inputs, runner)
    assert runner.calls == []
    assert failure.value.code == code
    return failure.value


# --- the job's shape is checked before any process ----------------------------------------


def test_a_job_without_a_voice_is_refused(tmp_path: Path, build: Build) -> None:
    failure = refused(tmp_path, build, ["media"])

    assert "voice" in failure.message


def test_a_job_with_neither_video_nor_media_is_refused(tmp_path: Path, build: Build) -> None:
    failure = refused(tmp_path, build, ["voice", "background"])

    assert "video" in failure.message and "media" in failure.message


def test_a_foreign_key_is_refused_by_name(tmp_path: Path, build: Build) -> None:
    failure = refused(tmp_path, build, ["media", "voice", "music"])

    assert "music" in failure.message


def test_product_images_beside_the_picture_and_the_voice_are_ignored(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, "media", "voice", "image_1", "image_2")
    runner = build(media_for(inputs))

    compose_dub.handle(job_with(), inputs, runner)

    for key in ("image_1", "image_2"):
        assert all(str(inputs[key]) not in call for call in runner.calls)


def test_an_unknown_duration_policy_names_the_parameter_before_any_process(
    tmp_path: Path, build: Build
) -> None:
    failure = refused(tmp_path, build, ["media", "voice"], durationPolicy="stretch")

    assert "durationPolicy" in failure.message


@pytest.mark.parametrize("value", ["yes", "true", 1, 0, []])
def test_a_flag_that_is_not_a_boolean_is_refused_by_name(
    tmp_path: Path, build: Build, value: Any
) -> None:
    failure = refused(tmp_path, build, ["media", "voice"], videoAudioAsBackground=value)

    assert "videoAudioAsBackground" in failure.message


# --- inputs are checked before encoding ---------------------------------------------------


def test_a_picture_without_a_video_stream_is_rejected_and_no_encode_runs(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, "media", "voice")
    runner = build(media_for(inputs, media=Media(video=False)))

    with pytest.raises(PermanentError) as failure:
        compose_dub.handle(job_with(), inputs, runner)

    assert failure.value.code == "input_rejected"
    assert "media" in failure.value.message
    assert runner.encodes() == []


def test_a_picture_longer_than_ten_minutes_names_the_limit_and_no_encode_runs(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, "media", "voice")
    runner = build(media_for(inputs, media=Media(seconds=660.0)))

    with pytest.raises(PermanentError) as failure:
        compose_dub.handle(job_with(), inputs, runner)

    assert failure.value.code == "duration_exceeded"
    assert "600" in failure.value.message
    assert runner.encodes() == []


def test_a_picture_of_exactly_ten_minutes_is_accepted(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, "media", "voice")
    runner = build(media_for(inputs, media=Media(seconds=600.0)))

    compose_dub.handle(job_with(durationPolicy="match_video"), inputs, runner)

    assert len(runner.encodes()) == 1


@pytest.mark.parametrize("key", ["voice", "background", "effects"])
def test_an_audio_input_without_audio_is_named_and_no_encode_runs(
    tmp_path: Path, build: Build, key: str
) -> None:
    inputs = staged(tmp_path, "media", "voice", "background", "effects")
    runner = build(media_for(inputs, **{key: Media(video=False, audio=False)}))

    with pytest.raises(PermanentError) as failure:
        compose_dub.handle(job_with(), inputs, runner)

    assert failure.value.code == "no_audio_stream"
    assert key in failure.value.message
    assert runner.encodes() == []


def test_a_probe_that_fails_is_an_extraction_failure_without_a_local_path(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, "media", "voice")
    err = f"{inputs['voice']}: Invalid data found when processing input"
    runner = build(media_for(inputs, voice=Media(video=False, probe_exit=1, probe_err=err)))

    with pytest.raises(PermanentError) as failure:
        compose_dub.handle(job_with(), inputs, runner)

    assert failure.value.code == "audio_extraction_failed"
    assert str(tmp_path) not in failure.value.message


def test_a_probe_ended_by_a_foreign_signal_is_transient(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, "media", "voice")
    runner = build(media_for(inputs, media=Media(probe_exit=-9)))

    with pytest.raises(TransientError):
        compose_dub.handle(job_with(), inputs, runner)


def test_the_video_input_is_the_picture_when_both_it_and_the_upload_are_present(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, "video", "media", "voice")
    runner = build(media_for(inputs))

    compose_dub.handle(job_with(), inputs, runner)

    (argv,) = runner.encodes()
    assert listed(argv)[0] == str(inputs["video"])
    assert str(inputs["media"]) not in runner.probed()


def test_the_upload_is_the_picture_when_there_is_no_video_input(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, "media", "voice")
    runner = build(media_for(inputs))

    compose_dub.handle(job_with(), inputs, runner)

    (argv,) = runner.encodes()
    assert listed(argv)[0] == str(inputs["media"])


# --- the encode ---------------------------------------------------------------------------


def test_a_voice_alone_is_the_only_audio_and_nothing_is_measured(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, "media", "voice")
    runner = build(media_for(inputs))

    outcome = compose_dub.handle(job_with(), inputs, runner)

    (argv,) = runner.encodes()
    assert runner.measures() == []
    assert listed(argv) == [str(inputs["media"]), str(inputs["voice"])]
    assert argv[argv.index("-map") + 1] == "[v]"
    assert "1:a:0" in argv
    assert outcome.data["gainsDb"] == {"voice": 0}
    (file,) = outcome.files
    assert (file.name, file.mime) == ("video.mp4", "video/mp4")
    assert isinstance(file.body, Path) and file.body.is_file()


def test_the_encode_flags_are_h264_ultrafast_aac_and_faststart(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, "media", "voice")
    runner = build(media_for(inputs))

    compose_dub.handle(job_with(), inputs, runner)

    (argv,) = runner.encodes()
    flags = " ".join(argv)
    assert "-nostdin" in argv
    assert (
        "libx264 -preset ultrafast -crf 23 -pix_fmt yuv420p -c:a aac -b:a 160k -movflags +faststart"
    ) in flags


def test_a_voice_longer_than_the_clip_extends_the_picture_and_the_output(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, "media", "voice")
    runner = build(media_for(inputs, media=Media(seconds=5.87), voice=Media(9.14, video=False)))

    outcome = compose_dub.handle(job_with(), inputs, runner)

    (argv,) = runner.encodes()
    assert "tpad=stop_mode=clone:stop_duration=3.270" in graph_of(argv)
    assert argv[argv.index("-t") + 1] == "9.140"
    assert outcome.data == {
        "policy": "match_audio",
        "targetMs": 9140,
        "videoMs": 5870,
        "voiceMs": 9140,
        "gainsDb": {"voice": 0},
    }


def test_match_video_keeps_the_picture_length_and_cuts_a_longer_voice(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, "media", "voice")
    runner = build(media_for(inputs, media=Media(seconds=5.0), voice=Media(9.0, video=False)))

    outcome = compose_dub.handle(job_with(durationPolicy="match_video"), inputs, runner)

    (argv,) = runner.encodes()
    assert "tpad" not in graph_of(argv)
    assert argv[argv.index("-t") + 1] == "5.000"
    assert outcome.data["policy"] == "match_video"
    assert outcome.data["targetMs"] == 5000


def test_pad_adds_black_instead_of_cloning_the_last_frame(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, "media", "voice")
    runner = build(media_for(inputs, media=Media(seconds=6.0), voice=Media(8.0, video=False)))

    compose_dub.handle(job_with(durationPolicy="pad"), inputs, runner)

    assert "tpad=stop_mode=add:stop_duration=2.000" in graph_of(runner.encodes()[0])


def test_a_voice_within_tolerance_of_the_picture_does_not_extend_it(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, "media", "voice")
    runner = build(media_for(inputs, media=Media(seconds=10.0), voice=Media(10.04, video=False)))

    compose_dub.handle(job_with(), inputs, runner)

    assert "tpad" not in graph_of(runner.encodes()[0])


def test_a_background_is_mixed_at_the_gains_the_loudness_targets_give(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, "media", "voice", "background")
    runner = build(
        media_for(
            inputs,
            voice=Media(10.0, video=False, lufs=-22.8),
            background=Media(10.0, video=False, lufs=-9.4),
        )
    )

    outcome = compose_dub.handle(job_with(), inputs, runner)

    (argv,) = runner.encodes()
    expected = audio_graph(
        "null", "file", False, {"voice": 6.8, "background": -10.6, "effects": 0.0}, 10.0
    )
    assert expected is not None
    assert graph_of(argv) == expected.graph
    assert listed(argv) == [str(inputs[k]) for k in ("media", "background", "voice")]
    assert "sidechaincompress=threshold=0.05:ratio=4:attack=20:release=400" in graph_of(argv)
    assert argv[argv.index("-map") + 1] == "[v]"
    assert "[aout]" in argv
    assert outcome.data["gainsDb"] == {"voice": 6.8, "background": -10.6}
    assert len(runner.measures()) == 2


def test_the_pictures_own_audio_is_the_background_when_asked_and_none_is_given(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, "media", "voice")
    runner = build(
        media_for(inputs, media=Media(10.0, lufs=-9.4), voice=Media(video=False, lufs=-22.8))
    )

    outcome = compose_dub.handle(job_with(videoAudioAsBackground=True), inputs, runner)

    (argv,) = runner.encodes()
    assert listed(argv) == [str(inputs["media"]), str(inputs["voice"])]
    assert "[0:a]volume=-10.60dB" in graph_of(argv)
    assert outcome.data["gainsDb"] == {"voice": 6.8, "background": -10.6}
    assert str(inputs["media"]) in " ".join(" ".join(call) for call in runner.measures())


def test_a_given_background_wins_over_the_pictures_own_audio(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, "media", "voice", "background")
    runner = build(media_for(inputs))

    compose_dub.handle(job_with(videoAudioAsBackground=True), inputs, runner)

    (argv,) = runner.encodes()
    assert str(inputs["background"]) in listed(argv)
    assert "[0:a]" not in graph_of(argv)


def test_the_pictures_audio_is_not_a_background_when_the_picture_has_none(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, "media", "voice")
    runner = build(media_for(inputs, media=Media(audio=False)))

    outcome = compose_dub.handle(job_with(videoAudioAsBackground=True), inputs, runner)

    (argv,) = runner.encodes()
    assert "1:a:0" in argv
    assert runner.measures() == []
    assert outcome.data["gainsDb"] == {"voice": 0}


def test_effects_alone_are_mixed_with_no_sidechain(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, "media", "voice", "effects")
    runner = build(
        media_for(
            inputs,
            voice=Media(video=False, lufs=-22.8),
            effects=Media(video=False, lufs=-26.0),
        )
    )

    outcome = compose_dub.handle(job_with(), inputs, runner)

    (argv,) = runner.encodes()
    assert "sidechaincompress" not in graph_of(argv)
    assert "atrim=0:10.000,apad=whole_dur=10.000" in graph_of(argv)
    assert outcome.data["gainsDb"] == {"voice": 6.8, "effects": 6.0}


def test_a_silent_or_unmeasurable_source_keeps_its_level(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, "media", "voice", "background", "effects")
    runner = build(
        media_for(
            inputs,
            voice=Media(video=False, lufs=None),
            background=Media(video=False, lufs=-30.0),
            effects=Media(video=False, lufs=-70.0),
        )
    )

    outcome = compose_dub.handle(job_with(), inputs, runner)

    assert outcome.data["gainsDb"] == {"voice": 0, "background": 0, "effects": 0}


def test_a_measurement_that_exits_non_zero_fails_the_compose_and_hides_the_path(
    tmp_path: Path, build: Build
) -> None:
    inputs = staged(tmp_path, "media", "voice", "background")
    err = f"{inputs['background']}: Invalid data found when processing input"
    runner = build(
        media_for(inputs, background=Media(video=False, measure_exit=1, measure_err=err))
    )

    with pytest.raises(PermanentError) as failure:
        compose_dub.handle(job_with(), inputs, runner)

    assert failure.value.code == "compose_failed"
    assert "Invalid data found" in failure.value.message
    assert str(inputs["background"].parent) not in failure.value.message
    assert runner.encodes() == []


def test_a_measurement_ended_by_a_foreign_signal_is_transient(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, "media", "voice", "background")
    runner = build(media_for(inputs, voice=Media(video=False, measure_exit=-9)))

    with pytest.raises(TransientError):
        compose_dub.handle(job_with(), inputs, runner)


def test_every_argv_carries_the_whitelist_once_per_input(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, "media", "voice", "background", "effects")
    runner = build(media_for(inputs))

    compose_dub.handle(job_with(), inputs, runner)

    assert len(runner.calls) > 5
    for call in runner.calls:
        assert_whitelisted(call)
    assert len(runner.measures()) == 3


def test_a_failed_encode_ships_no_file_and_hides_the_work_dir(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, "media", "voice")
    runner = build(
        media_for(inputs),
        encode_exit=1,
        encode_err=f"reading {inputs['media']}\nError while filtering {inputs['media']}",
    )

    with pytest.raises(PermanentError) as failure:
        compose_dub.handle(job_with(), inputs, runner)

    assert failure.value.code == "compose_failed"
    assert "Error while filtering" in failure.value.message
    assert str(inputs["media"].parent) not in failure.value.message
    assert str(runner.scratch_dir()) not in failure.value.message


def test_an_encode_that_writes_nothing_is_a_failure(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, "media", "voice")
    runner = build(media_for(inputs), encode_writes=False)

    with pytest.raises(PermanentError) as failure:
        compose_dub.handle(job_with(), inputs, runner)

    assert failure.value.code == "compose_failed"


def test_an_encode_killed_by_the_kernel_is_transient(tmp_path: Path, build: Build) -> None:
    inputs = staged(tmp_path, "media", "voice")
    runner = build(media_for(inputs), encode_exit=-9)

    with pytest.raises(TransientError):
        compose_dub.handle(job_with(), inputs, runner)


# --- real ffmpeg --------------------------------------------------------------------------


def lavfi(path: Path, *sources: str, extra: tuple[str, ...] = ()) -> Path:
    argv = ["ffmpeg", "-v", "error", "-y"]
    for source in sources:
        argv += ["-f", "lavfi", "-i", source]
    subprocess.run([*argv, *extra, str(path)], check=True)
    return path


def film(path: Path, seconds: float, with_audio: bool = True) -> Path:
    sources = [f"testsrc=duration={seconds}:size=160x120:rate=25"]
    extra: tuple[str, ...] = ("-pix_fmt", "yuv420p")
    if with_audio:
        sources.append(f"sine=frequency=330:sample_rate=44100:duration={seconds}")
        extra += ("-c:a", "aac")
    return lavfi(path, *sources, extra=extra)


def tone(path: Path, seconds: float, frequency: int = 440) -> Path:
    return lavfi(path, f"sine=frequency={frequency}:sample_rate=24000:duration={seconds}")


def ffprobe(path: Path, *args: str) -> str:
    completed = subprocess.run(
        ["ffprobe", "-v", "error", *args, "-of", "csv=p=0", str(path)],
        check=True,
        capture_output=True,
        text=True,
    )
    return completed.stdout.strip()


def composed(inputs: dict[str, Path], **params: Any) -> tuple[Path, ProcessRunner]:
    runner = ProcessRunner()
    outcome = compose_dub.handle(job_with(**params), inputs, runner)
    (file,) = outcome.files
    assert isinstance(file.body, Path)
    return file.body, runner


@needs_ffmpeg
def test_a_short_voice_does_not_cut_the_background(tmp_path: Path) -> None:
    inputs = {
        "media": film(tmp_path / "media.mp4", 3.0),
        "voice": tone(tmp_path / "voice.wav", 1.5),
        "background": tone(tmp_path / "background.wav", 3.0, 220),
    }
    # match_audio would end the output with the 1.5 s voice; the picture's length is the target
    # that the music must fill.
    output, runner = composed(inputs, durationPolicy="match_video")
    try:
        length = float(ffprobe(output, "-show_entries", "format=duration"))
        codecs = ffprobe(output, "-show_entries", "stream=codec_name").splitlines()
    finally:
        runner.close()

    assert length == pytest.approx(3.0, abs=0.1)
    assert sorted(codecs) == ["aac", "h264"]


@needs_ffmpeg
def test_a_voice_longer_than_the_picture_extends_it_under_match_audio(tmp_path: Path) -> None:
    inputs = {
        "media": film(tmp_path / "media.mp4", 2.0),
        "voice": tone(tmp_path / "voice.wav", 3.0),
    }
    output, runner = composed(inputs)
    try:
        length = float(ffprobe(output, "-show_entries", "format=duration"))
    finally:
        runner.close()

    assert length == pytest.approx(3.0, abs=0.1)


@needs_ffmpeg
def test_the_pictures_own_music_plays_under_the_voice(tmp_path: Path) -> None:
    inputs = {
        "media": film(tmp_path / "media.mp4", 3.0),
        "voice": tone(tmp_path / "voice.wav", 1.5),
    }
    output, runner = composed(inputs, videoAudioAsBackground=True, durationPolicy="match_video")
    try:
        length = float(ffprobe(output, "-show_entries", "format=duration"))
    finally:
        runner.close()

    assert length == pytest.approx(3.0, abs=0.1)


@needs_ffmpeg
def test_an_m4a_with_cover_art_is_not_a_picture(tmp_path: Path) -> None:
    cover = lavfi(tmp_path / "cover.png", "color=c=red:s=64x64", extra=("-frames:v", "1"))
    song = tmp_path / "song.m4a"
    subprocess.run(
        [
            "ffmpeg", "-v", "error", "-y", "-i", str(cover), "-f", "lavfi", "-i", "sine=duration=2",
            "-map", "1:a", "-map", "0:v", "-c:a", "aac", "-c:v", "mjpeg",
            "-disposition:v", "attached_pic", str(song),
        ],
        check=True,
    )  # fmt: skip
    runner = ProcessRunner()
    try:
        with pytest.raises(PermanentError) as failure:
            compose_dub.handle(
                job_with(), {"media": song, "voice": tone(tmp_path / "voice.wav", 1.0)}, runner
            )
    finally:
        runner.close()

    assert failure.value.code == "input_rejected"
