"""Audio mix: tempo stages, the mix graph, ducking, loudness parsing and the final mux."""

from __future__ import annotations

import math
from typing import Any

import pytest
from test_render_document import music_clip, scene, voice_clip, with_music, with_voice
from test_render_timeline import Mutate, plan

from flowcortex_worker.render.mix import (
    ATEMPO_STAGE_MAX,
    ATEMPO_STAGE_MIN,
    atempo_stages,
    final_mux_command,
    loudness_measure_command,
    loudnorm_filter,
    mix_command,
    parse_loudnorm,
)
from flowcortex_worker.render.timeline import TimelinePlan

FILES = {
    "a1": "/in/a1",
    "a2": "/in/a2",
    "v1": "/in/v1",
    "v2": "/in/v2",
    "s1": "/in/s1",
    "m1": "/in/m1",
}
STEM = {"kind": "audio", "role": "stem"}


def planned(mutate: Mutate | None = None) -> TimelinePlan:
    return plan([scene(0)], mutate=mutate)


def mixed(mutate: Mutate | None = None) -> tuple[list[str], str]:
    return mix_command(planned(mutate), "/work/base.mp4", FILES, "/work")


def graph_of(argv: list[str]) -> str:
    return argv[argv.index("-filter_complex") + 1]


def voices(*clips: dict[str, Any], music: dict[str, Any] | None = None) -> Mutate:
    def apply(raw: dict[str, Any]) -> None:
        raw["assets"]["v2"] = {"kind": "audio", "role": "dub"}
        raw["assets"]["s1"] = STEM
        with_voice(raw, *clips)
        if music is not None:
            with_music(raw, music)

    return apply


def test_atempo_stages_stay_inside_one_stage_range() -> None:
    assert atempo_stages(1) == []
    assert atempo_stages(1.5) == [1.5]
    assert atempo_stages(4) == [2, 2]
    stages = atempo_stages(0.3)
    assert all(ATEMPO_STAGE_MIN <= s <= ATEMPO_STAGE_MAX for s in stages)
    assert math.prod(stages) == pytest.approx(0.3)


def test_scene_only_mix_pads_the_scene_audio_and_mixes_it_with_silence() -> None:
    argv, out = mixed()
    graph = graph_of(argv)
    assert out == "/work/mix.wav"
    assert graph.startswith(
        "[0:a]aresample=async=1:first_pts=0,aformat=sample_fmts=fltp:channel_layouts=stereo,"
        "apad=whole_dur=3.0000,atrim=duration=3.0000[scene]"
    )
    assert "-f" in argv and "anullsrc=r=48000:cl=stereo" in argv
    assert "[1:a]atrim=duration=3.0000[voice]" in graph
    assert (
        "[scene][voice]amix=inputs=2:duration=longest:normalize=0,atrim=duration=3.0000[mix]"
        in graph
    )
    assert argv[-9:] == [
        "-map", "[mix]", "-c:a", "pcm_s16le", "-ar", "48000", "-t", "3.0000", "/work/mix.wav",
    ]  # fmt: skip


def test_a_faster_voice_clip_is_tempo_changed_delayed_and_trimmed_to_the_timeline() -> None:
    argv, _ = mixed(voices(voice_clip(atMs=1000, srcOutMs=3000, durMs=2000, rate=1.5)))
    graph = graph_of(argv)
    assert "[1:a]atrim=start=0.0000:end=3.0000,asetpts=PTS-STARTPTS,aresample=48000" in graph
    assert ",atempo=1.5,atrim=duration=2.0000" in graph
    assert ",volume=0dB,adelay=1000|1000,asetpts=N/SR/TB,apad=whole_dur=3.0000" in graph
    assert "atrim=duration=3.0000[vo0]" in graph


def test_a_trimmed_voice_clip_reads_play_times_rate_and_fades_at_the_cut() -> None:
    cue = {
        "id": "cu1",
        "startMs": 0,
        "endMs": 3500,
        "text": "Xin chào",
        "styleId": None,
    }

    def mutate(raw: dict[str, Any]) -> None:
        raw["timeline"]["captions"]["tracks"]["ct_main"]["cues"] = [cue]
        voices(voice_clip(cueId="cu1", atMs=1000, fitPolicy="trim_tail"))(raw)

    argv, _ = mixed(mutate)
    graph = graph_of(argv)
    assert "atrim=start=0.0000:end=2.5000" in graph
    assert ",afade=t=out:st=2.4400:d=0.0600," in graph


def test_a_looped_music_segment_repeats_to_the_clip_length() -> None:
    argv, _ = mixed(lambda raw: with_music(raw, music_clip(srcOutMs=4000, durMs=10000, loop=True)))
    assert "aloop=loop=-1:size=192000,atrim=duration=10.0000,asetpts=PTS-STARTPTS" in graph_of(argv)


def test_music_is_not_looped_unless_asked() -> None:
    argv, _ = mixed(lambda raw: with_music(raw, music_clip()))
    assert "aloop" not in graph_of(argv)
    assert "[scene][voice][music]amix=inputs=3" in graph_of(argv)


def test_narration_ducks_the_music() -> None:
    argv, _ = mixed(voices(voice_clip(), music=music_clip()))
    graph = graph_of(argv)
    assert "[voice]asplit=2[voice_mix][voice_sc]" in graph
    assert (
        "[music0][voice_sc]sidechaincompress=threshold=0.02:ratio=6:attack=20:release=400[music]"
        in graph
    )
    assert "[scene][voice_mix][music]amix=inputs=3" in graph


