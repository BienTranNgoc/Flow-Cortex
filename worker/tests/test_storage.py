"""Inputs come from configured media buckets; outputs land at deterministic keys."""

from __future__ import annotations

import tempfile
import threading
import time
import uuid
from pathlib import Path
from typing import Any
from unittest import mock

import boto3
import pytest
from botocore.stub import Stubber
from conftest import MEDIA_BUCKET, UPLOAD_BUCKET, Silo, captured_logs, job_body

from flowcortex_worker.contracts.media_job import MediaJob, MediaJobInput
from flowcortex_worker.errors import PermanentError, TransientError
from flowcortex_worker.runtime import ProcessKilled, ProcessRunner
from flowcortex_worker.storage import Storage, output_key


def upload(silo: Silo, body: bytes) -> MediaJobInput:
    key = f"u-1/{uuid.uuid4()}"
    silo.client().put_object(Bucket=UPLOAD_BUCKET, Key=key, Body=body)
    return MediaJobInput(key="media", bucket=UPLOAD_BUCKET, objectKey=key)


def test_input_is_downloaded_to_a_temporary_file_removed_afterwards(silo: Silo) -> None:
    ref = upload(silo, b"0123456789")
    storage = Storage(silo.client(), MEDIA_BUCKET, UPLOAD_BUCKET)

    with storage.fetched(ref, max_bytes=10, abort=ProcessRunner()) as path:
        assert path.read_bytes() == b"0123456789"
        assert oct(path.stat().st_mode & 0o777) == "0o600"

    assert not path.exists()
    assert not path.parent.exists()


def test_temporary_file_is_removed_when_the_handler_fails(silo: Silo) -> None:
    ref = upload(silo, b"abc")
    storage = Storage(silo.client(), MEDIA_BUCKET, UPLOAD_BUCKET)

    with (
        pytest.raises(RuntimeError),
        storage.fetched(ref, max_bytes=10, abort=ProcessRunner()) as path,
    ):
        raise RuntimeError("handler failed")

    assert not path.exists()


def test_object_over_the_job_maximum_is_rejected_without_downloading(silo: Silo) -> None:
    ref = upload(silo, b"0123456789")
    client = silo.client()
    storage = Storage(client, MEDIA_BUCKET, UPLOAD_BUCKET)

    with (
        mock.patch.object(client, "get_object") as download,
        pytest.raises(PermanentError) as rejected,
        storage.fetched(ref, max_bytes=9, abort=ProcessRunner()),
    ):
        pass

    assert rejected.value.code == "input_rejected"
    assert "10 bytes" in rejected.value.message
    download.assert_not_called()


def test_missing_object_is_rejected(silo: Silo) -> None:
    ref = MediaJobInput(key="media", bucket=UPLOAD_BUCKET, objectKey=f"u-1/{uuid.uuid4()}")
    storage = Storage(silo.client(), MEDIA_BUCKET, UPLOAD_BUCKET)

    with (
        pytest.raises(PermanentError) as rejected,
        storage.fetched(ref, max_bytes=10, abort=ProcessRunner()),
    ):
        pass

    assert rejected.value.code == "input_rejected"
    assert "not found" in rejected.value.message


def test_unreachable_store_is_transient() -> None:
    import boto3
    from botocore.config import Config

    client = boto3.client(
        "s3",
        endpoint_url="http://127.0.0.1:1",
        region_name="us-east-1",
        aws_access_key_id="x",
        aws_secret_access_key="y",
        config=Config(retries={"max_attempts": 1}, connect_timeout=1),
    )
    ref = MediaJobInput(key="media", bucket=UPLOAD_BUCKET, objectKey="u-1/x")

    storage = Storage(client, MEDIA_BUCKET, UPLOAD_BUCKET)

    with pytest.raises(TransientError), storage.fetched(ref, max_bytes=10, abort=ProcessRunner()):
        pass


def test_output_asset_can_be_used_as_a_later_step_input(silo: Silo) -> None:
    key = "runs/one/steps/3/attempts/1/speech.wav"
    silo.client().put_object(Bucket=MEDIA_BUCKET, Key=key, Body=b"speech")
    ref = MediaJobInput(key="speech", bucket=MEDIA_BUCKET, objectKey=key)
    storage = Storage(silo.client(), MEDIA_BUCKET, UPLOAD_BUCKET)

    with storage.fetched(ref, max_bytes=10, abort=ProcessRunner()) as path:
        assert path.read_bytes() == b"speech"


def test_input_outside_configured_media_buckets_is_a_contract_violation(silo: Silo) -> None:
    ref = MediaJobInput(key="media", bucket="foreign-bucket", objectKey="runs/x/steps/1/a")
    storage = Storage(silo.client(), MEDIA_BUCKET, UPLOAD_BUCKET)

    with pytest.raises(PermanentError) as rejected, storage.fetched(
        ref, max_bytes=10, abort=ProcessRunner()
    ):
        pass
    assert rejected.value.code == "contract_violation"


