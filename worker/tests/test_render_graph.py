"""Picture graph and ffmpeg argument lists: fit, scene sources, handles, join and mux."""

from __future__ import annotations

import re
from typing import Any

import pytest
from test_render_document import scene
from test_render_timeline import PROBES, plan

from flowcortex_worker.render.graph import (
    chunk_command,
    concat_list,
    fit_chain,
    join_command,
    mux_command,
)
from flowcortex_worker.render.timeline import TimelinePlan

FILES = {"a1": "/in/a1", "a2": "/in/a2"}


def graph_of(cmd: list[str]) -> str:
    return cmd[cmd.index("-filter_complex") + 1]


def command(planned: TimelinePlan, index: int = 0) -> list[str]:
    return chunk_command(planned, index, FILES, "/work", "/work/chunk_0.ass")


def transition(mode: str, dur: int = 400, kind: str = "fade") -> dict[str, Any]:
    return {"kind": kind, "durMs": dur, "mode": mode}


def test_cover_and_contain_at_identity_are_the_plain_chain() -> None:
    assert (
        fit_chain("cover", 1080, 1920, 0, 24)
        == "scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920"
    )
    assert fit_chain("contain", 1080, 1920, 0, 24) == (
        "scale=1080:1920:force_original_aspect_ratio=decrease,pad=1080:1920:(ow-iw)/2:(oh-ih)/2:black"
    )


def test_contain_blur_at_identity_centres_the_picture_on_the_blurred_copy() -> None:
    chain = fit_chain("contain_blur", 1080, 1920, 2, 24)
    assert "gblur=sigma=24" in chain
    assert chain.endswith("overlay=(W-w)/2:(H-h)/2")
    assert "[cb2_bg]" in chain


@pytest.mark.parametrize("fit", ["cover", "contain"])
def test_offset_and_zoom_overlay_the_fit_on_black_at_even_pixels(fit: str) -> None:
    chain = fit_chain(fit, 1080, 1920, 0, 24, 0.1, -0.05, 1.5, 30)
    assert "scale=iw*1.5:ih*1.5" in chain
    assert "color=c=black:s=1080x1920:r=30" in chain
    assert "overlay=x=floor(((W-w)/2+0.1*W)/2)*2:y=floor(((H-h)/2-0.05*H)/2)*2" in chain


def test_contain_blur_transform_reuses_the_blurred_background() -> None:
    chain = fit_chain("contain_blur", 1080, 1920, 1, 12, 0.1, 0.0, 1.5)
    assert chain.count("gblur") == 1
    assert "gblur=sigma=12" in chain
    assert "color=" not in chain
    assert "[cb1_bgb][sc1z]overlay=x=floor(" in chain


def test_unknown_fit_is_refused() -> None:
    with pytest.raises(ValueError, match="fit"):
        fit_chain("stretch", 1080, 1920, 0, 24)


def test_blur_sigma_follows_the_output_size() -> None:
    planned = plan([scene(0, fit="contain_blur")], profile="draft")
    assert planned.out_width == 480
    assert "gblur=sigma=10.6667" in graph_of(command(planned))


def test_extend_lengthens_both_neighbours_and_sets_the_xfade_offset() -> None:
    probes = {**PROBES, "a1": {**PROBES["a1"], "durationMs": 20000}}
    planned = plan(
        [
            scene(0, transitionOut=transition("extend")),
            scene(1, srcInMs=1000, srcOutMs=4000),
        ],
        probes=probes,
    )
    cmd = command(planned)
    ts = [cmd[i + 1] for i, a in enumerate(cmd) if a == "-t"]
    assert ts[0] == "3.2000"
    assert cmd[cmd.index("-ss") + 1] == "0.0000"
    second = [i for i, a in enumerate(cmd) if a == "-ss"][1]
    assert cmd[second + 1] == "0.8000"
    # first segment 3200 ms, transition 400 ms
    assert "xfade=transition=fade:duration=0.4000:offset=2.8000" in graph_of(cmd)
    assert cmd[cmd.index("-t", len(cmd) - 12) + 1] == "6.0000"


def test_overlap_adds_no_handle() -> None:
    planned = plan(
        [scene(0, transitionOut=transition("overlap", 500)), scene(1, atMs=2500)],
    )
    cmd = command(planned)
    assert "xfade=transition=fade:duration=0.5000:offset=2.5000" in graph_of(cmd)
    assert [cmd[i + 1] for i, a in enumerate(cmd) if a == "-ss"] == ["0.0000", "0.0000"]
    assert cmd[-4] == "5.5000"


def test_fill_scene_is_a_color_source() -> None:
    planned = plan([scene(0, clipId=None, srcOutMs=3000, fill="#ff8800")])
    cmd = command(planned)
    assert "color=c=0xff8800:s=1080x1920:r=30" in cmd
    assert "-protocol_whitelist" not in cmd


def test_image_scene_is_looped_and_zooms_out_from_the_larger_scale() -> None:
    planned = plan(
        [
            scene(
                0,
                clipId="a2",
                motion={"kind": "zoom_out", "from": 1.0, "to": 1.3},
            )
        ]
    )
    cmd = command(planned)
    at = cmd.index("-loop")
    assert cmd[at : at + 4] == ["-loop", "1", "-framerate", "30"]
    assert "zoompan=z='1.3+(1.0-1.3)*on/90'" in graph_of(cmd)


def test_zoom_in_keeps_its_direction() -> None:
    planned = plan([scene(0, clipId="a2", motion={"kind": "zoom_in", "from": 1.0, "to": 1.3})])
    assert "zoompan=z='1.0+(1.3-1.0)*on/90'" in graph_of(command(planned))


