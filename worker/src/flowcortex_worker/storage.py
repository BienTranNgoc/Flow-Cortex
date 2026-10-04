"""Object-store access: inputs by reference from the uploads bucket, outputs to the media one.

Errors name objects by bucket and key only: botocore messages carry the endpoint URL.
"""

from __future__ import annotations

import logging
import os
import shutil
import tempfile
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Protocol

import boto3
from botocore.config import Config
from botocore.exceptions import BotoCoreError, ClientError

from .config import Settings
from .contracts.media_job import MediaJob, MediaJobInput
from .errors import PermanentError, TransientError

log = logging.getLogger(__name__)

TEMP_PREFIX = "flowcortex-job-"
CHUNK_BYTES = 1024 * 1024
_MISSING = {"404", "NoSuchKey", "NotFound"}
_FORBIDDEN = {"403", "AccessDenied", "Forbidden"}
_CHANGED = {"412", "PreconditionFailed"}


class Abort(Protocol):
    """How a transfer learns that its job was killed (watchdog, cancel, shutdown)."""

    @property
    def killed(self) -> str | None: ...

    def check(self) -> None: ...

    def on_kill(self, callback: Callable[[], None]) -> None: ...


def output_key(job: MediaJob, name: str) -> str:
    return f"runs/{job.runId}/steps/{job.stepOrder}/{name}"


def client_from(settings: Settings) -> Any:
    return boto3.client(
        "s3",
        endpoint_url=settings.s3_endpoint,
        region_name=settings.s3_region,
        aws_access_key_id=settings.s3_access_key.get_secret_value(),
        aws_secret_access_key=settings.s3_secret_key.get_secret_value(),
        config=Config(
            s3={"addressing_style": "path" if settings.s3_path_style else "virtual"},
            retries={"max_attempts": 3, "mode": "standard"},
            connect_timeout=5,
            read_timeout=60,
        ),
    )


def remove_leftovers(directory: Path | None = None) -> int:
    """Deletes job directories a killed worker left behind; run before consuming."""
    removed = 0
    for leftover in Path(directory or tempfile.gettempdir()).glob(f"{TEMP_PREFIX}*"):
        shutil.rmtree(leftover, ignore_errors=True)
        removed += 1
    if removed:
        log.info("removed leftover job directories", extra={"count": removed})
    return removed


class Storage:
    def __init__(self, client: Any, bucket: str, upload_bucket: str) -> None:
        self._client = client
        self.bucket = bucket
        self.upload_bucket = upload_bucket

    @contextmanager
    def fetched(self, ref: MediaJobInput, max_bytes: int, abort: Abort) -> Iterator[Path]:
        """Downloads one input to a private temporary file that is removed on exit."""
        if ref.bucket != self.upload_bucket:
            raise PermanentError(
                "contract_violation", f"input '{ref.key}' is not in the uploads bucket"
            )
        size, etag = self._head(ref)
        if size > max_bytes:
            raise PermanentError(
                "input_rejected",
                f"input '{ref.key}' is {size} bytes, over the {max_bytes}-byte maximum",
            )
        with tempfile.TemporaryDirectory(prefix=TEMP_PREFIX) as directory:
            path = Path(directory) / "input"
            self._download(ref, etag, max_bytes, path, abort)
            log.info(
                "input downloaded",
                extra={"bucket": ref.bucket, "objectKey": ref.objectKey, "bytes": size},
            )
            yield path

    def put(self, key: str, body: bytes | Path, mime: str, abort: Abort) -> int:
        # Outputs are small in-memory reports; the check stops a killed job before it writes.
        abort.check()
        size = len(body) if isinstance(body, bytes) else body.stat().st_size
        try:
            if isinstance(body, bytes):
                self._client.put_object(
                    Bucket=self.bucket,
                    Key=key,
                    Body=body,
                    ContentType=mime
                )
            else:
                with body.open("rb") as stream:
                    abort.on_kill(stream.close)
                    self._client.put_object(
                        Bucket=self.bucket,
                        Key=key,
                        Body=stream,
                        ContentType=mime
                        )
        except (BotoCoreError, ClientError) as exc:
            raise TransientError(
                f"{type(exc).__name__} storing {self.bucket}/{key}"
                ) from exc
        abort.check()
        log.info(
            "output stored",
            extra={
                "bucket": self.bucket,
                "objectKey": key,
                "bytes": size
            },
        )
        return size


    def _head(self, ref: MediaJobInput) -> tuple[int, str]:
        try:
            head = self._client.head_object(Bucket=ref.bucket, Key=ref.objectKey)
        except (BotoCoreError, ClientError) as exc:
            raise self._classify(exc, ref) from exc
        return int(head["ContentLength"]), str(head["ETag"])

    def _download(
        self, ref: MediaJobInput, etag: str, max_bytes: int, path: Path, abort: Abort
    ) -> None:
        # IfMatch pins the download to the object whose size was checked.
        try:
            response = self._client.get_object(Bucket=ref.bucket, Key=ref.objectKey, IfMatch=etag)
        except (BotoCoreError, ClientError) as exc:
            raise self._classify(exc, ref) from exc
        body = response["Body"]
        # Closing the stream unblocks a read stuck on a stalled connection.
        abort.on_kill(body.close)
        written = 0
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as out:
            while True:
                abort.check()
                try:
                    chunk = body.read(CHUNK_BYTES)
                except Exception as exc:
                    abort.check()
                    raise TransientError(
                        f"{type(exc).__name__} reading {ref.bucket}/{ref.objectKey}"
                    ) from exc
                if not chunk:
                    break
                written += len(chunk)
                if written > max_bytes:
                    raise PermanentError(
                        "input_rejected",
                        f"input '{ref.key}' grew over the {max_bytes}-byte maximum",
                    )
                out.write(chunk)

    @staticmethod
    def _classify(exc: Exception, ref: MediaJobInput) -> PermanentError | TransientError:
        if isinstance(exc, ClientError):
            code = str(exc.response.get("Error", {}).get("Code", ""))
            if code in _MISSING:
                return PermanentError("input_rejected", f"input '{ref.key}' was not found")
            if code in _CHANGED:
                return PermanentError(
                    "input_rejected", f"input '{ref.key}' changed while it was being checked"
                )
            if code in _FORBIDDEN:
                # A missing key on these stores answers 404, so 403 is the worker's credential
                # or policy: an operator problem, retried, never blamed on the member's upload.
                log.error(
                    "the worker may not read an input object; check its store policy",
                    extra={"bucket": ref.bucket, "objectKey": ref.objectKey, "code": code},
                )
        return TransientError(f"{type(exc).__name__} reading {ref.bucket}/{ref.objectKey}")
