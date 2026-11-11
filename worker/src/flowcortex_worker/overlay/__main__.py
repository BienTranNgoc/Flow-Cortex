"""Child process of the clean.

Run as `python -m flowcortex_worker.overlay` inside a work directory holding the image and the
parameters under the names in `protocol`; the cleaned image and the result land beside them.
Exit codes are the ones in `protocol`; the handler maps them to job errors.
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path
from typing import Any

from . import protocol

# OpenCV reads this limit when it first decodes, so it is set before cv2 is imported.
os.environ["OPENCV_IO_MAX_IMAGE_PIXELS"] = str(protocol.MAX_PIXELS)

import numpy as np
from numpy.typing import NDArray

Image = NDArray[np.uint8]

USAGE = 2
# Mean per-channel difference under which two decodes of one file count as the same picture.
ALIGN_TOLERANCE = 8.0


class InputRejected(Exception):
    """The input is not a PNG, JPEG or WebP that decodes within the pixel cap."""


def _sniff(head: bytes) -> str | None:
    if head.startswith(b"\x89PNG\r\n\x1a\n"):
        return "png"
    if head.startswith(b"\xff\xd8\xff"):
        return "jpeg"
    if head[:4] == b"RIFF" and head[8:12] == b"WEBP":
        return "webp"
    return None


def _to_8bit(plane: NDArray[Any]) -> Image:
    if plane.dtype == np.uint8:
        result: Image = plane
        return result
    if plane.dtype == np.uint16:
        return (plane >> 8).astype(np.uint8)
    raise InputRejected(f"unsupported sample type {plane.dtype}")


def _orientations(plane: Image) -> list[Image]:
    """The eight rotations and mirrors of a plane, the unrotated one first."""
    turns = [np.rot90(plane, k) for k in range(4)]
    mirrored = [np.fliplr(view) for view in turns]
    return [
        turns[0],
        mirrored[0],
        turns[1],
        mirrored[1],
        turns[2],
        mirrored[2],
        turns[3],
        mirrored[3],
    ]


def _aligned_alpha(raw: Image, bgr: Image) -> Image:
    """The alpha plane of an unrotated decode, turned to match the colour decode."""
    colour = raw[:, :, :3]
    alpha = raw[:, :, 3]
    best: tuple[float, Image] | None = None
    for turned_colour, turned_alpha in zip(
        _orientations(colour), _orientations(alpha), strict=True
    ):
        if turned_colour.shape != bgr.shape:
            continue
        if np.array_equal(turned_colour, bgr):
            return np.ascontiguousarray(turned_alpha)
        gap = float(np.abs(turned_colour.astype(np.int16) - bgr.astype(np.int16)).mean())
        if best is None or gap < best[0]:
            best = (gap, turned_alpha)
    if best is None or best[0] > ALIGN_TOLERANCE:
        raise InputRejected("the alpha plane does not line up with the picture")
    return np.ascontiguousarray(best[1])


def decode_image(path: Path) -> Image:
    """BGR, or BGRA when a PNG/WebP carries transparency, with the EXIF orientation applied."""
    import cv2

    with path.open("rb") as handle:
        kind = _sniff(handle.read(12))
    if kind is None:
        raise InputRejected("not a PNG, JPEG or WebP file")
    try:
        bgr = cv2.imread(str(path), cv2.IMREAD_COLOR)
    except cv2.error as error:
        raise InputRejected(f"image refused by the decoder: {error}") from error
    if bgr is None:
        raise InputRejected("the file does not decode as an image")
    if kind == "jpeg":
        return _to_8bit(bgr)
    try:
        raw = cv2.imread(str(path), cv2.IMREAD_UNCHANGED)
    except cv2.error as error:
        raise InputRejected(f"image refused by the decoder: {error}") from error
    if raw is None:
        raise InputRejected("the file does not decode as an image")
    if raw.ndim != 3 or raw.shape[2] != 4:
        return _to_8bit(bgr)
    raw8 = _to_8bit(raw)
    alpha = _aligned_alpha(raw8, _to_8bit(bgr))
    stacked: Image = np.dstack([_to_8bit(bgr), alpha])
    return stacked


def _reject(message: str) -> int:
    sys.stderr.write(f"input rejected: {message}\n")
    return protocol.INPUT_REJECTED


def main(argv: list[str]) -> int:
    if argv:
        sys.stderr.write("usage: python -m flowcortex_worker.overlay (no arguments)\n")
        return USAGE
    work = Path.cwd()

    try:
        import cv2

        from . import models, pipeline
    except ImportError as error:
        sys.stderr.write(f"overlay runtime missing: {error}\n")
        return protocol.RUNTIME_MISSING

    raw_params = json.loads((work / protocol.PARAMS_FILE).read_text(encoding="utf-8"))
    params = protocol.params_from_json(raw_params)
    try:
        image = decode_image(work / protocol.INPUT_FILE)
    except InputRejected as error:
        return _reject(str(error))
    try:
        models.require_face_model()
    except models.FaceModelMissing as error:
        sys.stderr.write(f"{error}\n")
        return protocol.FACE_MODEL_MISSING

    cleaned, data = pipeline.clean(image, params, models.ModelEngines())

    if not cv2.imwrite(str(work / protocol.CLEAN_FILE), cleaned):
        raise OSError("could not write the cleaned image")
    (work / protocol.RESULT_FILE).write_text(json.dumps(data), encoding="utf-8")
    return protocol.OK


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
