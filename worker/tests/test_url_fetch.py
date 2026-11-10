"""The guarded URL download: URL checks and status mapping without sockets, the exchange against
loopback servers that stand in for the forward proxy."""

from __future__ import annotations

import http.client
import logging
import ssl
import threading
import time
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

import pytest

from flowcortex_worker.errors import PermanentError, TransientError
from flowcortex_worker.runtime import ProcessKilled, ProcessRunner
from flowcortex_worker.url_fetch import Fetched, classify_status, download, parse_target

Behaviour = Callable[[BaseHTTPRequestHandler], None]


# --- parse_target -----------------------------------------------------------------------------


@pytest.mark.parametrize(
    "url",
    [
        "file:///etc/passwd",
        "ftp://h/x",
        "data:,x",
        "https:///x",
        "http://",
        "https://user:secret@example.com/a.mp4",
        "https://:secret@example.com/a.mp4",
        "http://h:0/x",
        "http://h:65536/x",
        "http://h:abc/x",
        "http://[2001:db8::1/x",
        "http://a..b/x",
        "http://" + "a" * 64 + ".example/x",
        "http://h/a b",
        "http://h/a\tb",
        "http://h/a\r\nX: y",
        "http://h/a\x7fb",
        "",
    ],
)
def test_parse_target_refuses(url: str) -> None:
    with pytest.raises(PermanentError) as caught:
        parse_target(url)
    assert caught.value.code == "input_rejected"


def test_parse_target_messages_hold_no_secret_and_no_path() -> None:
    with pytest.raises(PermanentError) as credentials:
        parse_target("https://user:secret@example.com/a.mp4")
    assert "secret" not in str(credentials.value)
    with pytest.raises(PermanentError) as control:
        parse_target("http://h/a\r\nX: y")
    assert "/a" not in str(control.value)


def test_parse_target_accepts_internal_name_with_port() -> None:
    target = parse_target("http://minio:9000/x?y=1")
    assert (target.scheme, target.host, target.port) == ("http", "minio", 9000)
    assert target.request_target == "http://minio:9000/x?y=1"
    assert target.label == "http://minio"


def test_parse_target_sends_percent_encoding_as_written() -> None:
    target = parse_target("http://h/a%0d%0a")
    assert target.request_target == "http://h/a%0d%0a"


def test_parse_target_percent_encodes_non_ascii_path_and_query() -> None:
    target = parse_target("https://h/ä.mp4?sig=ü")
    assert target.request_target == "/%C3%A4.mp4?sig=%C3%BC"
    assert parse_target("http://h/ä").request_target == "http://h/%C3%A4"


def test_parse_target_refuses_unencodable_path() -> None:
    with pytest.raises(PermanentError) as caught:
        parse_target("http://h/\udc80")
    assert caught.value.code == "input_rejected"


def test_parse_target_encodes_idn_host_as_punycode() -> None:
    target = parse_target("https://bücher.example/x")
    assert target.host == "xn--bcher-kva.example"
    assert target.port == 443
    assert target.request_target == "/x"
    assert target.label == "https://xn--bcher-kva.example"


def test_parse_target_accepts_ipv6_literal() -> None:
    target = parse_target("http://[2001:db8::1]:8080/x")
    assert target.host == "2001:db8::1"
    assert target.port == 8080
    assert target.request_target == "http://[2001:db8::1]:8080/x"
    assert target.label == "http://[2001:db8::1]"


def test_parse_target_defaults_empty_path_and_drops_fragment() -> None:
    assert parse_target("https://h").request_target == "/"
    assert parse_target("http://h#frag").request_target == "http://h/"


# --- classify_status --------------------------------------------------------------------------


def test_classify_status_success_is_none() -> None:
    assert classify_status(200, None) is None


@pytest.mark.parametrize("status", [206, 301, 302, 307, 400, 401, 403, 404, 407, 410, 451])
def test_classify_status_permanent(status: int) -> None:
    error = classify_status(status, None)
    assert isinstance(error, PermanentError)
    assert error.code == "input_rejected"


@pytest.mark.parametrize("status", [408, 429, 500, 502, 503, 504])
def test_classify_status_transient(status: int) -> None:
    error = classify_status(status, None)
    assert isinstance(error, TransientError)
    assert error.retry_after_seconds is None


