"""A media worker's role is its queue and the capabilities it accepts, both checked at startup."""

from __future__ import annotations

import pytest

from flowcortex_worker.roles import RoleError, accepted_capabilities, media_queue

KNOWN = frozenset({"probe", "render_video"})


@pytest.mark.parametrize("raw", [None, "", "  "])
def test_unset_or_blank_queue_is_the_default_lane(raw: str | None) -> None:
    assert media_queue(raw) == "media.jobs"


def test_the_render_queue_is_accepted_as_written() -> None:
    assert media_queue("media.render.jobs") == "media.render.jobs"


def test_the_audio_queue_is_accepted_as_written() -> None:
    assert media_queue("media.audio.jobs") == "media.audio.jobs"


def test_unknown_queue_names_both_accepted_queues() -> None:
    with pytest.raises(RoleError) as refused:
        media_queue("media.preview.jobs")

    assert "media.preview.jobs" in str(refused.value)
    assert "media.jobs" in str(refused.value)
    assert "media.render.jobs" in str(refused.value)
    assert "media.audio.jobs" in str(refused.value)


def test_blanks_and_spaces_in_the_capability_list_are_ignored() -> None:
    assert accepted_capabilities(" render_video , ,", KNOWN) == frozenset({"render_video"})


@pytest.mark.parametrize("raw", [None, "", " , "])
def test_no_capability_list_accepts_everything(raw: str | None) -> None:
    assert accepted_capabilities(raw, KNOWN) is None


def test_unknown_capability_names_it_and_the_known_ones() -> None:
    with pytest.raises(RoleError) as refused:
        accepted_capabilities("render_video,video.preview", KNOWN)

    assert "video.preview" in str(refused.value)
    assert "probe" in str(refused.value)
    assert "render_video" in str(refused.value)
