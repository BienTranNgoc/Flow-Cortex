"""One ordered table classifies yt-dlp's error text; messages name the link's host only."""

from __future__ import annotations

import pytest

from flowcortex_worker.fetch_failures import classify

LINK = "https://www.youtube.com/watch?v=abc&t=s3cr3t"
HOST = "https://www.youtube.com"
BLOCKED = f"The video at {HOST} cannot be downloaded: the platform blocked the server's address"


def failure(text: str, *, rotates: bool = False) -> tuple[bool, bool]:
    result = classify(text, LINK, upstream_rotates=rotates)
    return result.permanent, result.stop_rotating


@pytest.mark.parametrize(
    "text",
    [
        "ERROR: Unable to download webpage: Tunnel connection failed: 407 Rejected by Proxy",
        "ERROR: HTTP Error 407: X-Smokescreen-Error: Request Rejected by Proxy",
    ],
)
def test_proxy_refusal_is_permanent_and_stops_rotation(text: str) -> None:
    assert failure(text) == (True, True)


def test_file_over_the_cap_is_permanent() -> None:
    text = "[download] File is larger than max-filesize (2000 bytes > 1000 bytes). Aborting."

    permanent, _stop = failure(text)

    assert permanent


def test_drm_is_permanent_and_stops_rotation() -> None:
    assert failure("ERROR: [youtube] abc: This video is DRM protected") == (True, True)


@pytest.mark.parametrize(
    "text",
    [
        "ERROR: [youtube] abc: This video is not available",
        "ERROR: [youtube] abc: Private video. Sign in if you've been granted access to this video",
        "ERROR: [youtube] abc: Join this channel to get access to members-only content",
        "ERROR: [youtube] abc: Video unavailable",
        "ERROR: [youtube] abc: This video is unavailable",
    ],
)
def test_unavailable_is_permanent_and_keeps_rotating(text: str) -> None:
    result = classify(text, LINK, upstream_rotates=False)

    assert (result.permanent, result.stop_rotating) == (True, False)
    assert result.message == (
        f"The video at {HOST} cannot be downloaded: "
        "it was removed, is private or is blocked in this region"
    )


@pytest.mark.parametrize(
    "text",
    [
        "ERROR: [youtube] abc: Sign in to confirm you're not a bot",
        "ERROR: unable to download video data: HTTP Error 403: Forbidden",
    ],
)
@pytest.mark.parametrize(("rotates", "permanent"), [(False, True), (True, False)])
def test_platform_block_is_final_unless_the_upstream_rotates(
    text: str, rotates: bool, permanent: bool
) -> None:
    result = classify(text, LINK, upstream_rotates=rotates)

    assert (result.permanent, result.stop_rotating) == (permanent, False)
    if permanent:
        assert result.message == BLOCKED


def test_login_required_is_permanent_and_keeps_rotating() -> None:
    assert failure("ERROR: [instagram] abc: login required to view this content") == (True, False)


@pytest.mark.parametrize(
    "text",
    [
        "ERROR: Unsupported URL: https://www.youtube.com/watch?v=abc",
        "ERROR: [generic] Unable to download webpage: no video formats found",
    ],
)
def test_unsupported_link_is_permanent_and_stops_rotation(text: str) -> None:
    assert failure(text) == (True, True)


def test_a_format_that_cannot_be_fetched_is_permanent_and_stops_rotation() -> None:
    result = classify(
        "ERROR: [youtube] abc: Requested format is not available", LINK, upstream_rotates=False
    )

    assert (result.permanent, result.stop_rotating) == (True, True)
    assert "no downloadable format" in result.message


def test_a_live_stream_is_permanent_and_stops_rotation() -> None:
    text = "[download] abc does not pass filter (!is_live), skipping .."

    assert failure(text) == (True, True)


@pytest.mark.parametrize("text", ["", "ERROR: something nobody has seen before", "timed out"])
def test_empty_and_unknown_text_is_transient(text: str) -> None:
    assert failure(text) == (False, False)


@pytest.mark.parametrize("rotates", [False, True])
@pytest.mark.parametrize(
    "text",
    [
        "Tunnel connection failed: 407 Request Rejected by Proxy",
        "File is larger than max-filesize",
        "DRM",
        "This video is not available",
        "Sign in to confirm you're not a bot",
        "HTTP Error 403: Forbidden",
        "Unsupported URL",
        "Requested format is not available",
        "login required",
        "something else",
        "",
    ],
)
def test_every_message_names_the_host_and_never_the_path_or_query(text: str, rotates: bool) -> None:
    message = classify(text, LINK, upstream_rotates=rotates).message

    assert HOST in message
    assert "watch" not in message
    assert "s3cr3t" not in message
