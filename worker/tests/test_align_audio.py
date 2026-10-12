"""The alignment capability pads or tempo-nudges speech onto the source timeline."""

from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path

import pytest
from conftest import UPLOAD_BUCKET, job_body

from flowcortex_worker.contracts.media_job import MediaJob
from flowcortex_worker.errors import PermanentError
from flowcortex_worker.handlers import align_audio
from flowcortex_worker.runtime import ProcessRunner

needs_ffmpeg = pytest.mark.skipif(
    shutil.which("ffmpeg") is None,
    reason="ffmpeg is installed in the worker image",
)


def make_job(**params: object) -> MediaJob:
    return MediaJob.model_validate(
        job_body(
            capability="align_audio",
            inputs=[
                {"key": "speech", "bucket": UPLOAD_BUCKET, "objectKey": "u-1/speech.wav"}
            ],
            params={"profile": align_audio.PROFILE, **params},
        )
    )


def make_speech(directory: Path, seconds: float) -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / "speech.wav"
    subprocess.run(
        [
            "ffmpeg", "-v", "error", "-y",
            "-f", "lavfi", "-i", f"sine=frequency=440:duration={seconds}",
            "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le",
            str(path),
        ],
        check=True,
    )  # fmt: skip
    return path


def measured_seconds(body: bytes | Path) -> float:
    assert isinstance(body, Path)
    path = body
    completed = subprocess.run(
        [
            "ffprobe", "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            str(path),
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    return float(completed.stdout.strip())


# --- plan_alignment is pure, so the pad/tempo rules are decided here -----------------


def test_speech_shorter_than_target_is_padded_not_stretched() -> None:
    tempo, pad = align_audio.plan_alignment(10.0, 15.0)

    assert tempo == 1.0
    assert pad == 5.0


def test_padding_beyond_the_relative_cap_is_refused_as_silence() -> None:
    with pytest.raises(PermanentError) as failure:
        align_audio.plan_alignment(4.0, 20.0)

    assert failure.value.code == "audio_alignment_out_of_range"


def test_padding_within_the_relative_cap_is_allowed() -> None:
    _, pad = align_audio.plan_alignment(8.0, 10.0)

    assert pad == pytest.approx(2.0)


def test_speech_longer_than_target_is_spped_up_never_truncated() -> None:
    tempo, pad = align_audio.plan_alignment(20.0, 15.0)

    assert tempo == pytest.approx(20.0 / 15.0)
    assert pad == 0.0


def test_equal_durations_need_neither_pad_nor_tempo() -> None:
    assert align_audio.plan_alignment(12.0, 12.0) == (1.0, 0.0)


@pytest.mark.parametrize("ratio", [1.0, 1.25, 1.5, 2.0, 4.0])
def test_a_wide_ratio_stays_inside_the_supported_tempo_range(ratio: float) -> None:
    tempo, _ = align_audio.plan_alignment(15.0 * ratio, 15.0)

    assert tempo == pytest.approx(ratio)


def test_a_ratio_beyond_the_supported_range_is_a_permanent_error() -> None:
    with pytest.raises(PermanentError) as failure:
        align_audio.plan_alignment(15.0 * align_audio.MAX_TEMPO * 20, 15.0)

    assert failure.value.code == "audio_alignment_out_of_range"


@pytest.mark.parametrize(("speech", "target"), [(0.0, 15.0), (10.0, 0.0), (-1.0, 15.0)])
def test_non_positive_durations_are_rejected(speech: float, target: float) -> None:
    with pytest.raises(ValueError):
        align_audio.plan_alignment(speech, target)


# --- the handler, against real ffmpeg -------------------------------------------------


@needs_ffmpeg
def test_padded_speech_meets_the_target_duration(tmp_path: Path) -> None:
    outcome = align_audio.handle(
        make_job(targetDurationSeconds=6),
        {"speech": make_speech(tmp_path / "shorter", 4.0)},
        ProcessRunner(),
    )

    assert outcome.data["tempo"] == 1.0
    assert outcome.data["padSeconds"] == pytest.approx(2.0, abs=0.05)
    assert measured_seconds(outcome.files[0].body) == pytest.approx(6.0, abs=0.15)
    assert (outcome.files[0].name, outcome.files[0].mime) == (
        "aligned-speech.wav",
        "audio/wav",
    )
    manifest = outcome.files[1]
    assert (manifest.name, manifest.mime) == ("alignment-manifest.json", "application/json")
    assert isinstance(manifest.body, bytes)
    assert json.loads(manifest.body) == outcome.data


@needs_ffmpeg
def test_long_speech_is_spped_up_to_the_target_duration(tmp_path: Path) -> None:
    outcome = align_audio.handle(
        make_job(targetDurationSeconds=5),
        {"speech": make_speech(tmp_path / "longer", 10.0)},
        ProcessRunner(),
    )

    assert outcome.data["tempo"] == pytest.approx(2.0, abs=0.01)
    assert outcome.data["padSeconds"] == 0.0
    assert measured_seconds(outcome.files[0].body) == pytest.approx(5.0, abs=0.15)


@needs_ffmpeg
def test_a_missing_target_duration_is_a_contract_violation(tmp_path: Path) -> None:
    with pytest.raises(PermanentError) as failure:
        align_audio.handle(
            make_job(),
            {"speech": make_speech(tmp_path / "no-target", 4.0)},
            ProcessRunner(),
        )

    assert failure.value.code == "contract_violation"


@needs_ffmpeg
def test_the_wrong_profile_is_refused(tmp_path: Path) -> None:
    with pytest.raises(PermanentError) as failure:
        align_audio.handle(
            make_job(profile="other_voice", targetDurationSeconds=6),
            {"speech": make_speech(tmp_path / "wrong-profile", 4.0)},
            ProcessRunner(),
        )

    assert failure.value.code == "contract_violation"


@needs_ffmpeg
def test_a_missing_speech_input_is_a_contract_violation(tmp_path: Path) -> None:
    with pytest.raises(PermanentError) as failure:
        align_audio.handle(make_job(targetDurationSeconds=6), {}, ProcessRunner())

    assert failure.value.code == "contract_violation"