def test_output_keys_are_deterministic_and_overwrite(silo: Silo) -> None:
    job = MediaJob.model_validate(job_body(stepOrder=2))
    key = output_key(job, "video-details.json")
    storage = Storage(silo.client(), MEDIA_BUCKET, UPLOAD_BUCKET)

    storage.put(key, b"first", "application/json", ProcessRunner())
    storage.put(key, b"second", "application/json", ProcessRunner())

    assert key == f"runs/{job.runId}/steps/2/video-details.json"
    head = silo.client().head_object(Bucket=MEDIA_BUCKET, Key=key)
    assert head["ContentType"] == "application/json"
    listed = silo.client().list_objects_v2(Bucket=MEDIA_BUCKET, Prefix=f"runs/{job.runId}/")
    assert [o["Key"] for o in listed["Contents"]] == [key]

def test_path_output_is_streamed_and_reports_its_size(tmp_path: Path) -> None:
    content = b"wave-data" * 1024
    output = tmp_path / "source-audio.wav"
    output.write_bytes(content)
    captured: dict[str, Any] = {}

    def consume_body(**kwargs: Any) -> None:
        body = kwargs["Body"]
        assert not isinstance(body, bytes)
        captured["content"] = body.read()
        captured["mime"] = kwargs["ContentType"]

    client = mock.Mock()
    client.put_object.side_effect = consume_body
    storage = Storage(client, MEDIA_BUCKET, UPLOAD_BUCKET)

    size = storage.put(
        "runs/r/steps/1/source-audio.wav",
        output,
        "audio/wav",
        ProcessRunner(),
    )

    assert size == len(content)
    assert captured == {"content": content, "mime": "audio/wav"}

class StalledBody:
    """A response body whose read blocks until the stream is closed, like a stuck socket."""

    def __init__(self) -> None:
        self.closed = threading.Event()

    def read(self, amount: int | None = None) -> bytes:
        self.closed.wait(30)
        raise ValueError("I/O operation on closed stream")

    def close(self) -> None:
        self.closed.set()


class StalledClient:
    def __init__(self) -> None:
        self.body = StalledBody()

    def head_object(self, **kwargs: Any) -> dict[str, Any]:
        return {"ContentLength": 10, "ETag": '"e1"'}

    def get_object(self, **kwargs: Any) -> dict[str, Any]:
        return {"Body": self.body}


def test_a_kill_stops_a_stalled_download_and_removes_the_file() -> None:
    storage = Storage(StalledClient(), MEDIA_BUCKET, UPLOAD_BUCKET)
    runner = ProcessRunner()
    ref = MediaJobInput(key="media", bucket=UPLOAD_BUCKET, objectKey="u-1/slow")
    threading.Timer(0.2, runner.kill, args=("run_timeout",)).start()
    before = set(Path(tempfile.gettempdir()).glob("flowcortex-job-*"))

    began = time.monotonic()
    with pytest.raises(ProcessKilled) as killed, storage.fetched(ref, 10, runner):
        pass

    assert killed.value.reason == "run_timeout"
    assert time.monotonic() - began < 2
    assert set(Path(tempfile.gettempdir()).glob("flowcortex-job-*")) == before


def test_a_kill_before_the_output_upload_stops_it() -> None:
    client = mock.Mock()
    runner = ProcessRunner()
    runner.kill("shutdown")
    storage = Storage(client, MEDIA_BUCKET, UPLOAD_BUCKET)

    with pytest.raises(ProcessKilled):
        storage.put("runs/r/steps/1/a", b"{}", "x", runner)

    client.put_object.assert_not_called()


def test_forbidden_input_is_transient_and_logged_for_the_operator() -> None:
    client = boto3.client(
        "s3",
        endpoint_url="http://127.0.0.1:1",
        region_name="us-east-1",
        aws_access_key_id="x",
        aws_secret_access_key="y",
    )
    stubber = Stubber(client)
    stubber.add_client_error("head_object", service_error_code="403", http_status_code=403)
    ref = MediaJobInput(key="media", bucket=UPLOAD_BUCKET, objectKey="u-1/x")

    storage = Storage(client, MEDIA_BUCKET, UPLOAD_BUCKET)
    with (
        stubber,
        captured_logs() as lines,
        pytest.raises(TransientError),
        storage.fetched(ref, 10, ProcessRunner()),
    ):
        pass

    [error] = [line for line in lines if line["level"] == "ERROR"]
    assert error["bucket"] == UPLOAD_BUCKET
    assert "http" not in str(error)


def test_object_replaced_after_the_size_check_is_rejected(silo: Silo) -> None:
    ref = upload(silo, b"0123456789")
    client = silo.client()
    storage = Storage(client, MEDIA_BUCKET, UPLOAD_BUCKET)
    head = client.head_object

    def head_then_replace(**kwargs: Any) -> Any:
        answer = head(**kwargs)
        client.put_object(Bucket=UPLOAD_BUCKET, Key=ref.objectKey, Body=b"9876543210")
        return answer

    with (
        mock.patch.object(client, "head_object", side_effect=head_then_replace),
        pytest.raises(PermanentError) as rejected,
        storage.fetched(ref, max_bytes=10, abort=ProcessRunner()),
    ):
        pass

    assert rejected.value.code == "input_rejected"
    assert "changed" in rejected.value.message


def test_leftover_job_directories_are_removed_at_startup(tmp_path: Path) -> None:
    from flowcortex_worker.storage import remove_leftovers

    stale = tmp_path / "flowcortex-job-abc123"
    stale.mkdir()
    (stale / "input").write_bytes(b"x" * 100)
    unrelated = tmp_path / "other-dir"
    unrelated.mkdir()

    assert remove_leftovers(tmp_path) == 1

    assert not stale.exists()
    assert unrelated.exists()