def test_ducking_off_leaves_the_music_untouched() -> None:
    def mutate(raw: dict[str, Any]) -> None:
        voices(voice_clip(), music=music_clip())(raw)
        raw["timeline"]["music"]["duck"]["enabled"] = False

    graph = graph_of(mixed(mutate)[0])
    assert "sidechaincompress" not in graph
    assert "[music0]anull[music]" in graph


def test_original_voice_does_not_duck() -> None:
    graph = graph_of(mixed(voices(voice_clip(assetId="s1"), music=music_clip()))[0])
    assert "sidechaincompress" not in graph
    assert "asplit" not in graph


def test_only_the_narration_clip_keys_the_ducking_when_stems_are_mixed_in() -> None:
    graph = graph_of(
        mixed(voices(voice_clip(), voice_clip(id="vc2", assetId="s1"), music=music_clip()))[0]
    )
    assert graph.count("asplit=2") == 1
    assert "[vo0]asplit=2[vm0][vk0]" in graph
    assert "[vk0]anull[voice_sc]" in graph
    assert "[vm0][vo1]amix=inputs=2:duration=longest:normalize=0[voice]" in graph
    assert "sidechaincompress" in graph


def test_every_asset_input_is_preceded_by_the_file_protocol_whitelist() -> None:
    argv, _ = mixed(voices(voice_clip(), voice_clip(id="vc2", assetId="v2"), music=music_clip()))
    positions = [i for i, arg in enumerate(argv) if arg == "-i"]
    assert [argv[i + 1] for i in positions] == ["/work/base.mp4", "/in/v1", "/in/v2", "/in/m1"]
    for i in positions[1:]:
        assert argv[i - 2 : i] == ["-protocol_whitelist", "file"]


def test_a_dropped_clip_adds_no_input() -> None:
    argv, _ = mixed(lambda raw: with_voice(raw, voice_clip(row="rw1"), lane=False))
    assert argv.count("-i") == 2  # base and the silent voice source


def test_loudnorm_block_is_found_behind_the_ffmpeg_7_tail() -> None:
    stderr = (
        "Input stream #0:0\n"
        '{\n\t"input_i" : "-20.10",\n\t"input_tp" : "-3.00",\n\t"input_lra" : "5.20",\n'
        '\t"input_thresh" : "-30.50",\n\t"target_offset" : "0.10"\n}\n'
        "[out#0/null @ 0x1] video:0KiB audio:1KiB\nsize=N/A time=00:00:03.00 speed=900x\n"
    )
    assert parse_loudnorm(stderr)["input_i"] == "-20.10"


def test_the_last_loudnorm_block_wins() -> None:
    stderr = '{"input_i": "-1"}\nnoise\n{"input_i": "-2"}\n'
    assert parse_loudnorm(stderr)["input_i"] == "-2"


def test_output_without_a_measurement_is_refused() -> None:
    with pytest.raises(ValueError, match="loudnorm"):
        parse_loudnorm("size=N/A time=00:00:00.00")


MEASURED = {
    "input_i": "-20.10",
    "input_tp": "-3.00",
    "input_lra": "5.20",
    "input_thresh": "-30.50",
    "target_offset": "0.10",
}


def test_loudness_measure_reads_the_file_without_writing() -> None:
    argv = loudness_measure_command("/work/mix.wav")
    assert argv[argv.index("-i") + 1] == "/work/mix.wav"
    assert "loudnorm=I=-14:TP=-1:LRA=11:print_format=json" in argv
    assert argv[-3:] == ["-f", "null", "-"]


def test_second_pass_carries_the_measured_values() -> None:
    assert loudnorm_filter(MEASURED) == (
        "loudnorm=I=-14:TP=-1:LRA=11:measured_I=-20.10:measured_LRA=5.20:measured_TP=-3.00"
        ":measured_thresh=-30.50:offset=0.10:linear=true:print_format=json,aresample=48000"
    )


def test_a_silent_mix_has_no_second_pass() -> None:
    assert loudnorm_filter({**MEASURED, "input_i": "-inf"}) is None


@pytest.mark.parametrize("broken", [{"input_i": "abc"}, {"input_i": "-20"}])
def test_a_malformed_measurement_is_refused(broken: dict[str, str]) -> None:
    with pytest.raises(ValueError, match=r"input_i|lacks"):
        loudnorm_filter(broken)


def test_final_mux_encodes_the_mix_once_and_copies_the_picture() -> None:
    argv = final_mux_command(
        "/work/base.mp4", "/work/mix.wav", "/work/out.mp4", 6000, 0.067, "loudnorm=X"
    )
    assert argv == [
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-itsoffset", "-0.067000", "-i", "/work/base.mp4", "-i", "/work/mix.wav",
        "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-af", "loudnorm=X",
        "-c:a", "aac", "-b:a", "160k", "-ar", "48000", "-t", "6.0000",
        "-movflags", "+faststart", "/work/out.mp4",
    ]  # fmt: skip


def test_final_mux_without_a_filter_has_no_audio_filter() -> None:
    argv = final_mux_command("/w/b.mp4", "/w/m.wav", "/w/o.mp4", 6000)
    assert "-af" not in argv
    assert argv[argv.index("-itsoffset") + 1] == "-0.000000"
