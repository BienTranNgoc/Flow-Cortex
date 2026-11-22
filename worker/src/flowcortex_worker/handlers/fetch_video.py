"""`fetch_video`: download a link through the forward proxy; record the video and its audio.

A link that ends in a media file name goes through the guarded download library; anything else
goes to yt-dlp. Every request leaves through the proxy, and the proxy is the only way out.
"""

from __future__ import annotations

import json
import logging
import sys
from collections.abc import Mapping
from pathlib import Path, PurePosixPath
from typing import Any
from urllib.parse import urlsplit

from ..config import FetchSettings
from ..contracts.media_job import MediaJob
from ..errors import PermanentError, TransientError
from ..fetch_failures import Failure, classify
from ..runtime import ProcessRunner
from ..url_fetch import _proxy_address, download, parse_target
from . import HandlerOutcome, OutputFile
from .probe import _pictures, _still_image

log = logging.getLogger(__name__)

VIDEO_OUTPUT = "video"
AUDIO_OUTPUT = "audio"
AUDIO_MIME = "audio/mp4"
SOURCE_NAME = "source"
AUDIO_NAME = "audio.m4a"
INFO_NAME = "out.info.json"
MAX_URL_LENGTH = 2048
# The direct download stops this long before the run deadline so the probe and the audio fit.
DEADLINE_RESERVE_SECONDS = 30
MAX_TITLE_LENGTH = 300
REDIRECT_REFUSAL = "redirects are not followed"
NOT_PLAYABLE = "The link did not give a playable video"

DIRECT_EXTENSIONS = frozenset(
    {".mp4", ".mov", ".m4v", ".webm", ".mkv", ".mp3", ".m4a", ".wav", ".aac"}
)
# Default client first, then the two that still serve videos the default reports unavailable.
PLAYER_CLIENTS: tuple[str | None, ...] = (None, "android", "ios")
QUALITIES = ("best", "1080p", "720p", "audio_only")

_VIDEO_MIME = {
    ".mp4": "video/mp4",
    ".m4v": "video/x-m4v",
    ".mov": "video/quicktime",
    ".webm": "video/webm",
    ".mkv": "video/x-matroska",
}
_NOT_MEDIA_SUFFIXES = frozenset({".json", ".part", ".ytdl", ".temp"})

# Only protocols yt-dlp's own downloader fetches through --proxy: anything else (rtmp, rtsp, mms,
# f4m, ism) would start an external program that does not honour it.
_PROTOCOLS = "[protocol~='^(https?|m3u8_native|m3u8|http_dash_segments)$']"


def _alternatives(*alternatives: str) -> str:
    return "/".join(alternatives)


def _best(limit: str = "") -> str:
    return _alternatives(f"bv*{limit}{_PROTOCOLS}+ba{_PROTOCOLS}", f"b{limit}{_PROTOCOLS}")


_FORMATS = {
    "best": _best(),
    "1080p": _best("[height<=1080]"),
    "720p": _best("[height<=720]"),
    "audio_only": _alternatives(f"ba{_PROTOCOLS}", f"b{_PROTOCOLS}"),
}


def handle(job: MediaJob, inputs: Mapping[str, Path], processes: ProcessRunner) -> HandlerOutcome:
    if inputs:
        raise PermanentError("contract_violation", "fetch_video takes no inputs")
    url, quality, extract_audio = _params(job)
    settings = FetchSettings()
    proxy = settings.fetch_proxy_url
    _proxy_address(proxy)
    target = parse_target(url)
    direct = PurePosixPath(urlsplit(url).path).suffix.lower() in DIRECT_EXTENSIONS
    if not direct and not settings.fetch_platform_links_enabled:
        raise PermanentError("input_rejected", "Links to video platforms are turned off")

    scratch = processes.scratch_dir()
    info: Mapping[str, Any] = {}
    content_type: str | None = None
    route = "direct"
    if direct:
        try:
            source, content_type = _download_direct(url, proxy, job, scratch, processes)
        except PermanentError as refused:
            if not refused.message.endswith(REDIRECT_REFUSAL):
                raise
            route = "redirect"
            source, info = _fetch_with_ytdlp(
                url, proxy, job, quality, scratch, processes, settings, generic=True
            )
    else:
        route = "platform"
        source, info = _fetch_with_ytdlp(
            url, proxy, job, quality, scratch, processes, settings, generic=False
        )

    report = _probe(source, processes)
    audio_streams = [s for s in report.get("streams", []) if s.get("codec_type") == "audio"]
    moving = _pictures(report.get("streams", []))
    if quality == "audio_only":
        playable = bool(audio_streams)
    else:
        playable = bool(moving) and not _still_image(report)
    if not playable:
        source.unlink(missing_ok=True)
        raise PermanentError("input_rejected", NOT_PLAYABLE)

    files: list[OutputFile] = []
    if quality != "audio_only":
        files.append(OutputFile(VIDEO_OUTPUT, _video_mime(source, url, content_type), source))
    if audio_streams and (quality == "audio_only" or extract_audio):
        audio = _extract_audio(source, scratch, processes)
        files.append(OutputFile(AUDIO_OUTPUT, AUDIO_MIME, audio))
    log.info(
        "fetch finished",
        extra={"source": target.label, "route": route, "outputs": [f.name for f in files]},
    )
    return HandlerOutcome(data=_facts(report, moving, info, url, target.host), files=files)


