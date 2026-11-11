"""The fetch_video handler: a direct link through the guarded library, a platform link through
yt-dlp (a scripted runner stands in for it), both against a loopback server that stands in for
the forward proxy."""

from __future__ import annotations

import json
import logging
import subprocess
import threading
import time
from collections.abc import Callable, Iterator
from pathlib import Path
from typing import Any

import pytest
import yt_dlp
from conftest import job_body
from test_url_fetch import idle, proxy, serving

from flowcortex_worker import telemetry
from flowcortex_worker.contracts.media_job import MediaJob
from flowcortex_worker.errors import PermanentError, TransientError
from flowcortex_worker.handlers import HandlerOutcome
from flowcortex_worker.handlers.fetch_video import _FORMATS, handle
from flowcortex_worker.runtime import ProcessKilled, ProcessRunner

PROXY = "http://egress-fetch:4750"
YOUTUBE = "https://www.youtube.com/watch?v=abc"
MP4 = "http://cdn.example.com/clip.mp4"
NOT_PLAYABLE = "The link did not give a playable video"
Script = Callable[[int, list[str]], tuple[int, str, str]]


def make_job(params: dict[str, Any], **change: Any) -> MediaJob:
    return MediaJob.model_validate(
        job_body(capability="fetch_video", inputs=[], params=params, **change)
    )


class Runner(ProcessRunner):
    """Real ffprobe and ffmpeg; yt-dlp is whatever `script(attempt, argv)` says it did."""

    def __init__(self, script: Script | None = None) -> None:
        super().__init__()
        self.script = script
        self.ytdlp: list[list[str]] = []

    def run(self, argv: list[str]) -> subprocess.CompletedProcess[bytes]:
        if argv[1:3] != ["-m", "yt_dlp"]:
            return super().run(argv)
        self.ytdlp.append(argv)
        assert self.script is not None, "yt-dlp must not start"
        code, out, err = self.script(len(self.ytdlp), argv)
        return subprocess.CompletedProcess(argv, code, out.encode(), err.encode())


def output_template(argv: list[str]) -> str:
    return argv[argv.index("-o") + 1]


def write_download(
    argv: list[str],
    source: Path,
    *,
    ext: str = "mp4",
    title: str = "Demo",
    extractor: str = "youtube",
) -> tuple[int, str, str]:
    template = output_template(argv)
    Path(template.replace("%(ext)s", ext)).write_bytes(source.read_bytes())
    Path(template.replace("%(ext)s", "info.json")).write_text(
        json.dumps({"title": title, "extractor": extractor})
    )
    return 0, "", ""


def failing(text: str) -> Script:
    return lambda _attempt, _argv: (1, "", text)


def encode(path: Path, *inputs: str, codecs: list[str]) -> Path:
    command = ["ffmpeg", "-v", "error", "-y"]
    for source in inputs:
        command += ["-f", "lavfi", "-i", source]
    subprocess.run([*command, *codecs, str(path)], check=True)
    return path


@pytest.fixture(scope="session")
def clip(tmp_path_factory: pytest.TempPathFactory) -> Path:
    return encode(
        tmp_path_factory.mktemp("media") / "clip.mp4",
        "testsrc=duration=3:size=640x360:rate=25",
        "sine=duration=3",
        codecs=["-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest"],
    )


@pytest.fixture(scope="session")
def silent_clip(tmp_path_factory: pytest.TempPathFactory) -> Path:
    return encode(
        tmp_path_factory.mktemp("media") / "silent.mp4",
        "testsrc=duration=2:size=320x240:rate=25",
        codecs=["-c:v", "libx264", "-pix_fmt", "yuv420p"],
    )


@pytest.fixture(scope="session")
def sound(tmp_path_factory: pytest.TempPathFactory) -> Path:
    return encode(
        tmp_path_factory.mktemp("media") / "sound.m4a", "sine=duration=2", codecs=["-c:a", "aac"]
    )