def test_classify_status_reads_integer_retry_after_only() -> None:
    seconds = classify_status(429, "1")
    assert isinstance(seconds, TransientError)
    assert seconds.retry_after_seconds == 1
    for value in ("Wed, 21 Oct 2026 07:28:00 GMT", "soon", "-3", "1.5", ""):
        error = classify_status(503, value)
        assert isinstance(error, TransientError)
        assert error.retry_after_seconds is None


# --- the exchange -----------------------------------------------------------------------------


@dataclass
class Seen:
    connections: int = 0
    requests: list[tuple[str, str]] = field(default_factory=list)

    @property
    def targets(self) -> list[str]:
        return [path for _command, path in self.requests]


class _Server(ThreadingHTTPServer):
    daemon_threads = True
    block_on_close = False

    def __init__(self, behaviour: Behaviour, seen: Seen, stop: threading.Event) -> None:
        super().__init__(("127.0.0.1", 0), _Handler)
        self.behaviour = behaviour
        self.seen = seen
        self.stop = stop

    def get_request(self) -> Any:
        request = super().get_request()
        self.seen.connections += 1
        return request

    def handle_error(self, request: Any, client_address: Any) -> None:
        pass


class _Handler(BaseHTTPRequestHandler):
    server: _Server

    def log_message(self, format: str, *args: Any) -> None:
        pass

    def _serve(self) -> None:
        self.server.seen.requests.append((self.command, self.path))
        self.server.behaviour(self)

    do_GET = _serve
    do_CONNECT = _serve


@contextmanager
def proxy(behaviour: Behaviour) -> Iterator[tuple[str, Seen]]:
    seen = Seen()
    stop = threading.Event()
    server = _Server(behaviour, seen, stop)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_address[1]}", seen
    finally:
        stop.set()
        server.shutdown()
        server.server_close()
        thread.join(5)


def reply(
    h: BaseHTTPRequestHandler,
    status: str = "200 OK",
    headers: dict[str, str] | None = None,
    body: bytes = b"",
    *,
    length: bool = True,
) -> None:
    lines = [f"HTTP/1.1 {status}"]
    merged = dict(headers or {})
    if length:
        merged.setdefault("Content-Length", str(len(body)))
    lines += [f"{name}: {value}" for name, value in merged.items()]
    h.wfile.write(("\r\n".join(lines) + "\r\n\r\n").encode("latin-1") + body)
    h.wfile.flush()


def serving(
    status: str = "200 OK", headers: dict[str, str] | None = None, body: bytes = b""
) -> Behaviour:
    return lambda h: reply(h, status, headers, body)


def trickle(h: BaseHTTPRequestHandler, data: bytes, interval: float = 0.2) -> None:
    stop = h.server.stop  # type: ignore[attr-defined]
    for index in range(len(data)):
        if stop.wait(interval):
            return
        try:
            h.wfile.write(data[index : index + 1])
            h.wfile.flush()
        except OSError:
            return


def idle(h: BaseHTTPRequestHandler) -> None:
    h.server.stop.wait(30)  # type: ignore[attr-defined]


def refuse(url: str, dest: Path, proxy_url: str, **kwargs: Any) -> PermanentError | TransientError:
    params: dict[str, Any] = {
        "max_bytes": 1000,
        "deadline_seconds": 10,
        "abort": ProcessRunner(),
        "proxy_url": proxy_url,
    }
    params.update(kwargs)
    with pytest.raises((PermanentError, TransientError)) as caught:
        download(url, dest, **params)
    assert not dest.exists()
    error = caught.value
    assert isinstance(error, PermanentError | TransientError)
    return error


def fetch(url: str, dest: Path, proxy_url: str, **kwargs: Any) -> Fetched:
    params: dict[str, Any] = {
        "max_bytes": 1000,
        "deadline_seconds": 10,
        "abort": ProcessRunner(),
        "proxy_url": proxy_url,
    }
    params.update(kwargs)
    return download(url, dest, **params)


@pytest.fixture
def dest(tmp_path: Path) -> Path:
    return tmp_path / "clip.bin"


def test_downloads_bytes_and_reports_content_type(
    dest: Path, caplog: pytest.LogCaptureFixture
) -> None:
    with (
        proxy(serving(headers={"Content-Type": "video/mp4"}, body=b"abc")) as (proxy_url, seen),
        caplog.at_level(logging.INFO, logger="flowcortex_worker.url_fetch"),
    ):
        fetched = fetch("http://cdn.example.com/a.mp4?sig=1", dest, proxy_url)
    assert fetched == Fetched(bytes=3, content_type="video/mp4")
    assert dest.read_bytes() == b"abc"
    assert dest.stat().st_mode & 0o777 == 0o600
    assert seen.requests == [("GET", "http://cdn.example.com/a.mp4?sig=1")]
    record = next(r for r in caplog.records if r.getMessage() == "url downloaded")
    assert record.__dict__["source"] == "http://cdn.example.com"
    assert record.__dict__["bytes"] == 3
    assert "sig=1" not in caplog.text