def _params(job: MediaJob) -> tuple[str, str, bool]:
    url = job.params.get("url")
    if not isinstance(url, str) or not url or len(url) > MAX_URL_LENGTH:
        raise PermanentError(
            "contract_violation", "fetch_video param 'url' must be a non-empty string"
        )
    quality = job.params.get("quality", "best")
    if quality not in QUALITIES:
        raise PermanentError(
            "contract_violation",
            f"fetch_video param 'quality' must be one of {', '.join(QUALITIES)}",
        )
    extract_audio = job.params.get("extractAudio", True)
    if not isinstance(extract_audio, bool):
        raise PermanentError(
            "contract_violation", "fetch_video param 'extractAudio' must be a boolean"
        )
    return url, str(quality), extract_audio


def _download_direct(
    url: str, proxy: str, job: MediaJob, scratch: Path, processes: ProcessRunner
) -> tuple[Path, str | None]:
    dest = scratch / SOURCE_NAME
    fetched = download(
        url,
        dest,
        max_bytes=job.maxInputBytes,
        deadline_seconds=max(1, job.maxRuntimeSeconds - DEADLINE_RESERVE_SECONDS),
        abort=processes,
        proxy_url=proxy,
    )
    return dest, fetched.content_type


def _fetch_with_ytdlp(
    url: str,
    proxy: str,
    job: MediaJob,
    quality: str,
    scratch: Path,
    processes: ProcessRunner,
    settings: FetchSettings,
    *,
    generic: bool,
) -> tuple[Path, Mapping[str, Any]]:
    """Runs yt-dlp once with its generic extractor, or once per player client until one works."""
    label = parse_target(url).label
    failure: Failure = classify("", url, upstream_rotates=settings.fetch_upstream_rotates)
    for attempt, client in enumerate((None,) if generic else PLAYER_CLIENTS, start=1):
        processes.check()
        for leftover in scratch.glob("out.*"):
            leftover.unlink(missing_ok=True)
        argv = _ytdlp_argv(url, proxy, job.maxInputBytes, quality, scratch, client, generic)
        completed = processes.run(argv)
        media = _written_media(scratch, job.maxInputBytes)
        if completed.returncode == 0 and media is not None:
            return media, _info(scratch)
        text = completed.stderr.decode("utf-8", "replace") + completed.stdout.decode(
            "utf-8", "replace"
        )
        failure = classify(text, url, upstream_rotates=settings.fetch_upstream_rotates)
        log.warning(
            "download attempt failed",
            extra={
                "source": label,
                "downloadAttempt": attempt,
                "permanent": failure.permanent,
                "exitCode": completed.returncode,
            },
        )
        if failure.stop_rotating:
            break
    if failure.permanent:
        raise PermanentError("input_rejected", failure.message)
    raise TransientError(failure.message)


