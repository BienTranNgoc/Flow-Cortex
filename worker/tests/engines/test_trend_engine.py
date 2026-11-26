"""The RT-DETR person detector child against the real model; runs inside the trend image only."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any

import pytest

pytest.importorskip("transformers")

from PIL import Image
from transformers import AutoConfig

from flowcortex_worker.engines.role_suggest import MODEL_DIR, person_ids

pytestmark = pytest.mark.engines

FIXTURES = Path(__file__).resolve().parent / "fixtures"
# The person in one_person.jpg (960x540) spans x 335..755 and y 15..540 px; a box around them
# has its centre near (0.57, 0.52). The region excludes the parked cars at the left edge.
PERSON_CENTRE_X = (0.45, 0.70)
PERSON_CENTRE_Y = (0.35, 0.65)
OFFLINE_ENV = {**os.environ, "HF_HUB_OFFLINE": "1", "TRANSFORMERS_OFFLINE": "1"}


def run_detector(
    output: Path, *frames: Path, model_dir: Path | None = None
) -> subprocess.CompletedProcess[str]:
    args = ["--model-dir", str(model_dir)] if model_dir else []
    return subprocess.run(
        [
            sys.executable,
            *("-m", "flowcortex_worker.engines.role_suggest"),
            *args,
            *("--work-dir", str(output.parent)),
            *("--output", str(output)),
            *map(str, frames),
        ],
        capture_output=True,
        text=True,
        timeout=600,
        env=OFFLINE_ENV,
    )


def read_report(output: Path) -> list[dict[str, Any]]:
    report: list[dict[str, Any]] = json.loads(output.read_text(encoding="utf-8"))
    return report


def test_a_single_colour_frame_has_no_box(tmp_path: Path) -> None:
    frame = tmp_path / "flat.jpg"
    Image.new("RGB", (640, 360), (30, 120, 200)).save(frame, "JPEG")
    output = tmp_path / "boxes.json"

    done = run_detector(output, frame)

    assert done.returncode == 0, done.stderr
    assert read_report(output) == [{"width": 640, "height": 360, "boxes": []}]


def test_the_person_label_resolves_to_id_zero() -> None:
    config = AutoConfig.from_pretrained(MODEL_DIR, local_files_only=True)

    assert person_ids(config) == {0}


def test_a_synthetic_portrait_frame_reports_boxes_in_the_output_schema(tmp_path: Path) -> None:
    frame = tmp_path / "portrait.jpg"
    subprocess.run(
        [
            *("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y"),
            *("-f", "lavfi", "-i", "testsrc2=s=480x854:r=24:d=1"),
            *("-frames:v", "1", "-q:v", "2", str(frame)),
        ],
        check=True,
        capture_output=True,
    )
    output = tmp_path / "boxes.json"

    done = run_detector(output, frame)

    assert done.returncode == 0, done.stderr
    [item] = read_report(output)
    assert (item["width"], item["height"]) == (480, 854)
    for box in item["boxes"]:
        assert len(box) == 5
        assert 0 <= box[4] <= 1


def test_a_model_directory_without_the_model_is_unloadable(tmp_path: Path) -> None:
    frame = tmp_path / "flat.jpg"
    Image.new("RGB", (64, 64)).save(frame, "JPEG")
    empty = tmp_path / "empty"
    empty.mkdir()
    output = tmp_path / "boxes.json"

    done = run_detector(output, frame, model_dir=empty)

    assert done.returncode == 3, done.stderr
    assert done.stderr.strip()
    assert not output.exists()


def test_a_photo_of_one_person_yields_a_box_around_them(tmp_path: Path) -> None:
    photo = tmp_path / "one_person.jpg"
    shutil.copy(FIXTURES / "one_person.jpg", photo)
    output = tmp_path / "boxes.json"

    done = run_detector(output, photo)

    assert done.returncode == 0, done.stderr
    [item] = read_report(output)
    width, height = item["width"], item["height"]
    centres = [
        ((x0 + x1) / 2 / width, (y0 + y1) / 2 / height)
        for x0, y0, x1, y1, score in item["boxes"]
        if score >= 0.5
    ]
    assert any(
        PERSON_CENTRE_X[0] <= cx <= PERSON_CENTRE_X[1]
        and PERSON_CENTRE_Y[0] <= cy <= PERSON_CENTRE_Y[1]
        for cx, cy in centres
    ), centres