def test_hdr_clip_is_tone_mapped_before_the_fit() -> None:
    probes = {**PROBES, "a1": {**PROBES["a1"], "colorTransfer": "smpte2084"}}
    graph = graph_of(command(plan([scene(0)], probes=probes)))
    assert "zscale=t=linear:npl=100" in graph
    assert "tonemap=tonemap=hable" in graph
    assert graph.index("tonemap") < graph.index("scale=1080:1920")


def test_sdr_clip_has_no_tone_map() -> None:
    assert "tonemap" not in graph_of(command(plan([scene(0)])))


def test_every_asset_input_is_preceded_by_the_file_protocol_whitelist() -> None:
    cmd = command(
        plan([scene(0, transitionOut=transition("overlap")), scene(1, clipId="a2", atMs=2600)])
    )
    assets = [i for i, a in enumerate(cmd) if a in FILES.values()]
    assert len(assets) == 2
    for i in assets:
        assert cmd[i - 3 : i] == ["-protocol_whitelist", "file", "-i"]


def test_kept_audio_uses_the_clip_and_everything_else_is_silence() -> None:
    kept = command(plan([scene(0, audio={"keep": True, "gainDb": -6})]))
    assert "volume=-6.0dB" in graph_of(kept)
    assert "anullsrc" not in " ".join(kept)
    muted = command(plan([scene(0, audio={"keep": False, "gainDb": 0})]))
    assert "anullsrc=r=48000:cl=stereo" in muted
    no_audio = {**PROBES, "a1": {**PROBES["a1"], "hasAudio": False}}
    assert "anullsrc" in " ".join(command(plan([scene(0)], probes=no_audio)))


def test_chunk_output_is_named_by_index_whatever_the_scene_id() -> None:
    planned = plan(
        [scene(0, id="../../x"), scene(1, id="../y", clipId="a2")],
    )
    assert command(planned, 1)[-1] == "/work/chunk_1.mp4"
    assert command(planned, 0)[-1] == "/work/chunk_0.mp4"
    assert not any("../" in part for part in command(planned, 0))


def test_chunk_with_captions_logs_verbosely_for_the_libass_audit() -> None:
    planned = plan([scene(0)])
    cmd = command(planned)
    assert planned.cues
    assert cmd[cmd.index("-loglevel") + 1] == "verbose"
    assert "-nostats" in cmd


def test_chunk_without_captions_stays_quiet() -> None:
    planned = plan([scene(0)], mutate=lambda d: d["output"]["lanes"].update(captions=False))
    cmd = command(planned)
    assert cmd[cmd.index("-loglevel") + 1] == "error"
    assert "-nostats" not in cmd


def test_ass_path_is_escaped_for_the_filter_graph() -> None:
    planned = plan([scene(0)])
    cmd = chunk_command(planned, 0, FILES, "/work", "/w:o'rk/chunk_0.ass")
    assert "ass='/w\\:o\\'rk/chunk_0.ass'" in graph_of(cmd)


def test_encode_arguments_follow_the_profile() -> None:
    final = command(plan([scene(0)]))
    assert final[final.index("-preset") + 1 : final.index("-preset") + 4] == [
        "medium",
        "-crf",
        "20",
    ]
    draft = command(plan([scene(0)], profile="draft"))
    assert draft[draft.index("-preset") + 1 : draft.index("-preset") + 4] == [
        "ultrafast",
        "-crf",
        "28",
    ]
    assert re.search(r"s=480x854|scale=480:854", graph_of(draft))


def test_join_lists_only_the_chunk_files_and_copies() -> None:
    paths = ["/work/chunk_0.mp4", "/work/chunk_1.mp4"]
    cmd = join_command(paths, "/work/chunks.txt", "/work/base.mp4")
    assert cmd[cmd.index("-f") : cmd.index("-f") + 5] == ["-f", "concat", "-safe", "0", "-i"]
    assert cmd[cmd.index("-i") + 1] == "/work/chunks.txt"
    assert cmd[cmd.index("-c") + 1] == "copy"
    assert cmd[-1] == "/work/base.mp4"
    assert concat_list(paths) == "file '/work/chunk_0.mp4'\nfile '/work/chunk_1.mp4'\n"


def test_concat_list_escapes_a_quote() -> None:
    assert concat_list(["/a'b/c.mp4"]) == "file '/a'\\''b/c.mp4'\n"


def test_join_of_one_chunk_is_refused() -> None:
    with pytest.raises(ValueError, match="at least two"):
        join_command(["/work/chunk_0.mp4"], "/work/chunks.txt", "/work/base.mp4")


def test_mux_re_encodes_the_audio_to_the_timeline_length() -> None:
    cmd = mux_command("/work/base.mp4", "/work/video.mp4", 5033.3333, 0.021)
    assert cmd[cmd.index("-itsoffset") + 1] == "-0.021000"
    assert cmd.count("-i") == 2
    assert cmd[cmd.index("-map") + 1] == "0:v:0"
    assert cmd.count("-map") == 2 and "1:a:0" in cmd
    assert cmd[cmd.index("-c:v") + 1] == "copy"
    assert cmd[cmd.index("-af") + 1] == (
        "aresample=async=1:first_pts=0,"
        "aformat=sample_fmts=fltp:channel_layouts=stereo,"
        "apad=whole_dur=5.0333,atrim=duration=5.0333"
    )
    tail = cmd[cmd.index("-c:a") :]
    assert tail[:6] == ["-c:a", "aac", "-b:a", "160k", "-ar", "48000"]
    assert tail[6:8] == ["-t", "5.0333"]
    assert "+faststart" in tail
    assert cmd[-1] == "/work/video.mp4"