def _ytdlp_argv(
    url: str,
    proxy: str,
    max_bytes: int,
    quality: str,
    scratch: Path,
    client: str | None,
    generic: bool,
) -> list[str]:
    argv = [
        sys.executable, "-m", "yt_dlp",
        "--ignore-config", "--no-cache-dir",
        "--proxy", proxy,
        "--downloader", "native",
        "--no-playlist", "--no-warnings", "--no-progress",
        "--retries", "2", "--socket-timeout", "30",
        "--max-filesize", str(max_bytes),
        "--match-filters", "!is_live",
        "-f", _FORMATS[quality],
        "--write-info-json",
    ]  # fmt: skip
    if quality != "audio_only":
        argv += ["--merge-output-format", "mp4", "-S", "vcodec:h264,res,ext:mp4"]
    if generic:
        argv += ["--use-extractors", "generic"]
    elif client is not None:
        argv += ["--extractor-args", f"youtube:player_client={client}"]
    return [*argv, "-o", str(scratch / "out.%(ext)s"), "--", url]


def _written_media(scratch: Path, max_bytes: int) -> Path | None:
    """The one media file yt-dlp wrote; a file over the cap is removed and refused."""
    written = [
        path
        for path in scratch.glob("out.*")
        if path.suffix not in _NOT_MEDIA_SUFFIXES and path.is_file()
    ]
    for path in written:
        if path.stat().st_size > max_bytes:
            path.unlink(missing_ok=True)
            raise PermanentError(
                "input_rejected", f"the download is over the {max_bytes}-byte maximum"
            )
    return max(written, key=lambda path: path.stat().st_size, default=None)


def _info(scratch: Path) -> Mapping[str, Any]:
    try:
        loaded = json.loads((scratch / INFO_NAME).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    return loaded if isinstance(loaded, dict) else {}


def _probe(media: Path, processes: ProcessRunner) -> dict[str, Any]:
    completed = processes.run(
        [
            "ffprobe", "-v", "error", "-protocol_whitelist", "file",
            "-print_format", "json", "-show_format", "-show_streams", str(media),
        ]
    )  # fmt: skip
    try:
        report = json.loads(completed.stdout) if completed.returncode == 0 else None
    except ValueError:
        report = None
    if not isinstance(report, dict):
        media.unlink(missing_ok=True)
        raise PermanentError("input_rejected", NOT_PLAYABLE)
    return report


def _extract_audio(source: Path, scratch: Path, processes: ProcessRunner) -> Path:
    output = scratch / AUDIO_NAME
    completed = processes.run(
        [
            "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
            "-protocol_whitelist", "file", "-i", str(source),
            "-map", "0:a:0", "-vn", "-c:a", "aac", "-b:a", "128k", str(output),
        ]
    )  # fmt: skip
    if completed.returncode != 0 or not output.is_file() or output.stat().st_size == 0:
        output.unlink(missing_ok=True)
        raise PermanentError("audio_extraction_failed", "the audio track could not be extracted")
    return output


def _video_mime(source: Path, url: str, content_type: str | None) -> str:
    received = (content_type or "").split(";")[0].strip().lower()
    if received.startswith("video/"):
        return received
    suffix = source.suffix.lower() or PurePosixPath(urlsplit(url).path).suffix.lower()
    return _VIDEO_MIME.get(suffix, "application/octet-stream")


def _facts(
    report: Mapping[str, Any],
    moving: list[Mapping[str, Any]],
    info: Mapping[str, Any],
    url: str,
    host: str,
) -> dict[str, Any]:
    video = moving[0] if moving else None
    duration = (report.get("format") or {}).get("duration")
    title = info.get("title")
    extractor = str(info.get("extractor") or "").lower()
    facts: dict[str, Any] = {
        "durationSec": round(float(duration), 3) if duration is not None else None,
        "width": video.get("width") if video else None,
        "height": video.get("height") if video else None,
        "fps": _fps(video) if video else None,
        "title": title.strip()[:MAX_TITLE_LENGTH] if isinstance(title, str) else None,
        "platform": extractor if extractor and extractor != "generic" else host,
        "sourceUrl": url,
    }
    return {key: value for key, value in facts.items() if value is not None}


def _fps(stream: Mapping[str, Any]) -> float | None:
    for key in ("avg_frame_rate", "r_frame_rate"):
        numerator, _, denominator = str(stream.get(key) or "").partition("/")
        try:
            rate = float(numerator) / float(denominator or 1)
        except (ValueError, ZeroDivisionError):
            continue
        if rate > 0:
            return round(rate, 3)
    return None