@pytest.fixture(autouse=True)
def configured_proxy(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("FETCH_PROXY_URL", PROXY)
    monkeypatch.delenv("FETCH_PLATFORM_LINKS_ENABLED", raising=False)
    monkeypatch.delenv("FETCH_UPSTREAM_ROTATES", raising=False)


def names(outcome: HandlerOutcome) -> list[str]:
    return [file.name for file in outcome.files]


def refused(job: MediaJob, runner: ProcessRunner) -> PermanentError:
    with pytest.raises(PermanentError) as caught:
        handle(job, {}, runner)
    return caught.value


# --- recorded outputs and facts ----------------------------------------------------------------


def test_direct_mp4_link_records_video_audio_and_facts(
    clip: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    runner = Runner()
    with proxy(serving(headers={"Content-Type": "video/mp4"}, body=clip.read_bytes())) as (
        proxy_url,
        seen,
    ):
        monkeypatch.setenv("FETCH_PROXY_URL", proxy_url)
        outcome = handle(make_job({"url": MP4}), {}, runner)

    assert seen.targets == [MP4]
    assert runner.ytdlp == []
    assert names(outcome) == ["video", "audio"]
    video, audio = outcome.files
    assert (video.mime, audio.mime) == ("video/mp4", "audio/mp4")
    assert isinstance(audio.body, Path)
    probed = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "stream=codec_name", "-of", "csv=p=0",
         str(audio.body)],
        capture_output=True,
        text=True,
        check=True,
    )  # fmt: skip
    assert probed.stdout.strip() == "aac"
    assert outcome.data["durationSec"] == pytest.approx(3.0, abs=0.2)
    assert (outcome.data["width"], outcome.data["height"]) == (640, 360)
    assert outcome.data["fps"] == 25.0
    assert outcome.data["platform"] == "cdn.example.com"
    assert outcome.data["sourceUrl"] == MP4
    runner.close()


