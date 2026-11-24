"""The filter graph of each derive effect, as pure text."""

from __future__ import annotations

import pytest

from flowcortex_worker.errors import PermanentError
from flowcortex_worker.video_ops import effects

SIZE = (640, 360)
RATE = "25/1"
FPS = 25.0
SECONDS = 2.0


def graph(effect: str, *, size: tuple[int, int] = SIZE, seconds: float = SECONDS) -> str:
    return effects.effect_filter(effect, *size, RATE, FPS, seconds)


def test_flash_fades_in_from_white_over_300_ms() -> None:
    assert "fade=t=in:st=0:d=0.3:color=white" in graph("flash")


def test_zoom_ramps_one_to_one_point_two_over_the_windows_frames() -> None:
    zoom = graph("zoom")

    assert "min(1+0.00408163*on,1.2)" in zoom
    assert "s=640x360" in zoom
    assert zoom.startswith("fps=25/1,zoompan=")


def test_zoom_on_a_one_frame_window_does_not_divide_by_zero() -> None:
    assert "min(1+0.20000000*on,1.2)" in graph("zoom", seconds=0.01)


def test_shake_scales_up_by_two_percent_and_crops_the_original_size_back() -> None:
    shake = graph("shake")

    assert "scale=664:374" in shake
    assert "crop=640:360" in shake


def test_shake_offsets_depend_on_the_frame_number_alone() -> None:
    shake = graph("shake")

    assert "sin(n*12.9898+78.233)" in shake
    assert "sin(n*39.3468+11.135)" in shake
    assert "random(" not in shake


def test_glitch_shifts_red_and_blue_on_three_of_every_ten_frames() -> None:
    glitch = graph("glitch")

    assert "rgbashift=rh=9:bh=-9" in glitch
    assert "lt(mod(n,10),3)" in glitch


def test_slowmo_stretches_the_first_half_to_the_whole_window() -> None:
    assert "trim=end=1.000000,setpts=2*(PTS-STARTPTS)" in graph("slowmo")


def test_slowmo_repeats_frames_up_to_the_source_rate() -> None:
    assert "fps=25/1" in graph("slowmo")


def test_an_odd_source_is_trimmed_to_even_sides_before_the_flash() -> None:
    assert graph("flash", size=(641, 361)).startswith("scale=trunc(iw/2)*2:trunc(ih/2)*2,")


def test_zoom_and_shake_size_their_output_to_even_sides_of_an_odd_source() -> None:
    assert "s=640x360" in graph("zoom", size=(641, 361))
    assert "crop=640:360" in graph("shake", size=(641, 361))


def test_an_unknown_effect_is_a_contract_violation_naming_effect() -> None:
    with pytest.raises(PermanentError) as caught:
        graph("vhs")

    assert caught.value.code == "contract_violation"
    assert "effect" in caught.value.message
    assert "vhs" in caught.value.message


@pytest.mark.parametrize("value", [None, 3, ["flash"], True])
def test_an_effect_that_is_not_a_name_is_refused_by_the_check(value: object) -> None:
    with pytest.raises(PermanentError) as caught:
        effects.check_effect(value)

    assert caught.value.code == "contract_violation"
    assert "effect" in caught.value.message
