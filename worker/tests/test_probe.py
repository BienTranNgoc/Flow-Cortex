"""`probe` runs ffprobe on the downloaded input and returns a summary plus the full report."""

from __future__ import annotations

import json
import subprocess
from pathlib import Path

import pytest
from conftest import (
    FakeConnection,
    FakeStorage,
    assert_valid,
    job_body,
    make_clip,
    published,
    run_consumer,
    settled,
)

from flowcortex_worker.contracts.media_job import MediaJob
from flowcortex_worker.errors import PermanentError
from flowcortex_worker.handlers import probe
from flowcortex_worker.runtime import ProcessRunner


@pytest.fixture(scope="module")
def clip(tmp_path_factory: pytest.TempPathFactory) -> Path:
    return make_clip(tmp_path_factory.mktemp("clip") / "clip.mp4")


def job() -> MediaJob:
    return MediaJob.model_validate(job_body())


def test_clip_is_summarised(clip: Path) -> None:
    outcome = probe.handle(job(), {"media": clip}, ProcessRunner())

    assert outcome.data["container"] == "mov,mp4,m4a,3gp,3g2,mj2"
    assert 950 <= outcome.data["durationMs"] <= 1100
    assert (outcome.data["width"], outcome.data["height"]) == (320, 240)
    assert outcome.data["videoCodec"] == "h264"
    assert outcome.data["audioCodec"] == "aac"
    [report] = outcome.files
    assert (report.name, report.mime) == ("video-details.json", "application/json")
    details = json.loads(report.body)
    assert {s["codec_type"] for s in details["streams"]} == {"video", "audio"}
    assert "format" in details


def test_audio_only_leaves_the_video_fields_null() -> None:
    report = {
        "format": {"format_name": "wav", "duration": "2.500000"},
        "streams": [{"codec_type": "audio", "codec_name": "pcm_s16le"}],
    }

    assert probe.summarize(report) == {
        "container": "wav",
        "durationMs": 2500,
        "width": None,
        "height": None,
        "videoCodec": None,
        "audioCodec": "pcm_s16le",
    }


def make_song_with_cover(path: Path) -> Path:
    cover = path.with_suffix(".jpg")
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=64x64",
         "-frames:v", "1", "-c:v", "mjpeg", str(cover)],
        check=True,
    )  # fmt: skip
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "sine=duration=1", "-i", str(cover),
         "-map", "0:a", "-map", "1:v", "-c:a", "libmp3lame", "-c:v", "copy",
         "-disposition:v", "attached_pic", str(path)],
        check=True,
    )  # fmt: skip
    return path


def test_cover_art_of_an_audio_file_is_not_reported_as_video(tmp_path: Path) -> None:
    song = make_song_with_cover(tmp_path / "song.mp3")

    outcome = probe.handle(job(), {"media": song}, ProcessRunner())

    assert outcome.data["container"] == "mp3"
    assert outcome.data["audioCodec"] == "mp3"
    assert (outcome.data["width"], outcome.data["height"], outcome.data["videoCodec"]) == (
        None,
        None,
        None,
    )
    [report] = outcome.files
    assert any(s["disposition"]["attached_pic"] == 1 for s in json.loads(report.body)["streams"])


def test_a_video_listed_after_its_cover_art_is_the_one_summarised() -> None:
    report = {
        "format": {"format_name": "mov,mp4,m4a,3gp,3g2,mj2", "duration": "1.000000"},
        "streams": [
            {"codec_type": "video", "codec_name": "mjpeg", "width": 64, "height": 64,
             "disposition": {"attached_pic": 1}},
            {"codec_type": "video", "codec_name": "h264", "width": 320, "height": 240,
             "disposition": {"attached_pic": 0}},
        ],
    }  # fmt: skip

    summary = probe.summarize(report)

    assert (summary["videoCodec"], summary["width"], summary["height"]) == ("h264", 320, 240)


def test_text_file_named_like_a_video_fails_permanently(tmp_path: Path) -> None:
    text = tmp_path / "clip.mp4"
    text.write_text("not a video")

    media_job, runner = job(), ProcessRunner()

    with pytest.raises(PermanentError) as failed:
        probe.handle(media_job, {"media": text}, runner)

    assert failed.value.code == "probe_failed"


def test_job_without_the_media_input_is_a_contract_violation(clip: Path) -> None:
    media_job, runner = job(), ProcessRunner()

    with pytest.raises(PermanentError) as failed:
        probe.handle(media_job, {"source": clip}, runner)

    assert failed.value.code == "contract_violation"


def test_report_is_stored_as_video_details_at_the_step_key(clip: Path) -> None:
    conn = FakeConnection()
    body = job_body(stepOrder=3)
    conn.deliver(body)
    storage = FakeStorage(content=clip.read_bytes())

    run_consumer(conn, storage=storage, until=settled, timeout=30)

    key = f"runs/{body['runId']}/steps/3/video-details.json"
    message = assert_valid(published(conn, "result")[0].body, "media-result")
    assert message["status"] == "completed"
    assert message["data"]["videoCodec"] == "h264"
    assert [o["objectKey"] for o in message["outputs"]] == [key]
    stored, mime = storage.puts[key]
    assert mime == "application/json"
    assert message["outputs"][0]["bytes"] == len(stored)
    assert json.loads(stored)["format"]["format_name"] == "mov,mp4,m4a,3gp,3g2,mj2"


def test_text_upload_ends_as_probe_failed_and_is_acked() -> None:
    conn = FakeConnection()
    conn.deliver(job_body())

    run_consumer(conn, storage=FakeStorage(content=b"not a video"), until=settled, timeout=30)

    message = assert_valid(published(conn, "result")[0].body, "media-result")
    assert message["error"]["code"] == "probe_failed"
    assert message["error"]["retryable"] is False
    assert conn.consume.acks == [1]


def test_a_still_image_is_not_a_video(tmp_path: Path) -> None:
    image = tmp_path / "clip.mp4"
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=64x64",
         "-frames:v", "1", "-f", "image2", "-c:v", "png", str(image)],
        check=True,
    )  # fmt: skip

    media_job, runner = job(), ProcessRunner()

    with pytest.raises(PermanentError) as failed:
        probe.handle(media_job, {"media": image}, runner)

    assert failed.value.code == "probe_failed"


class RecordingRunner(ProcessRunner):
    def __init__(self) -> None:
        super().__init__()
        self.argv: list[str] = []

    def run(self, argv: list[str]) -> subprocess.CompletedProcess[bytes]:
        self.argv = argv
        return super().run(argv)


def test_ffprobe_may_open_local_files_only(clip: Path) -> None:
    runner = RecordingRunner()

    probe.handle(job(), {"media": clip}, runner)

    flag = runner.argv.index("-protocol_whitelist")
    assert runner.argv[flag + 1] == "file"
    assert flag < runner.argv.index(str(clip))
