"""Capabilities resolve through an explicit table; an unknown one never falls back."""

from __future__ import annotations

import json

import pytest
from conftest import FakeConnection, assert_valid, job_body, published, run_consumer, settled

from flowcortex_worker.errors import PermanentError
from flowcortex_worker.handlers import registry, render_video
from flowcortex_worker.roles import RoleError

BROKEN = "flowcortex_worker.handlers.not_installed"
TABLE = {
    "render_video": "flowcortex_worker.handlers.render_video",
    "probe": BROKEN,
}


@pytest.mark.parametrize("capability", ["unknown-thing", "", "echo"])
def test_unknown_capability_is_a_contract_violation(capability: str) -> None:
    with pytest.raises(PermanentError) as rejected:
        registry.resolve(capability)

    assert rejected.value.code == "contract_violation"


def test_job_naming_an_unknown_capability_is_answered_and_acked_without_a_handler() -> None:
    conn = FakeConnection()
    body = job_body(capability="unknown-thing")
    conn.deliver(body)

    run_consumer(conn, resolve=lambda c: pytest.fail("no handler may run"), until=settled)

    [result] = published(conn, "result")
    message = assert_valid(result.body, "media-result")
    assert message["jobId"] == body["jobId"]
    assert message["error"]["code"] == "contract_violation"
    assert message["error"]["retryable"] is False
    assert result.properties.correlation_id == body["runId"]
    assert conn.consume.acks == [1]


def test_body_without_usable_ids_is_dead_lettered() -> None:
    conn = FakeConnection()
    conn.deliver(json.dumps({"status": "done"}).encode())

    run_consumer(conn, until=settled)

    assert published(conn, "result") == []
    assert conn.consume.rejects == [(1, False)]


def test_a_handler_that_fails_to_import_is_skipped_when_unaccepted() -> None:
    handlers = registry.load_handlers(frozenset({"render_video"}), modules=TABLE)

    assert set(handlers) == {"render_video"}


def test_a_handler_that_fails_to_import_stops_the_worker_when_accepted() -> None:
    with pytest.raises(RoleError) as refused:
        registry.load_handlers(frozenset({"probe"}), modules=TABLE)

    assert "probe" in str(refused.value)


def test_no_accepted_list_loads_every_row() -> None:
    handlers = registry.load_handlers(None)

    assert set(handlers) == set(registry.HANDLER_MODULES)


def test_a_registered_capability_the_worker_does_not_accept_is_refused_without_a_handler() -> None:
    conn = FakeConnection()
    body = job_body(capability="probe")
    conn.deliver(body)
    render_only = registry.Registry(
        registry.load_handlers(frozenset({"render_video"})), frozenset({"render_video"})
    )

    run_consumer(conn, resolve=render_only.resolve, until=settled)

    [result] = published(conn, "result")
    message = assert_valid(result.body, "media-result")
    assert message["error"]["code"] == "contract_violation"
    assert message["error"]["retryable"] is False
    assert "render_video" in message["error"]["message"]
    assert conn.consume.acks == [1]


def test_the_default_registry_resolves_every_capability() -> None:
    default = registry.Registry(registry.load_handlers(None), None)

    assert default.resolve("render_video") is render_video.handle


def test_the_module_level_resolve_returns_the_render_handler() -> None:
    assert registry.resolve("render_video") is render_video.handle
