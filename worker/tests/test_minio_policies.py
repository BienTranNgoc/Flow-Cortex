"""The provider identity reads back only its own speech artifacts and each run's first product
image, never any other run input or a video."""

from __future__ import annotations

import json
import uuid
from typing import Any

import pytest
from botocore.exceptions import ClientError
from conftest import MEDIA_BUCKET, PROVIDER_POLICY, UPLOAD_BUCKET, ProviderSilo

from flowcortex_worker.errors import TransientError
from flowcortex_worker.storage import Storage

SPEECH_ARTIFACTS = {
    "arn:aws:s3:::flowcortex-media/runs/*/steps/*/attempts/*/speech.wav",
    "arn:aws:s3:::flowcortex-media/runs/*/steps/*/attempts/*/manifest.json",
}
FIRST_PRODUCT_IMAGE = "arn:aws:s3:::flowcortex-media/runs/*/inputs/image_1"


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


def test_provider_reads_only_speech_artifacts_and_the_first_product_image() -> None:
    readable = _resources_for("s3:GetObject")

    assert not [resource for resource in readable if resource.endswith("*")]
    assert readable == SPEECH_ARTIFACTS | {FIRST_PRODUCT_IMAGE}


def test_provider_writes_only_under_its_step_attempts() -> None:
    assert _resources_for("s3:PutObject") == {
        "arn:aws:s3:::flowcortex-media/runs/*/steps/*/attempts/*"
    }


@pytest.mark.parametrize("name", ["inputs/image_1", "steps/4/video.mp4"])
def test_store_answers_403_when_the_provider_writes_outside_a_step_attempt(
    provider_silo: ProviderSilo, name: str
) -> None:
    client = provider_silo.provider.client()

    with pytest.raises(ClientError) as denied:
        client.put_object(Bucket=MEDIA_BUCKET, Key=f"runs/{uuid.uuid4()}/{name}", Body=b"x")
    assert _status(denied.value.response) == 403


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


def test_provider_reads_a_run_s_first_product_image(provider_silo: ProviderSilo) -> None:
    key = f"runs/{uuid.uuid4()}/inputs/image_1"
    provider_silo.root.client().put_object(Bucket=MEDIA_BUCKET, Key=key, Body=b"\x89PNG")
    client = provider_silo.provider.client()

    assert client.get_object(Bucket=MEDIA_BUCKET, Key=key)["Body"].read() == b"\x89PNG"


def test_a_presigned_url_opens_the_first_product_image_and_no_other_input(
    provider_silo: ProviderSilo,
) -> None:
    import urllib.error
    import urllib.request

    from flowcortex_worker.config import Settings
    from flowcortex_worker.storage import client_from

    run = f"runs/{uuid.uuid4()}/inputs"
    for name in ("image_1", "image_2"):
        provider_silo.root.client().put_object(
            Bucket=MEDIA_BUCKET, Key=f"{run}/{name}", Body=name.encode()
        )
    provider = provider_silo.provider
    settings = Settings(
        amqp_url="amqp://unused",
        s3_endpoint="http://minio:9000",
        s3_public_endpoint=provider.endpoint,
        s3_path_style=True,
        s3_upload_bucket=UPLOAD_BUCKET,
        s3_bucket=MEDIA_BUCKET,
        s3_access_key=provider.access_key,
        s3_secret_key=provider.secret_key,
    )
    storage = Storage(
        client_from(settings),
        MEDIA_BUCKET,
        UPLOAD_BUCKET,
        presign_client=client_from(settings, settings.s3_public_endpoint),
    )

    url = storage.presigned_get_url(MEDIA_BUCKET, f"{run}/image_1")
    with urllib.request.urlopen(url, timeout=10) as response:
        assert response.read() == b"image_1"
    with pytest.raises(urllib.error.HTTPError) as denied:
        urllib.request.urlopen(
            storage.presigned_get_url(MEDIA_BUCKET, f"{run}/image_2"), timeout=10
        )
    assert denied.value.code == 403


@pytest.mark.parametrize(
    "name", ["inputs/x", "inputs/image_2", "inputs/image_10", "steps/4/attempts/1/video.mp4"]
)
def test_store_answers_403_when_the_provider_reads_a_run_input_or_video(
    provider_silo: ProviderSilo, name: str
) -> None:
    key = f"runs/{uuid.uuid4()}/{name}"
    provider_silo.root.client().put_object(Bucket=MEDIA_BUCKET, Key=key, Body=b"run data")
    client = provider_silo.provider.client()
    storage = Storage(client, MEDIA_BUCKET, UPLOAD_BUCKET)

    for read in (client.head_object, client.get_object):
        with pytest.raises(ClientError) as denied:
            read(Bucket=MEDIA_BUCKET, Key=key)
        assert _status(denied.value.response) == 403
    with pytest.raises(TransientError):
        storage.head_object(MEDIA_BUCKET, key)
