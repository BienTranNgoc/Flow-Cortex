"""The skeleton slideshow capability validates bindings and emits a fixed vertical MP4."""

from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path
from typing import Any, cast

import pytest
from conftest import UPLOAD_BUCKET, job_body

from flowcortex_worker.contracts.media_job import MediaJob
from flowcortex_worker.errors import PermanentError
from flowcortex_worker.handlers import render_video
from flowcortex_worker.runtime import ProcessRunner


def make_job(keys: list[str], profile: str = render_video.PROFILE) -> MediaJob:
    return MediaJob.model_validate(
        job_body(
            inputs=[
                {"key": key, "bucket": UPLOAD_BUCKET, "objectKey": f"u-1/{key}"}
                for key in keys
            ],
            params={"profile": profile},
        )
    )


def make_inputs(directory: Path, keys: list[str]) -> dict[str, Path]:
    directory.mkdir(parents=True, exist_ok=True)
    resolved = {}
    for key in keys:
        path = directory / key
        path.write_bytes(key.encode())
        resolved[key] = path
    return resolved


def inspect_video(path: Path) -> dict[str, Any]:
    completed = subprocess.run(
        [
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "format=duration:stream=codec_type,codec_name,width,height",
            "-of",
            "json",
            str(path),
        ],
        check=True,
        capture_output=True,
    )
    return cast(dict[str, Any], json.loads(completed.stdout))


@pytest.mark.skipif(
    shutil.which("ffmpeg") is None,
    reason="ffmpeg is installed in the worker image",
)
def test_fixture_is_deterministic_vertical_mp4_with_audio(tmp_path: Path) -> None:
    keys = ["image_1", "image_2", "speech"]
    first = render_video.handle(
        make_job(keys),
        make_inputs(tmp_path / "first", keys),
        ProcessRunner(),
    )
    first_video, first_manifest = first.files
    assert isinstance(first_video.body, Path)
    first_bytes = first_video.body.read_bytes()

    second = render_video.handle(
        make_job(keys),
        make_inputs(tmp_path / "second", keys),
        ProcessRunner(),
    )
    second_video, _ = second.files
    assert isinstance(second_video.body, Path)

    assert (first_video.name, first_video.mime) == ("video.mp4", "video/mp4")
    assert first_bytes == second_video.body.read_bytes()
    report = inspect_video(first_video.body)
    video = next(stream for stream in report["streams"] if stream["codec_type"] == "video")
    audio = next(stream for stream in report["streams"] if stream["codec_type"] == "audio")
    assert (video["codec_name"], video["width"], video["height"]) == ("h264", 360, 640)
    assert audio["codec_name"] == "aac"
    assert 15 <= float(report["format"]["duration"]) < 16

    expected = {
        "aspectRatio": "9:16",
        "durationSeconds": 15,
        "height": 640,
        "imageCount": 2,
        "profile": "vertical_ad_v1",
        "renderer": "fixture",
        "width": 360,
    }
    assert first.data == expected
    assert (first_manifest.name, first_manifest.mime) == ("manifest.json", "application/json")
    assert isinstance(first_manifest.body, bytes)
    assert json.loads(first_manifest.body) == expected


@pytest.mark.parametrize("profile", ["", "vertical_ad_v2", "product_ad_v1"])
def test_unknown_profile_is_rejected(tmp_path: Path, profile: str) -> None:
    keys = ["image_1", "speech"]
    with pytest.raises(PermanentError) as failed:
        render_video.handle(
            make_job(keys, profile),
            make_inputs(tmp_path, keys),
            ProcessRunner(),
        )

    assert failed.value.code == "contract_violation"


@pytest.mark.parametrize(
    "keys",
    [
        ["speech"],
        [*[f"image_{index}" for index in range(1, 12)], "speech"],
        ["image_1"],
        ["image_1", "image_3", "speech"],
        ["image_2", "image_1", "speech"],
        ["image_1", "speech", "subtitle"],
        ["image_1", "image_1", "speech"],
        ["image_1", "speech", "speech"],
    ],
    ids=[
        "no-image",
        "too-many-images",
        "no-speech",
        "image-gap",
        "wrong-image-order",
        "extra-input",
        "duplicate-image",
        "duplicate-speech",
    ],
)
def test_invalid_input_bindings_are_rejected(tmp_path: Path, keys: list[str]) -> None:
    with pytest.raises(PermanentError) as failed:
        render_video.handle(
            make_job(keys),
            make_inputs(tmp_path, keys),
            ProcessRunner(),
        )

    assert failed.value.code == "contract_violation"


def test_resolved_inputs_must_match_declared_inputs(tmp_path: Path) -> None:
    keys = ["image_1", "speech"]
    inputs = make_inputs(tmp_path, keys)
    del inputs["image_1"]

    with pytest.raises(PermanentError) as failed:
        render_video.handle(make_job(keys), inputs, ProcessRunner())

    assert failed.value.code == "contract_violation"


def test_empty_speech_is_rejected(tmp_path: Path) -> None:
    inputs = make_inputs(tmp_path, ["image_1", "speech"])
    inputs["speech"].write_bytes(b"")

    with pytest.raises(PermanentError, match="non-empty speech"):
        render_video.handle(
            make_job(["image_1", "speech"]),
            inputs,
            ProcessRunner(),
        )
