"""The provider identity reads product inputs and its own speech artifacts, never video outputs."""

from __future__ import annotations

import json
import uuid
from typing import Any
from unittest.mock import MagicMock

import pytest
from botocore.exceptions import ClientError
from conftest import MEDIA_BUCKET, PROVIDER_POLICY, UPLOAD_BUCKET, ProviderSilo

from flowcortex_worker.errors import TransientError
from flowcortex_worker.storage import Storage

SPEECH_ARTIFACTS = {
    "arn:aws:s3:::flowcortex-media/runs/*/steps/*/attempts/*/speech.wav",
    "arn:aws:s3:::flowcortex-media/runs/*/steps/*/attempts/*/manifest.json",
}
RUN_INPUTS = "arn:aws:s3:::flowcortex-media/runs/*/inputs/*"


def _resources_for(action: str) -> set[str]:
    policy: dict[str, Any] = json.loads(PROVIDER_POLICY.read_text())
    resources: set[str] = set()
    for statement in policy["Statement"]:
        assert statement["Effect"] == "Allow"
        actions = statement["Action"]
        if action in ([actions] if isinstance(actions, str) else actions):
            granted = statement["Resource"]
            resources.update([granted] if isinstance(granted, str) else granted)
    return resources


def _status(response: dict[str, Any]) -> int:
    return int(response["ResponseMetadata"]["HTTPStatusCode"])


def test_provider_reads_only_run_inputs_and_speech_artifacts() -> None:
    readable = _resources_for("s3:GetObject")

    assert not [resource for resource in readable if resource.endswith("runs/*")]
    assert readable == SPEECH_ARTIFACTS | {RUN_INPUTS}


def test_provider_still_writes_under_runs() -> None:
    assert _resources_for("s3:PutObject") == {"arn:aws:s3:::flowcortex-media/runs/*"}


def test_store_answers_404_for_a_missing_speech_artifact_so_reuse_sees_it_absent(
    provider_silo: ProviderSilo,
) -> None:
    client = provider_silo.provider.client()
    storage = Storage(client, MEDIA_BUCKET, UPLOAD_BUCKET)
    attempt = f"runs/{uuid.uuid4()}/steps/3/attempts/1"
    speech, manifest = f"{attempt}/speech.wav", f"{attempt}/manifest.json"

    assert _status(client.put_object(Bucket=MEDIA_BUCKET, Key=speech, Body=b"RIFF")) == 200
    assert _status(client.head_object(Bucket=MEDIA_BUCKET, Key=speech)) == 200
    assert storage.head_object(MEDIA_BUCKET, speech) is not None

    with pytest.raises(ClientError) as missing:
        client.head_object(Bucket=MEDIA_BUCKET, Key=manifest)
    assert missing.value.response["Error"]["Code"] == "404"
    assert storage.head_object(MEDIA_BUCKET, manifest) is None


def test_provider_can_fetch_a_snapshotted_run_input(provider_silo: ProviderSilo) -> None:
    key = f"runs/{uuid.uuid4()}/inputs/image_1"
    provider_silo.root.client().put_object(Bucket=MEDIA_BUCKET, Key=key, Body=b"run data")
    client = provider_silo.provider.client()
    storage = Storage(client, MEDIA_BUCKET, UPLOAD_BUCKET)

    assert storage.fetch_bytes(MEDIA_BUCKET, key, MagicMock()) == b"run data"


def test_store_answers_403_when_the_provider_reads_a_video_output(
    provider_silo: ProviderSilo,
) -> None:
    key = f"runs/{uuid.uuid4()}/steps/4/attempts/1/video.mp4"
    provider_silo.root.client().put_object(Bucket=MEDIA_BUCKET, Key=key, Body=b"run data")
    client = provider_silo.provider.client()
    storage = Storage(client, MEDIA_BUCKET, UPLOAD_BUCKET)

    for read in (client.head_object, client.get_object):
        with pytest.raises(ClientError) as denied:
            read(Bucket=MEDIA_BUCKET, Key=key)
        assert _status(denied.value.response) == 403
    with pytest.raises(TransientError):
        storage.head_object(MEDIA_BUCKET, key)
