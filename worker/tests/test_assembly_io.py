"""The I/O helper shared by the assembly handlers: probes, the loudness reading and ffmpeg runs."""

from __future__ import annotations

import re
import shutil
import subprocess
from collections.abc import Callable
from pathlib import Path
from typing import Literal

import pytest

from flowcortex_worker.errors import PermanentError, TransientError
from flowcortex_worker.handlers import _assembly_io as io
from flowcortex_worker.runtime import ProcessRunner

needs_ffmpeg = pytest.mark.skipif(
    shutil.which("ffmpeg") is None,
    reason="ffmpeg is installed in the worker image",
)

Script = Callable[[list[str]], "subprocess.CompletedProcess[bytes]"]

SUMMARY = """\
[Parsed_ebur128_0 @ 0x1] Summary:

  Integrated loudness:
    I:         -22.8 LUFS
    Threshold: -33.0 LUFS

  Loudness range:
    LRA:         0.0 LU
"""


def done(
    argv: list[str], code: int = 0, out: str = "", err: str = ""
) -> subprocess.CompletedProcess[bytes]:
    return subprocess.CompletedProcess(argv, code, out.encode(), err.encode())


class FakeRunner(ProcessRunner):
    def __init__(self, script: Script) -> None:
        super().__init__()
        self.calls: list[list[str]] = []
        self._script = script

    def run(self, argv: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess[bytes]:
        self.calls.append(list(argv))
        return self._script(argv)


def runner(code: int = 0, out: str = "", err: str = "") -> FakeRunner:
    return FakeRunner(lambda argv: done(argv, code, out, err))


# --- input_args ---------------------------------------------------------------------------


def test_input_args_put_the_whitelist_right_before_the_input() -> None:
    assert io.input_args(Path("/work/a.wav")) == (
        "-protocol_whitelist",
        "file",
        "-i",
        "/work/a.wav",
    )


# --- loudness -----------------------------------------------------------------------------


def test_loudness_runs_the_pinned_argv_and_reads_the_summary() -> None:
    processes = runner(err=SUMMARY)

    value = io.loudness(Path("/work/voice.wav"), "a:0", processes, "compose_failed")

    assert value == -22.8
    assert processes.calls == [
        [
            *("ffmpeg", "-nostdin", "-hide_banner", "-nostats"),
            *("-protocol_whitelist", "file", "-i", "/work/voice.wav"),
            *("-map", "0:a:0", "-af", "ebur128=framelog=quiet", "-f", "null", "-"),
        ]
    ]
    assert "-loglevel" not in processes.calls[0]


def test_loudness_without_a_summary_is_none() -> None:
    silent = runner(err="size=N/A time=00:00:02")

    assert io.loudness(Path("/work/a.wav"), "a:0", silent, "compose_failed") is None


def test_loudness_of_a_measurement_that_exits_non_zero_is_the_callers_failure() -> None:
    processes = runner(code=1, err="/work/secret/a.wav: Invalid data found when processing input")

    with pytest.raises(PermanentError) as failure:
        io.loudness(Path("/work/secret/a.wav"), "a:0", processes, "compose_failed")

    assert failure.value.code == "compose_failed"
    assert "Invalid data found" in failure.value.message
    assert "/work/secret" not in failure.value.message


def test_loudness_ignores_an_integrated_line_before_the_summary() -> None:
    err = "t: 0.4 I: -99.0 LUFS\n" + SUMMARY

    assert io.loudness(Path("/work/a.wav"), "a:0", runner(err=err), "compose_failed") == -22.8


def test_loudness_cut_by_a_foreign_signal_is_transient() -> None:
    with pytest.raises(TransientError):
        io.loudness(Path("/work/a.wav"), "a:0", runner(code=-9), "compose_failed")


# --- duration and streams -----------------------------------------------------------------


def test_duration_reads_the_format_duration_through_the_whitelist() -> None:
    processes = runner(out="3.250000\n")

    assert io.duration_seconds(Path("/work/a.wav"), processes, "audio_extraction_failed") == 3.25
    argv = processes.calls[0]
    assert argv[0] == "ffprobe"
    assert argv.count("-protocol_whitelist") == argv.count("-i") == 1
    assert "format=duration" in argv


def test_a_failed_duration_probe_carries_the_callers_code_and_no_path() -> None:
    processes = runner(code=1, err="/work/secret/a.wav: Invalid data found")

    with pytest.raises(PermanentError) as failure:
        io.duration_seconds(Path("/work/secret/a.wav"), processes, "input_rejected")

    assert failure.value.code == "input_rejected"
    assert "/work/secret" not in failure.value.message


@pytest.mark.parametrize("text", ["", "N/A\n", "nan\n", "-1.0\n"])
def test_an_unusable_duration_is_a_permanent_failure(text: str) -> None:
    with pytest.raises(PermanentError) as failure:
        io.duration_seconds(Path("/work/a.wav"), runner(out=text), "audio_extraction_failed")

    assert failure.value.code == "audio_extraction_failed"


def test_a_probe_ended_by_a_foreign_signal_is_transient() -> None:
    with pytest.raises(TransientError):
        io.duration_seconds(Path("/work/a.wav"), runner(code=-9), "audio_extraction_failed")


@pytest.mark.parametrize(
    ("kind", "selector", "found"), [("audio", "a:0", "0\n"), ("video", "v:0", "0,0\n")]
)
def test_has_stream_selects_the_first_stream_of_the_kind(
    kind: Literal["audio", "video"], selector: str, found: str
) -> None:
    present = runner(out=found)
    absent = runner(out="")

    assert io.has_stream(Path("/work/a"), kind, present, "input_rejected")
    assert not io.has_stream(Path("/work/a"), kind, absent, "input_rejected")
    argv = present.calls[0]
    assert argv[argv.index("-select_streams") + 1] == selector
    assert argv.count("-protocol_whitelist") == argv.count("-i") == 1


def test_cover_art_is_not_a_video_stream() -> None:
    cover = runner(out="1,1\n")

    assert not io.has_stream(Path("/work/song.m4a"), "video", cover, "input_rejected")
    assert "stream_disposition=attached_pic" in " ".join(cover.calls[0])


def test_a_picture_stream_without_the_cover_disposition_is_video() -> None:
    assert io.has_stream(Path("/work/a"), "video", runner(out="0,0\n"), "input_rejected")


def test_require_audio_names_the_key_for_a_silent_input_and_for_a_failed_probe() -> None:
    with pytest.raises(PermanentError) as silent:
        io.require_audio("voice", Path("/work/a"), runner(out=""))
    with pytest.raises(PermanentError) as broken:
        io.require_audio("voice", Path("/work/a"), runner(code=1, err="Invalid data"))

    assert silent.value.code == "no_audio_stream"
    assert "voice" in silent.value.message
    assert broken.value.code == "audio_extraction_failed"
    assert "voice" in broken.value.message


def test_require_video_names_the_key_for_a_missing_stream_and_for_a_failed_probe() -> None:
    with pytest.raises(PermanentError) as flat:
        io.require_video("clip_2", Path("/work/a"), runner(out=""))
    with pytest.raises(PermanentError) as broken:
        io.require_video("clip_2", Path("/work/a"), runner(code=1, err="Invalid data"))

    assert flat.value.code == broken.value.code == "input_rejected"
    assert "clip_2" in flat.value.message
    assert "clip_2" in broken.value.message


def test_input_seconds_names_the_key_when_the_length_cannot_be_read() -> None:
    assert io.input_seconds("clip_1", Path("/work/a"), runner(out="2.5\n"), "input_rejected") == 2.5
    with pytest.raises(PermanentError) as failure:
        io.input_seconds("clip_2", Path("/work/a"), runner(out="N/A\n"), "input_rejected")

    assert failure.value.code == "input_rejected"
    assert "clip_2" in failure.value.message


def test_ceil_ms_is_not_pushed_up_by_float_noise() -> None:
    assert io.ceil_ms(4.6) == 4600
    assert io.ceil_ms(3.1) == 3100
    assert io.ceil_ms(2.0006) == 2001


def test_mask_dir_is_the_common_parent_of_every_source_and_the_scratch(tmp_path: Path) -> None:
    scratch = tmp_path / "scratch"
    sources = [tmp_path / "a" / "input", tmp_path / "b" / "input"]

    assert io.mask_dir(sources, scratch) == tmp_path


def test_mask_dir_falls_back_to_the_scratch_when_only_the_root_is_shared() -> None:
    assert io.mask_dir([Path("/srv/a/input")], Path("/tmp/scratch")) == Path("/tmp/scratch")


def test_a_failed_stream_probe_carries_the_callers_code() -> None:
    with pytest.raises(PermanentError) as failure:
        io.has_stream(Path("/work/a"), "video", runner(code=1, err="bad"), "input_rejected")

    assert failure.value.code == "input_rejected"


# --- run_ffmpeg ---------------------------------------------------------------------------


def test_a_foreign_signal_is_transient(tmp_path: Path) -> None:
    with pytest.raises(TransientError):
        io.run_ffmpeg(["ffmpeg"], runner(code=-9), "compose_failed", "the encode", tmp_path)


def test_a_non_zero_exit_names_the_code_and_the_last_line_without_the_work_dir(
    tmp_path: Path,
) -> None:
    err = f"[x] reading {tmp_path}/in.wav\nError while filtering {tmp_path}/in.wav"

    with pytest.raises(PermanentError) as failure:
        io.run_ffmpeg(["ffmpeg"], runner(code=1, err=err), "audio_mix_failed", "the mix", tmp_path)

    assert failure.value.code == "audio_mix_failed"
    assert "Error while filtering" in failure.value.message
    assert str(tmp_path) not in failure.value.message


def test_an_empty_or_missing_output_is_a_failure(tmp_path: Path) -> None:
    out = tmp_path / "mix.mp3"

    with pytest.raises(PermanentError) as missing:
        io.run_ffmpeg(["ffmpeg"], runner(), "audio_mix_failed", "the mix", tmp_path, output=out)
    out.write_bytes(b"")
    with pytest.raises(PermanentError) as empty:
        io.run_ffmpeg(["ffmpeg"], runner(), "audio_mix_failed", "the mix", tmp_path, output=out)

    assert missing.value.code == empty.value.code == "audio_mix_failed"


def test_a_written_output_passes(tmp_path: Path) -> None:
    out = tmp_path / "mix.mp3"
    processes = FakeRunner(lambda argv: (out.write_bytes(b"x"), done(argv))[1])

    completed = io.run_ffmpeg(
        ["ffmpeg", str(out)], processes, "audio_mix_failed", "the mix", tmp_path, output=out
    )

    assert completed.returncode == 0


# --- real ffmpeg --------------------------------------------------------------------------


@needs_ffmpeg
def test_loudness_matches_the_summary_ffmpeg_itself_prints(tmp_path: Path) -> None:
    source = tmp_path / "tone.wav"
    subprocess.run(
        [
            "ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
            "-af", "volume=-20dB", "-ac", "1", "-ar", "48000", str(source),
        ],
        check=True,
    )  # fmt: skip
    own = subprocess.run(
        [
            "ffmpeg", "-nostdin", "-hide_banner", "-nostats", "-i", str(source),
            "-af", "ebur128=framelog=quiet", "-f", "null", "-",
        ],
        check=True,
        capture_output=True,
        text=True,
    )  # fmt: skip
    printed = re.search(r"I:\s*(-?\d+\.\d+) LUFS", own.stderr.rpartition("Summary:")[2])
    assert printed is not None

    measured = io.loudness(source, "a:0", ProcessRunner(), "compose_failed")

    assert measured is not None
    assert abs(measured - float(printed.group(1))) <= 1.0


@needs_ffmpeg
def test_an_m4a_with_cover_art_has_no_video_stream_but_a_film_has(tmp_path: Path) -> None:
    cover = tmp_path / "cover.png"
    song = tmp_path / "song.m4a"
    film = tmp_path / "film.mp4"
    subprocess.run(
        [
            "ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "color=c=red:s=64x64",
            "-frames:v", "1", str(cover),
        ],
        check=True,
    )  # fmt: skip
    subprocess.run(
        [
            "ffmpeg", "-v", "error", "-y", "-i", str(cover), "-f", "lavfi", "-i", "sine=duration=1",
            "-map", "1:a", "-map", "0:v", "-c:a", "aac", "-c:v", "mjpeg",
            "-disposition:v", "attached_pic", str(song),
        ],
        check=True,
    )  # fmt: skip
    subprocess.run(
        [
            "ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc=duration=1:size=64x64",
            "-f", "lavfi", "-i", "sine=duration=1", "-shortest", str(film),
        ],
        check=True,
    )  # fmt: skip
    processes = ProcessRunner()

    try:
        assert not io.has_stream(song, "video", processes, "input_rejected")
        assert io.has_stream(song, "audio", processes, "input_rejected")
        assert io.has_stream(film, "video", processes, "input_rejected")
    finally:
        processes.close()
