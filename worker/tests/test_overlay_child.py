"""The overlay child: input checks, decoding and model preconditions, before any model loads."""

from __future__ import annotations

import json
import runpy
import struct
import subprocess
import sys
from pathlib import Path

import cv2
import numpy as np
import pytest
from numpy.typing import NDArray

from flowcortex_worker.overlay import protocol
from flowcortex_worker.overlay.__main__ import decode_image
from flowcortex_worker.overlay.params import parse_params

FACE_MODEL = "face_detection_yunet_2023mar.onnx"


def write_params(directory: Path) -> Path:
    path = directory / "params.json"
    path.write_text(json.dumps(protocol.params_to_json(parse_params({}))), encoding="utf-8")
    return path


def run_child(
    image: Path, tmp_path: Path, models: Path | None = None
) -> tuple[subprocess.CompletedProcess[str], Path]:
    out = tmp_path / "out"
    out.mkdir(exist_ok=True)
    env = {"PATH": "/usr/bin:/bin", "FLOWCORTEX_OVERLAY_MODELS": str(models or tmp_path / "models")}
    done = subprocess.run(
        [
            sys.executable,
            "-m",
            "flowcortex_worker.overlay",
            str(image),
            str(write_params(tmp_path)),
            str(out),
        ],
        capture_output=True,
        text=True,
        env=env,
        timeout=120,
        check=False,
    )
    return done, out


def blank(path: Path, width: int, height: int) -> Path:
    assert cv2.imwrite(str(path), np.zeros((height, width, 3), np.uint8))
    return path


def exif_orientation_segment(orientation: int) -> bytes:
    ifd = struct.pack("<HHHIHH", 1, 0x0112, 3, 1, orientation, 0) + struct.pack("<I", 0)
    tiff = b"II*\x00" + struct.pack("<I", 8) + ifd
    body = b"Exif\x00\x00" + tiff
    return b"\xff\xe1" + struct.pack(">H", len(body) + 2) + body


def test_oversized_png_is_rejected_before_decoding_pixels(tmp_path: Path) -> None:
    image = blank(tmp_path / "big.png", 5001, 5000)
    done, out = run_child(image, tmp_path)
    assert done.returncode == protocol.INPUT_REJECTED
    assert not (out / protocol.CLEAN_FILE).exists()


def test_image_of_exactly_the_cap_passes_the_decode_step(tmp_path: Path) -> None:
    image = blank(tmp_path / "cap.png", 2048, 2048)
    assert protocol.MAX_PIXELS == 2048 * 2048
    empty_models = tmp_path / "models"
    empty_models.mkdir()
    done, _ = run_child(image, tmp_path, empty_models)
    assert done.returncode == protocol.FACE_MODEL_MISSING, done.stderr


def test_image_one_row_over_the_cap_is_rejected(tmp_path: Path) -> None:
    image = blank(tmp_path / "over.png", 2048, 2049)
    done, out = run_child(image, tmp_path)
    assert done.returncode == protocol.INPUT_REJECTED
    assert not (out / protocol.CLEAN_FILE).exists()


def test_text_file_is_rejected(tmp_path: Path) -> None:
    image = tmp_path / "notes.png"
    image.write_text("not an image at all, just words", encoding="utf-8")
    done, _ = run_child(image, tmp_path)
    assert done.returncode == protocol.INPUT_REJECTED


@pytest.mark.parametrize("name", ["scan.tif", "scan.jp2"])
def test_formats_outside_the_allowlist_are_rejected_without_decoding(
    tmp_path: Path, name: str
) -> None:
    image = tmp_path / name
    assert cv2.imwrite(str(image), np.full((64, 64, 3), 90, np.uint8))
    assert cv2.imread(str(image)) is not None
    done, out = run_child(image, tmp_path)
    assert done.returncode == protocol.INPUT_REJECTED
    assert not (out / protocol.CLEAN_FILE).exists()


def test_face_model_that_is_a_lfs_pointer_stops_the_job(tmp_path: Path) -> None:
    models = tmp_path / "models"
    models.mkdir()
    (models / FACE_MODEL).write_bytes(b"x" * 131)
    done, out = run_child(blank(tmp_path / "small.png", 64, 64), tmp_path, models)
    assert done.returncode == protocol.FACE_MODEL_MISSING
    assert FACE_MODEL in done.stderr
    assert not (out / protocol.CLEAN_FILE).exists()


def test_missing_overlay_libraries_end_with_the_runtime_exit_code(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    image = blank(tmp_path / "small.png", 64, 64)
    monkeypatch.setitem(sys.modules, "cv2", None)
    monkeypatch.delitem(sys.modules, "flowcortex_worker.overlay.__main__")
    monkeypatch.setattr(
        sys, "argv", ["overlay", str(image), str(write_params(tmp_path)), str(tmp_path)]
    )
    with pytest.raises(SystemExit) as stopped:
        runpy.run_module("flowcortex_worker.overlay", run_name="__main__")
    assert stopped.value.code == protocol.RUNTIME_MISSING


def test_rgba_png_keeps_its_alpha_plane(tmp_path: Path) -> None:
    bgra = np.random.default_rng(3).integers(0, 255, (40, 60, 4)).astype(np.uint8)
    path = tmp_path / "alpha.png"
    assert cv2.imwrite(str(path), bgra)
    decoded = decode_image(path)
    assert decoded.shape == (40, 60, 4)
    assert np.array_equal(decoded[:, :, 3], bgra[:, :, 3])
    assert np.array_equal(decoded[:, :, :3], bgra[:, :, :3])


def test_sixteen_bit_alpha_is_scaled_to_eight_bits(tmp_path: Path) -> None:
    bgra = np.random.default_rng(4).integers(0, 65535, (16, 24, 4)).astype(np.uint16)
    path = tmp_path / "alpha16.png"
    assert cv2.imwrite(str(path), bgra)
    decoded = decode_image(path)
    assert decoded.dtype == np.uint8
    assert np.array_equal(decoded[:, :, 3], (bgra[:, :, 3] >> 8).astype(np.uint8))


def test_opaque_png_decodes_without_alpha(tmp_path: Path) -> None:
    decoded = decode_image(blank(tmp_path / "plain.png", 30, 20))
    assert decoded.shape == (20, 30, 3)


def test_rotated_jpeg_decodes_as_displayed(tmp_path: Path) -> None:
    plain = tmp_path / "plain.jpg"
    assert cv2.imwrite(str(plain), np.full((3000, 4000, 3), 120, np.uint8))
    data = plain.read_bytes()
    rotated = tmp_path / "rotated.jpg"
    rotated.write_bytes(data[:2] + exif_orientation_segment(6) + data[2:])
    decoded: NDArray[np.uint8] = decode_image(rotated)
    assert decoded.shape == (4000, 3000, 3)
