"""The compose audio graph: the voice alone needs none, a background is ducked under the voice,
effects are not, and the sum is limited. Inputs are listed by role; the picture is input 0."""

from __future__ import annotations

import re

from flowcortex_worker.assembly.compose import BG_DUCK, audio_graph

GAINS = {"voice": 6.8, "background": -10.6, "effects": 3.0}


def ducked_and_key(graph: str) -> tuple[str, str]:
    match = re.search(r"(\[[^\]]+\])(\[[^\]]+\])sidechaincompress=", graph)
    assert match, graph
    return match.group(1), match.group(2)


def test_the_duck_setting_is_xoods() -> None:
    assert BG_DUCK == "threshold=0.05:ratio=4:attack=20:release=400"


def test_voice_only_needs_no_graph() -> None:
    assert audio_graph("null", None, False, GAINS, 30.04) is None


def test_a_background_file_is_ducked_under_the_padded_voice() -> None:
    result = audio_graph("null", "file", False, GAINS, 30.88)

    assert result is not None
    assert result.inputs == ("video", "background", "voice")
    graph = result.graph
    assert graph.startswith("[0:v]null[v];")
    assert "[1:a]volume=-10.60dB,aformat=channel_layouts=stereo,apad=whole_dur=30.880[bg]" in graph
    assert "[2:a]volume=6.80dB,aformat=channel_layouts=stereo,asplit=2[v1][vsc]" in graph
    assert "[vsc]apad=whole_dur=30.880[vscpad]" in graph
    assert f"[bg][vscpad]sidechaincompress={BG_DUCK}[bgduck]" in graph
    assert graph.endswith(
        "[bgduck][v1]amix=inputs=2:duration=longest:normalize=0,alimiter=limit=0.97[aout]"
    )
    assert graph.count("sidechaincompress") == 1


def test_a_generated_videos_own_music_is_read_from_the_picture_input() -> None:
    result = audio_graph("null", "video", False, GAINS, 10.0)

    assert result is not None
    assert result.inputs == ("video", "voice")
    assert re.search(r"\[0:a\]volume=-10\.60dB[^;]*\[bg\];", result.graph)
    assert ducked_and_key(result.graph)[0] == "[bg]"
    assert "[1:a]volume=6.80dB" in result.graph


def test_effects_without_background_are_mixed_unducked_at_their_gain() -> None:
    result = audio_graph("null", None, True, GAINS, 30.04)

    assert result is not None
    assert result.inputs == ("video", "voice", "effects")
    graph = result.graph
    assert "sidechaincompress" not in graph
    assert (
        "[2:a]volume=3.00dB,aformat=channel_layouts=stereo,"
        "atrim=0:30.040,apad=whole_dur=30.040[sfx]"
    ) in graph
    assert "[1:a]volume=6.80dB,aformat=channel_layouts=stereo[v1]" in graph
    assert "amix=inputs=2:duration=longest:normalize=0,alimiter=limit=0.97[aout]" in graph


def test_background_and_effects_duck_only_the_background() -> None:
    result = audio_graph("null", "file", True, GAINS, 30.04)

    assert result is not None
    assert result.inputs == ("video", "background", "voice", "effects")
    graph = result.graph
    assert graph.count("sidechaincompress") == 1
    ducked, key = ducked_and_key(graph)
    assert re.search(rf"\[1:a\][^;]*{re.escape(ducked)};", graph)
    assert key == "[vscpad]"
    assert (
        "[bgduck][v1][sfx]amix=inputs=3:duration=longest:normalize=0,alimiter=limit=0.97[aout]"
    ) in graph


def test_effects_after_the_music_from_the_picture_keep_their_own_input() -> None:
    result = audio_graph("null", "video", True, GAINS, 30.04)

    assert result is not None
    assert result.inputs == ("video", "voice", "effects")
    assert re.search(r"\[2:a\]volume=3\.00dB[^;]*atrim=0:", result.graph)
    assert re.search(r"\[0:a\][^;]*\[bg\];", result.graph)


def test_the_video_chain_is_applied_to_the_picture() -> None:
    chain = "tpad=stop_mode=clone:stop_duration=3.270"

    result = audio_graph(chain, "file", False, GAINS, 9.14)

    assert result is not None
    assert result.graph.startswith(f"[0:v]{chain}[v];")
