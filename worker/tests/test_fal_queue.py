from __future__ import annotations

import json
from collections.abc import Mapping
from typing import Any

import pytest

from flowcortex_worker.errors import TransientError
from flowcortex_worker.provider.fal_queue import FetchResponse, probe_fal_queue

STATUS_URL = "https://queue.fal.run/fal-ai/model/requests/1/status"
RESPONSE_URL = "https://queue.fal.run/fal-ai/model/requests/1"
MEDIA_URL = "https://v3.fal.media/files/a.mp4"
HANDLE = {"statusUrl": STATUS_URL, "responseUrl": RESPONSE_URL}
SUFFIXES = (".fal.media",)
HEADERS = {"Authorization": "Key fal-secret"}


def reply(status: int, body: Any = None, headers: Mapping[str, str] | None = None) -> FetchResponse:
    raw = b"" if body is None else json.dumps(body).encode()
    return status, headers or {}, raw


class FakeFetch:
    def __init__(self, routes: dict[tuple[str, str], FetchResponse]) -> None:
        self.routes = routes
        self.calls: list[tuple[str, str, Mapping[str, str]]] = []

    def __call__(self, method: str, url: str, headers: Mapping[str, str]) -> FetchResponse:
        self.calls.append((method, url, headers))
        return self.routes[(method, url)]


def probe(fetch: FakeFetch, handle: Mapping[str, Any] = HANDLE) -> str:
    return probe_fal_queue(handle, HEADERS, fetch, SUFFIXES)


def completed(media: FetchResponse, result: Any = None) -> FakeFetch:
    return FakeFetch(
        {
            ("GET", STATUS_URL): reply(200, {"status": "COMPLETED"}),
            ("GET", RESPONSE_URL): reply(
                200, result if result is not None else {"video": {"url": MEDIA_URL}}
            ),
            ("HEAD", MEDIA_URL): media,
        }
    )


@pytest.mark.parametrize(
    ("state", "expected"),
    [
        ("IN_QUEUE", "running"),
        ("IN_PROGRESS", "running"),
        ("FAILED", "failed"),
        ("ERROR", "failed"),
    ],
)
def test_status_maps_to_a_verdict(state: str, expected: str) -> None:
    fetch = FakeFetch({("GET", STATUS_URL): reply(200, {"status": state})})

    assert probe(fetch) == expected
    assert fetch.calls[0][2] == HEADERS


def test_completed_with_an_error_is_failed() -> None:
    fetch = FakeFetch({("GET", STATUS_URL): reply(200, {"status": "COMPLETED", "error": "boom"})})

    assert probe(fetch) == "failed"


@pytest.mark.parametrize("status", [404, 410])
def test_a_missing_status_is_gone(status: int) -> None:
    assert probe(FakeFetch({("GET", STATUS_URL): reply(status)})) == "gone"


def test_completed_with_live_media_succeeds_and_media_gets_no_key() -> None:
    fetch = completed(reply(200))

    assert probe(fetch) == "succeeded"
    assert fetch.calls[-1] == ("HEAD", MEDIA_URL, {})


@pytest.mark.parametrize("status", [403, 404, 410])
def test_expired_media_is_gone(status: int) -> None:
    assert probe(completed(reply(status))) == "gone"


@pytest.mark.parametrize("status", [403, 404, 410])
def test_a_missing_response_is_gone(status: int) -> None:
    fetch = FakeFetch(
        {
            ("GET", STATUS_URL): reply(200, {"status": "COMPLETED"}),
            ("GET", RESPONSE_URL): reply(status),
        }
    )

    assert probe(fetch) == "gone"


def test_a_result_naming_no_media_is_gone() -> None:
    assert probe(completed(reply(200), result={"seed": 1})) == "gone"


@pytest.mark.parametrize(
    "handle",
    [
        {"statusUrl": "https://evil.example/requests/1/status", "responseUrl": RESPONSE_URL},
        {"statusUrl": STATUS_URL, "responseUrl": "https://queue.fal.run.evil.example/x"},
        {"statusUrl": "http://queue.fal.run/x", "responseUrl": RESPONSE_URL},
        {"statusUrl": STATUS_URL},
        {},
    ],
)
def test_a_handle_pointing_elsewhere_is_gone_without_a_request(handle: dict[str, Any]) -> None:
    fetch = FakeFetch({})

    assert probe(fetch, handle) == "gone"
    assert fetch.calls == []


def test_a_server_error_raises() -> None:
    with pytest.raises(TransientError):
        probe(FakeFetch({("GET", STATUS_URL): reply(500)}))


def test_media_off_the_allowlist_raises_without_fetching_it() -> None:
    fetch = completed(reply(200), result={"video": {"url": "https://cdn.example.com/a.mp4"}})

    with pytest.raises(TransientError, match="allowed media hosts"):
        probe(fetch)
    assert all(call[0] == "GET" for call in fetch.calls)


def test_more_than_five_redirects_raise() -> None:
    loop = reply(302, headers={"Location": MEDIA_URL})
    fetch = completed(loop)

    with pytest.raises(TransientError, match="too many redirects"):
        probe(fetch)


def test_a_media_redirect_off_the_allowlist_raises() -> None:
    fetch = completed(reply(302, headers={"Location": "https://cdn.example.com/a.mp4"}))

    with pytest.raises(TransientError, match="allowed media hosts"):
        probe(fetch)


def test_a_status_redirect_off_the_queue_host_raises() -> None:
    fetch = FakeFetch(
        {("GET", STATUS_URL): reply(302, headers={"Location": "https://evil.example/s"})}
    )

    with pytest.raises(TransientError, match="off the queue host"):
        probe(fetch)


def test_the_key_never_follows_a_redirect_off_the_queue_host() -> None:
    fetch = FakeFetch(
        {
            ("GET", STATUS_URL): reply(
                302, headers={"Location": "https://queue.fal.run/other/status"}
            ),
            ("GET", "https://queue.fal.run/other/status"): reply(200, {"status": "IN_QUEUE"}),
        }
    )

    assert probe(fetch) == "running"
    assert all(call[2] == HEADERS for call in fetch.calls)