def test_a_video_without_a_sound_track_is_recorded_without_audio(
    silent_clip: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    runner = Runner()
    with proxy(serving(body=silent_clip.read_bytes())) as (proxy_url, _seen):
        monkeypatch.setenv("FETCH_PROXY_URL", proxy_url)
        outcome = handle(make_job({"url": MP4}), {}, runner)

    assert names(outcome) == ["video"]
    runner.close()


def test_platform_link_without_audio_sidecar_records_one_video(clip: Path) -> None:
    runner = Runner(lambda _n, argv: write_download(argv, clip, title="Demo"))

    outcome = handle(make_job({"url": YOUTUBE, "extractAudio": False}), {}, runner)

    assert names(outcome) == ["video"]
    assert outcome.files[0].mime == "video/mp4"
    assert outcome.data["title"] == "Demo"
    assert outcome.data["platform"] == "youtube"
    runner.close()


def test_audio_only_records_one_audio_output(sound: Path) -> None:
    runner = Runner(lambda _n, argv: write_download(argv, sound, ext="m4a"))

    outcome = handle(make_job({"url": YOUTUBE, "quality": "audio_only"}), {}, runner)

    assert names(outcome) == ["audio"]
    assert outcome.files[0].mime == "audio/mp4"
    assert "height" not in outcome.data
    runner.close()


# --- the proxy and the link are checked before any connection or process ----------------------


@pytest.mark.parametrize("configured", ["", "egress-fetch:4750", "https://egress-fetch:4750"])
def test_no_usable_proxy_fails_before_any_process(
    monkeypatch: pytest.MonkeyPatch, configured: str
) -> None:
    monkeypatch.setenv("FETCH_PROXY_URL", configured)
    runner = Runner()

    error = refused(make_job({"url": YOUTUBE}), runner)

    assert error.code == "provider_capability_unavailable"
    assert runner.ytdlp == []


@pytest.mark.parametrize("url", ["file:///etc/passwd", "-o/tmp/x", "ftp://x.example/a.mp4"])
def test_a_link_that_is_not_http_never_starts_a_process(url: str) -> None:
    runner = Runner()

    error = refused(make_job({"url": url}), runner)

    assert error.code == "input_rejected"
    assert runner.ytdlp == []


def test_yt_dlp_argument_list() -> None:
    runner = Runner(failing("This video is not available"))
    with pytest.raises(PermanentError):
        handle(make_job({"url": YOUTUBE}, maxInputBytes=1000), {}, runner)

    argv = runner.ytdlp[0]
    assert argv[-2:] == ["--", YOUTUBE]
    for flag in ("--ignore-config", "--no-cache-dir", "--no-playlist"):
        assert flag in argv
    assert argv[argv.index("--proxy") + 1] == PROXY
    assert argv[argv.index("--downloader") + 1] == "native"
    assert argv[argv.index("--max-filesize") + 1] == "1000"


@pytest.mark.parametrize(
    "params",
    [{"url": YOUTUBE, "quality": "4k"}, {"url": YOUTUBE, "extractAudio": "no"}, {}, {"url": ""}],
)
def test_params_that_break_the_contract_are_refused(params: dict[str, Any]) -> None:
    error = refused(make_job(params), Runner())

    assert error.code == "contract_violation"


def test_a_job_with_an_input_is_a_contract_violation(tmp_path: Path) -> None:
    with pytest.raises(PermanentError) as caught:
        handle(make_job({"url": YOUTUBE}), {"media": tmp_path / "x"}, Runner())

    assert caught.value.code == "contract_violation"


# --- caps and the watchdog ---------------------------------------------------------------------


def test_a_merged_file_over_the_cap_is_refused_and_removed() -> None:
    runner = Runner(lambda _n, argv: write_oversize(argv))

    error = refused(make_job({"url": YOUTUBE}, maxInputBytes=1000), runner)

    assert error.code == "input_rejected"
    assert "1000-byte maximum" in error.message
    assert list(runner.scratch_dir().glob("out.mp4")) == []
    runner.close()


def write_oversize(argv: list[str]) -> tuple[int, str, str]:
    Path(output_template(argv).replace("%(ext)s", "mp4")).write_bytes(b"x" * 1001)
    return 0, "", ""


def test_a_format_over_the_cap_that_yt_dlp_skips_is_not_transient() -> None:
    skipped = "[download] File is larger than max-filesize (2000 bytes > 1000 bytes). Aborting."
    runner = Runner(lambda _n, _argv: (0, skipped, ""))

    error = refused(make_job({"url": YOUTUBE}), runner)

    assert error.code == "input_rejected"
    runner.close()


def test_the_watchdog_kills_a_running_yt_dlp(monkeypatch: pytest.MonkeyPatch) -> None:
    runner = ProcessRunner()
    with proxy(idle) as (proxy_url, _seen):
        monkeypatch.setenv("FETCH_PROXY_URL", proxy_url)
        threading.Timer(1.5, runner.kill, args=("run_timeout",)).start()
        began = time.monotonic()
        with pytest.raises(ProcessKilled) as killed:
            handle(make_job({"url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ"}), {}, runner)

    assert killed.value.reason == "run_timeout"
    assert time.monotonic() - began < 15
    runner.close()


# --- rotation across player clients ------------------------------------------------------------


def clients(runner: Runner) -> list[str | None]:
    found: list[str | None] = []
    for argv in runner.ytdlp:
        extra = argv.index("--extractor-args") + 1 if "--extractor-args" in argv else None
        found.append(argv[extra] if extra else None)
    return found


def test_a_removed_video_is_tried_with_three_clients_then_refused() -> None:
    runner = Runner(failing("ERROR: [youtube] abc: This video is not available"))

    error = refused(make_job({"url": YOUTUBE}), runner)

    assert clients(runner) == [None, "youtube:player_client=android", "youtube:player_client=ios"]
    assert error.code == "input_rejected"
    assert error.message == (
        "The video at https://www.youtube.com cannot be downloaded: "
        "it was removed, is private or is blocked in this region"
    )


@pytest.fixture
def job_fields_on_log_records() -> Iterator[None]:
    """The record factory the worker installs: job ids become attributes of every record."""
    original = logging.getLogRecordFactory()
    telemetry._add_job_fields_to_records()
    yield
    logging.setLogRecordFactory(original)


@pytest.mark.usefixtures("job_fields_on_log_records")
def test_an_attempt_log_line_inside_a_job_scope_does_not_collide_with_the_job_ids() -> None:
    job = make_job({"url": YOUTUBE})
    ids = {
        "jobId": str(job.jobId),
        "runId": str(job.runId),
        "correlationId": str(job.runId),
        "attempt": job.attempt,
        "deliveryCount": 0,
        "capability": "fetch_video",
    }
    runner = Runner(failing("ERROR: [youtube] abc: This video is not available"))

    with telemetry.job_scope(ids):
        error = refused(job, runner)

    assert error.code == "input_rejected"
    assert len(runner.ytdlp) == 3


def test_the_second_client_can_succeed(clip: Path) -> None:
    def script(attempt: int, argv: list[str]) -> tuple[int, str, str]:
        if attempt == 1:
            return 1, "", "ERROR: This video is not available"
        return write_download(argv, clip)

    runner = Runner(script)

    outcome = handle(make_job({"url": YOUTUBE}), {}, runner)

    assert clients(runner) == [None, "youtube:player_client=android"]
    assert names(outcome) == ["video", "audio"]
    runner.close()


def test_a_proxy_refusal_stops_at_once() -> None:
    runner = Runner(failing("Tunnel connection failed: 407 Request Rejected by Proxy"))

    error = refused(make_job({"url": YOUTUBE}), runner)

    assert len(runner.ytdlp) == 1
    assert error.code == "input_rejected"


BOT_CHECK = "ERROR: [youtube] abc: Sign in to confirm you're not a bot"


def test_a_bot_check_without_a_rotating_upstream_is_final_after_three_attempts() -> None:
    runner = Runner(failing(BOT_CHECK))

    error = refused(make_job({"url": YOUTUBE}), runner)

    assert len(runner.ytdlp) == 3
    assert error.message == (
        "The video at https://www.youtube.com cannot be downloaded: "
        "the platform blocked the server's address"
    )


def test_a_bot_check_with_a_rotating_upstream_is_transient(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("FETCH_UPSTREAM_ROTATES", "true")
    runner = Runner(failing(BOT_CHECK))

    with pytest.raises(TransientError):
        handle(make_job({"url": YOUTUBE}), {}, runner)

    assert len(runner.ytdlp) == 3


def test_http_403_without_a_rotating_upstream_is_the_blocked_message() -> None:
    runner = Runner(failing("ERROR: unable to download video data: HTTP Error 403: Forbidden"))

    error = refused(make_job({"url": YOUTUBE}), runner)

    assert error.message.endswith("the platform blocked the server's address")


def test_a_signed_link_never_reaches_a_message_or_a_log_line(
    caplog: pytest.LogCaptureFixture, monkeypatch: pytest.MonkeyPatch
) -> None:
    caplog.set_level(logging.DEBUG)
    platform = Runner(failing("ERROR: This video is not available"))
    signed = "https://www.youtube.com/watch?v=abc&token=s3cr3t"
    messages = [refused(make_job({"url": signed}), platform).message]
    with proxy(serving("404 Not Found")) as (proxy_url, _seen):
        monkeypatch.setenv("FETCH_PROXY_URL", proxy_url)
        direct = "http://cdn.example.com/a.mp4?token=s3cr3t"
        messages.append(refused(make_job({"url": direct}), Runner()).message)

    for text in [*messages, caplog.text]:
        assert "s3cr3t" not in text
        assert "/a.mp4" not in text
        assert "watch" not in text


# --- the result must be playable media of the asked kind ---------------------------------------


def test_a_link_that_answers_an_html_page_is_not_a_playable_video(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runner = Runner()
    with proxy(serving(headers={"Content-Type": "text/html"}, body=b"<html>no</html>")) as (
        proxy_url,
        _seen,
    ):
        monkeypatch.setenv("FETCH_PROXY_URL", proxy_url)
        error = refused(make_job({"url": MP4}), runner)

    assert error.code == "input_rejected"
    assert error.message == NOT_PLAYABLE
    assert not (runner.scratch_dir() / "source").exists()
    runner.close()


def test_audio_only_from_a_video_without_sound_is_not_playable(
    silent_clip: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    runner = Runner()
    with proxy(serving(body=silent_clip.read_bytes())) as (proxy_url, _seen):
        monkeypatch.setenv("FETCH_PROXY_URL", proxy_url)
        error = refused(make_job({"url": MP4, "quality": "audio_only"}), runner)

    assert error.message == NOT_PLAYABLE
    runner.close()


# --- platform links can be switched off --------------------------------------------------------


def test_switched_off_platform_links_never_start_yt_dlp(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("FETCH_PLATFORM_LINKS_ENABLED", "false")
    runner = Runner()

    error = refused(make_job({"url": YOUTUBE}), runner)

    assert (error.code, error.message) == (
        "input_rejected",
        "Links to video platforms are turned off",
    )
    assert runner.ytdlp == []


def test_a_direct_link_still_downloads_while_platform_links_are_off(
    clip: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("FETCH_PLATFORM_LINKS_ENABLED", "false")
    runner = Runner()
    with proxy(serving(body=clip.read_bytes())) as (proxy_url, _seen):
        monkeypatch.setenv("FETCH_PROXY_URL", proxy_url)
        outcome = handle(make_job({"url": MP4}), {}, runner)

    assert names(outcome) == ["video", "audio"]
    runner.close()


# --- a direct link that redirects --------------------------------------------------------------


def test_a_redirecting_direct_link_gets_one_generic_yt_dlp_attempt(
    clip: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("FETCH_PLATFORM_LINKS_ENABLED", "false")
    runner = Runner(lambda _n, argv: write_download(argv, clip, extractor="generic"))
    with proxy(serving("302 Found", {"Location": "http://storage.example.net/x.mp4"})) as (
        proxy_url,
        seen,
    ):
        monkeypatch.setenv("FETCH_PROXY_URL", proxy_url)
        outcome = handle(make_job({"url": MP4}), {}, runner)

    assert len(seen.requests) == 1
    [argv] = runner.ytdlp
    assert argv[argv.index("--use-extractors") + 1] == "generic"
    assert argv[argv.index("--proxy") + 1] == proxy_url
    assert argv[-2:] == ["--", MP4]
    assert "--extractor-args" not in argv
    assert names(outcome) == ["video", "audio"]
    assert outcome.data["platform"] == "cdn.example.com"
    runner.close()


def test_a_redirecting_direct_link_is_not_retried_when_yt_dlp_fails(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runner = Runner(failing("ERROR: Unsupported URL"))
    with proxy(serving("302 Found", {"Location": "http://storage.example.net/x.mp4"})) as (
        proxy_url,
        _seen,
    ):
        monkeypatch.setenv("FETCH_PROXY_URL", proxy_url)
        error = refused(make_job({"url": MP4}), runner)

    assert len(runner.ytdlp) == 1
    assert error.code == "input_rejected"


def test_a_direct_link_that_is_not_found_never_starts_yt_dlp(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runner = Runner()
    with proxy(serving("404 Not Found")) as (proxy_url, _seen):
        monkeypatch.setenv("FETCH_PROXY_URL", proxy_url)
        error = refused(make_job({"url": MP4}), runner)

    assert error.code == "input_rejected"
    assert runner.ytdlp == []


# --- yt-dlp fetches only what its own downloader can fetch through the proxy -------------------


@pytest.mark.parametrize("quality", ["best", "1080p", "720p", "audio_only"])
def test_every_alternative_of_the_format_selector_carries_the_protocol_filter(
    quality: str,
) -> None:
    runner = Runner(failing("This video is not available"))
    with pytest.raises(PermanentError):
        handle(make_job({"url": YOUTUBE, "quality": quality}), {}, runner)

    selector = runner.ytdlp[0][runner.ytdlp[0].index("-f") + 1]
    assert selector == _FORMATS[quality]
    for alternative in selector.split("/"):
        for part in alternative.split("+"):
            assert "[protocol~=" in part


@pytest.mark.parametrize("quality", ["best", "1080p", "720p", "audio_only"])
def test_the_selector_never_picks_a_protocol_the_native_downloader_cannot_proxy(
    quality: str,
) -> None:
    def candidate(format_id: str, protocol: str, **extra: Any) -> dict[str, Any]:
        return {"format_id": format_id, "protocol": protocol, "ext": "mp4", "url": "x", **extra}

    formats = [
        candidate("rtmp", "rtmp", vcodec="h264", acodec="aac", height=720),
        candidate("rtsp", "rtsp", vcodec="h264", acodec="aac", height=720),
        candidate("f4m", "f4m", vcodec="h264", acodec="aac", height=720),
        candidate("hls", "m3u8_native", vcodec="h264", acodec="aac", height=480),
    ]
    selector = yt_dlp.YoutubeDL({"quiet": True}).build_format_selector(_FORMATS[quality])

    picked = list(selector({"formats": formats, "incomplete_formats": False}))
    only_rtmp = list(selector({"formats": formats[:1], "incomplete_formats": False}))

    assert [f["format_id"] for f in picked] == ["hls"]
    assert only_rtmp == []


def test_only_an_unfetchable_format_is_refused_without_another_attempt() -> None:
    runner = Runner(failing("ERROR: [youtube] abc: Requested format is not available"))

    error = refused(make_job({"url": YOUTUBE}), runner)

    assert len(runner.ytdlp) == 1
    assert error.code == "input_rejected"
    assert "no downloadable format" in error.message
