"""Model-backed engines of the clean: OCR detector and recogniser, YuNet and LaMa.

Every file is read from the model root and nothing is downloaded or written at run time.
The LaMa pre- and post-processing follows simple-lama-inpainting 0.1.2 (Apache-2.0, author
enesmsahin; its util module credits advimman/lama and Sanster/lama-cleaner).
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any

import cv2
import numpy as np
from numpy.typing import NDArray

Image = NDArray[np.uint8]
Mask = NDArray[np.uint8]

MODEL_ROOT_ENV = "FLOWCORTEX_OVERLAY_MODELS"
DEFAULT_MODEL_ROOT = "/opt/flowcortex/models"
FACE_MODEL_FILE = "face_detection_yunet_2023mar.onnx"
LAMA_MODEL_FILE = "lama/big-lama.pt"
EASYOCR_DIR = "easyocr"
# A Git LFS pointer is 131 bytes; the real model is 232,589.
MIN_FACE_MODEL_BYTES = 100_000
FACE_SCORE_THRESHOLD = 0.6
FACE_NMS_THRESHOLD = 0.3
FACE_TOP_K = 5000
LAMA_PAD_MODULO = 8
DETECTOR_LANGUAGES = ["ch_sim", "en"]
RECOGNISER_LANGUAGES = ["vi", "en"]


class FaceModelMissing(RuntimeError):
    """The face model is absent or too small to be one; the clean must not run without it."""


def model_root() -> Path:
    return Path(os.environ.get(MODEL_ROOT_ENV) or DEFAULT_MODEL_ROOT)


def face_model_path() -> Path:
    return model_root() / FACE_MODEL_FILE


def require_face_model() -> Path:
    path = face_model_path()
    if not path.is_file() or path.stat().st_size < MIN_FACE_MODEL_BYTES:
        raise FaceModelMissing(
            f"face model {FACE_MODEL_FILE} is missing or not a model file at {path}"
        )
    return path


def _torch() -> Any:
    import torch

    # Follow the CPUs the container may use, not the host's core count.
    torch.set_num_threads(len(os.sched_getaffinity(0)))
    return torch


def _easyocr_reader(languages: list[str], *, recognizer: bool) -> Any:
    import easyocr

    base = model_root() / EASYOCR_DIR
    return easyocr.Reader(
        languages,
        gpu=False,
        verbose=False,
        recognizer=recognizer,
        download_enabled=False,
        model_storage_directory=str(base / "model"),
        user_network_directory=str(base / "user_network"),
    )


def _pad_to_modulo(plane: NDArray[np.float32], modulo: int) -> NDArray[np.float32]:
    _, height, width = plane.shape
    pad_h = -height % modulo
    pad_w = -width % modulo
    if pad_h == 0 and pad_w == 0:
        return plane
    padded: NDArray[np.float32] = np.pad(plane, ((0, 0), (0, pad_h), (0, pad_w)), mode="symmetric")
    return padded


class ModelEngines:
    """The four model steps of `pipeline.Engines`, each model loaded on first use."""

    def __init__(self) -> None:
        self._detector: Any = None
        self._recogniser: Any = None
        self._face: Any = None
        self._lama: Any = None

    def load_all(self) -> None:
        """Build every model now, so a missing file fails here and not mid-job."""
        self._detector_reader()
        self._recogniser_reader()
        self._face_detector((320, 320))
        self._lama_model()

    def _detector_reader(self) -> Any:
        if self._detector is None:
            self._detector = _easyocr_reader(DETECTOR_LANGUAGES, recognizer=False)
        return self._detector

    def _recogniser_reader(self) -> Any:
        if self._recogniser is None:
            self._recogniser = _easyocr_reader(RECOGNISER_LANGUAGES, recognizer=True)
        return self._recogniser

    def _face_detector(self, size: tuple[int, int]) -> Any:
        if self._face is None:
            self._face = cv2.FaceDetectorYN.create(
                str(require_face_model()),
                "",
                size,
                FACE_SCORE_THRESHOLD,
                FACE_NMS_THRESHOLD,
                FACE_TOP_K,
            )
        return self._face

    def _lama_model(self) -> Any:
        if self._lama is None:
            path = model_root() / LAMA_MODEL_FILE
            if not path.is_file():
                raise FileNotFoundError(f"LaMa weights missing at {path}")
            model = _torch().jit.load(str(path), map_location="cpu")
            model.eval()
            self._lama = model
        return self._lama

    def text_boxes(self, bgr: Image) -> list[tuple[int, int, int, int]]:
        found = self._detector_reader().detect(bgr)[0][0]
        return [(int(b[0]), int(b[1]), int(b[2]), int(b[3])) for b in found]

    def text_items(self, bgr: Image) -> list[dict[str, Any]]:
        items: list[dict[str, Any]] = []
        for box, text, confidence in self._recogniser_reader().readtext(bgr):
            xs = [int(point[0]) for point in box]
            ys = [int(point[1]) for point in box]
            items.append(
                {
                    "box": (min(xs), max(xs), min(ys), max(ys)),
                    "text": text,
                    "confidence": float(confidence),
                }
            )
        return items

    def faces(self, bgr: Image) -> list[tuple[int, int, int, int]]:
        height, width = bgr.shape[:2]
        detector = self._face_detector((width, height))
        detector.setInputSize((width, height))
        _, found = detector.detect(bgr)
        if found is None:
            return []
        return [(int(f[0]), int(f[1]), int(f[2]), int(f[3])) for f in found]

    def inpaint(self, bgr: Image, mask: Mask) -> Image:
        torch = _torch()
        model = self._lama_model()
        height, width = bgr.shape[:2]
        rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
        image = rgb.transpose(2, 0, 1).astype(np.float32) / 255.0
        region = (mask > 0).astype(np.float32)[None]
        image = _pad_to_modulo(image, LAMA_PAD_MODULO)
        region = _pad_to_modulo(region, LAMA_PAD_MODULO)
        with torch.inference_mode():
            out = model(
                torch.from_numpy(image).unsqueeze(0),
                (torch.from_numpy(region).unsqueeze(0) > 0).to(torch.int64),
            )
            result = out[0].permute(1, 2, 0).detach().cpu().numpy()
        painted = np.clip(result * 255, 0, 255).astype(np.uint8)[:height, :width]
        restored: Image = cv2.cvtColor(painted, cv2.COLOR_RGB2BGR)
        return restored