def test_internal_host_named_in_no_proxy_still_goes_through_the_proxy(
    dest: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("NO_PROXY", "minio")
    monkeypatch.setenv("no_proxy", "minio")
    monkeypatch.setenv("HTTP_PROXY", "http://127.0.0.1:1")
    with proxy(serving(body=b"abc")) as (proxy_url, seen):
        fetch("http://minio:9000/flowcortex-media/x", dest, proxy_url)
    assert seen.targets == ["http://minio:9000/flowcortex-media/x"]


def test_ipv6_literal_is_sent_in_absolute_form(dest: Path) -> None:
    with proxy(serving(body=b"abc")) as (proxy_url, seen):
        fetch("http://[2001:db8::1]:8080/x", dest, proxy_url)
    assert seen.targets == ["http://[2001:db8::1]:8080/x"]


def test_https_goes_through_a_connect_tunnel_and_a_407_is_a_permanent_refusal(
    dest: Path,
) -> None:
    with proxy(serving("407 Proxy Authentication Required", {"X-Smokescreen-Error": "x"})) as (
        proxy_url,
        seen,
    ):
        error = refuse("https://cdn.example.com/a.mp4?sig=1", dest, proxy_url)
    assert seen.requests == [("CONNECT", "cdn.example.com:443")]
    assert isinstance(error, PermanentError)
    assert error.code == "input_rejected"
    assert "cdn.example.com" in str(error)
    assert "a.mp4" not in str(error)
    assert "sig=1" not in str(error)


def test_tunnel_refusal_with_429_is_transient_without_a_hint(dest: Path) -> None:
    with proxy(serving("429 Too Many Requests", {"Retry-After": "1"})) as (proxy_url, _seen):
        error = refuse("https://cdn.example.com/a.mp4", dest, proxy_url)
    assert isinstance(error, TransientError)
    assert error.retry_after_seconds is None


def test_tunnel_refusal_with_502_is_transient(dest: Path) -> None:
    with proxy(serving("502 Bad Gateway")) as (proxy_url, _seen):
        error = refuse("https://nowhere.example/a.mp4", dest, proxy_url)
    assert isinstance(error, TransientError)


def test_unparsable_tunnel_error_is_transient(dest: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    def tunnel(self: http.client.HTTPConnection) -> None:
        raise OSError("something else\nTunnel connection failed: 407 x")

    monkeypatch.setattr(http.client.HTTPConnection, "_tunnel", tunnel)
    with proxy(serving()) as (proxy_url, _seen):
        error = refuse("https://cdn.example.com/a.mp4", dest, proxy_url)
    assert isinstance(error, TransientError)
    assert "cdn.example.com" in str(error)
    assert "something else" not in str(error)


def test_certificate_failure_is_permanent_and_names_the_host(
    dest: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    def wrap_socket(*_args: Any, **_kwargs: Any) -> Any:
        raise ssl.SSLCertVerificationError(1, "certificate verify failed: /secret/path")

    monkeypatch.setattr(ssl.SSLContext, "wrap_socket", wrap_socket)
    with proxy(lambda h: reply(h, "200 Connection established", length=False)) as (
        proxy_url,
        _seen,
    ):
        error = refuse("https://cdn.example.com/a.mp4", dest, proxy_url)
    assert isinstance(error, PermanentError)
    assert error.code == "input_rejected"
    assert "cdn.example.com" in str(error)
    assert "secret" not in str(error)


def test_wrapped_exception_text_never_reaches_the_message(
    dest: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    def putrequest(self: http.client.HTTPConnection, method: str, url: str, **_kw: Any) -> None:
        raise http.client.InvalidURL(repr(url))

    monkeypatch.setattr(http.client.HTTPConnection, "putrequest", putrequest)
    with proxy(serving()) as (proxy_url, _seen):
        error = refuse("http://cdn.example.com/private/a.mp4", dest, proxy_url)
    assert isinstance(error, TransientError)
    assert "cdn.example.com" in str(error)
    assert "InvalidURL" in str(error)
    assert "private" not in str(error)
    assert "a.mp4" not in str(error)


def test_plain_407_with_smokescreen_header_is_permanent(dest: Path) -> None:
    with proxy(serving("407 Proxy Authentication Required", {"X-Smokescreen-Error": "denied"})) as (
        proxy_url,
        _seen,
    ):
        error = refuse("http://169.254.169.254/", dest, proxy_url)
    assert isinstance(error, PermanentError)
    assert error.code == "input_rejected"
    assert "169.254.169.254" in str(error)


def test_redirect_is_refused_and_not_followed(dest: Path) -> None:
    with proxy(serving("302 Found", {"Location": "http://minio:9000/secret"})) as (
        proxy_url,
        seen,
    ):
        error = refuse("http://cdn.example.com/a.mp4", dest, proxy_url)
    assert isinstance(error, PermanentError)
    assert error.code == "input_rejected"
    assert len(seen.requests) == 1


@pytest.mark.parametrize(
    ("status", "retry_after", "transient"),
    [("503 Service Unavailable", "7", True), ("404 Not Found", None, False)],
)
def test_error_statuses_are_classified_with_the_hint(
    dest: Path, status: str, retry_after: str | None, transient: bool
) -> None:
    headers = {"Retry-After": retry_after} if retry_after else None
    with proxy(serving(status, headers)) as (proxy_url, _seen):
        error = refuse("http://cdn.example.com/a.mp4", dest, proxy_url)
    assert isinstance(error, TransientError if transient else PermanentError)
    if isinstance(error, TransientError):
        assert error.retry_after_seconds == 7


def test_presigned_url_is_not_echoed(dest: Path) -> None:
    with proxy(serving("403 Forbidden")) as (proxy_url, _seen):
        error = refuse("http://cdn.example.com/a.mp4?X-Amz-Signature=deadbeef", dest, proxy_url)
    assert isinstance(error, PermanentError)
    assert "cdn.example.com" in str(error)
    assert "X-Amz-Signature" not in str(error)
    assert "deadbeef" not in str(error)
    assert "a.mp4" not in str(error)


def test_declared_length_over_the_cap_is_refused_before_a_byte_is_written(dest: Path) -> None:
    written: list[bool] = []

    def behaviour(h: BaseHTTPRequestHandler) -> None:
        reply(h, headers={"Content-Length": "1001"}, body=b"x" * 10, length=False)
        written.append(dest.exists())

    with proxy(behaviour) as (proxy_url, _seen):
        error = refuse("http://h/x", dest, proxy_url, max_bytes=1000)
    assert isinstance(error, PermanentError)
    assert error.code == "input_rejected"
    assert written in ([], [False])


def test_chunked_body_over_the_cap_is_refused(dest: Path) -> None:
    def behaviour(h: BaseHTTPRequestHandler) -> None:
        reply(h, headers={"Transfer-Encoding": "chunked"}, length=False)
        for _ in range(2):
            h.wfile.write(b"%x\r\n" % 501 + b"x" * 501 + b"\r\n")
        h.wfile.write(b"0\r\n\r\n")

    with proxy(behaviour) as (proxy_url, _seen):
        error = refuse("http://h/x", dest, proxy_url, max_bytes=1000)
    assert isinstance(error, PermanentError)
    assert error.code == "input_rejected"


def test_body_of_exactly_the_cap_succeeds(dest: Path) -> None:
    with proxy(serving(body=b"x" * 1000)) as (proxy_url, _seen):
        assert fetch("http://h/x", dest, proxy_url, max_bytes=1000).bytes == 1000
    assert dest.stat().st_size == 1000


def test_body_shorter_than_declared_is_transient(dest: Path) -> None:
    with proxy(
        lambda h: reply(h, headers={"Content-Length": "10"}, body=b"abcd", length=False)
    ) as (
        proxy_url,
        _seen,
    ):
        error = refuse("http://h/x", dest, proxy_url)
    assert isinstance(error, TransientError)


def test_deadline_cuts_a_server_that_never_answers(dest: Path) -> None:
    with proxy(idle) as (proxy_url, _seen):
        started = time.monotonic()
        error = refuse("http://h/x", dest, proxy_url, deadline_seconds=1)
    assert time.monotonic() - started < 3
    assert isinstance(error, PermanentError)
    assert error.code == "input_rejected"
    assert "deadline" in str(error)


def test_deadline_cuts_a_status_line_sent_one_byte_at_a_time(dest: Path) -> None:
    with proxy(lambda h: trickle(h, b"HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nabcde")) as (
        proxy_url,
        _seen,
    ):
        started = time.monotonic()
        error = refuse("http://h/x", dest, proxy_url, deadline_seconds=1)
    assert time.monotonic() - started < 3
    assert isinstance(error, PermanentError)


def test_deadline_on_a_close_delimited_body_is_a_rejection_not_a_success(dest: Path) -> None:
    def behaviour(h: BaseHTTPRequestHandler) -> None:
        reply(h, length=False)
        trickle(h, b"x" * 100)

    with proxy(behaviour) as (proxy_url, _seen):
        error = refuse("http://h/x", dest, proxy_url, deadline_seconds=1, max_bytes=10_000)
    assert isinstance(error, PermanentError)
    assert error.code == "input_rejected"


def test_deadline_on_a_length_delimited_body_is_permanent_not_transient(dest: Path) -> None:
    def behaviour(h: BaseHTTPRequestHandler) -> None:
        reply(h, headers={"Content-Length": "100"}, length=False)
        trickle(h, b"x" * 100)

    with proxy(behaviour) as (proxy_url, _seen):
        error = refuse("http://h/x", dest, proxy_url, deadline_seconds=1)
    assert isinstance(error, PermanentError)
    assert error.code == "input_rejected"


def test_kill_during_a_slow_body_raises_process_killed_promptly(dest: Path) -> None:
    runner = ProcessRunner()
    killed_at: list[float] = []

    def kill() -> None:
        killed_at.append(time.monotonic())
        runner.kill("consumer cancelled")

    def behaviour(h: BaseHTTPRequestHandler) -> None:
        reply(h, headers={"Content-Length": "100"}, length=False)
        trickle(h, b"x" * 100)

    with proxy(behaviour) as (proxy_url, _seen):
        timer = threading.Timer(0.3, kill)
        timer.start()
        try:
            with pytest.raises(ProcessKilled) as caught:
                fetch("http://h/x", dest, proxy_url, abort=runner, deadline_seconds=30)
        finally:
            timer.cancel()
        assert time.monotonic() - killed_at[0] < 1
    assert caught.value.reason == "consumer cancelled"
    assert not dest.exists()


def test_kill_before_download_raises_without_a_connection(dest: Path) -> None:
    runner = ProcessRunner()
    runner.kill("watchdog")
    with proxy(serving(body=b"abc")) as (proxy_url, seen), pytest.raises(ProcessKilled):
        fetch("http://h/x", dest, proxy_url, abort=runner)
    assert seen.connections == 0
    assert not dest.exists()


def test_kill_after_a_finished_download_changes_nothing(dest: Path) -> None:
    runner = ProcessRunner()
    with proxy(serving(body=b"abc")) as (proxy_url, _seen):
        fetch("http://h/x", dest, proxy_url, abort=runner)
    runner.kill("late")
    assert dest.read_bytes() == b"abc"


def test_existing_destination_is_refused_and_left_alone(dest: Path) -> None:
    dest.write_bytes(b"keep")
    with proxy(serving(body=b"abc")) as (proxy_url, seen), pytest.raises(FileExistsError):
        fetch("http://h/x", dest, proxy_url)
    assert dest.read_bytes() == b"keep"
    assert seen.connections == 0


@pytest.mark.parametrize("proxy_url", ["", "socks5://h:1", "http://h", "https://h:1", "h:1"])
def test_missing_or_unusable_proxy_fails_closed(dest: Path, proxy_url: str) -> None:
    with proxy(serving(body=b"abc")) as (_url, seen), pytest.raises(PermanentError) as caught:
        fetch("http://h/x", dest, proxy_url)
    assert caught.value.code == "provider_capability_unavailable"
    assert seen.connections == 0
    assert not dest.exists()


def test_unset_proxy_environment_fails_closed(dest: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("FETCH_PROXY_URL", raising=False)
    with pytest.raises(PermanentError) as caught:
        download("http://h/x", dest, max_bytes=1000, deadline_seconds=5, abort=ProcessRunner())
    assert caught.value.code == "provider_capability_unavailable"


def test_bad_url_is_refused_before_the_proxy_is_considered(dest: Path) -> None:
    with pytest.raises(PermanentError) as caught:
        fetch("file:///etc/passwd", dest, "")
    assert caught.value.code == "input_rejected"
