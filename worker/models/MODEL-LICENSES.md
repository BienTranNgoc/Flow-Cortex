# Model and ported-code licences (overlay image)

Read from the upstream licence files on 2026-11-10. "Redistribution inside an image" means a
published container image that carries the file.

| Component | Source | Licence (upstream file) | Redistribution inside an image |
|---|---|---|---|
| YuNet face detector `face_detection_yunet_2023mar.onnx` | https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet | MIT, `LICENSE` in that directory (Copyright 2020 Shiqi Yu) | Allowed; keep the copyright notice with the file |
| big-lama weights `big-lama.pt` | Mirror used by `simple-lama-inpainting` 0.1.2: https://github.com/enesmsahin/simple-lama-inpainting/releases/download/v0.1.0/big-lama.pt (205,803,670 bytes); upstream model https://huggingface.co/smartywu/big-lama, code https://github.com/advimman/lama | Apache-2.0 (advimman/lama `LICENSE`; Hugging Face model card `license: apache-2.0`) | Allowed by the declared licence. Provenance caveat: the weights were trained on Places365, whose terms of use (places2.csail.mit.edu) allow the image data only for non-commercial research and education and forbid distributing the images. The terms cover the images, not the derived weights, and neither the LaMa repository nor the model card carries that restriction, but no upstream statement settles it. Open for Master |
| EasyOCR CRAFT detector `craft_mlt_25k.pth` | https://github.com/JaidedAI/EasyOCR/releases/download/pre-v1.1.6/craft_mlt_25k.zip; architecture https://github.com/clovaai/CRAFT-pytorch | CRAFT code: MIT (NAVER Corp., `LICENSE`); weights shipped as an EasyOCR release asset under the EasyOCR repository licence, Apache-2.0 | Allowed; EasyOCR names no separate weight licence |
| EasyOCR recogniser `latin_g2` (`latin_g2.zip`, the model EasyOCR 1.7.2 picks for `vi`,`en`) | https://github.com/JaidedAI/EasyOCR/releases/download/v1.3/latin_g2.zip | EasyOCR repository licence, Apache-2.0 (`LICENSE`); no separate model licence is published | Allowed under the repository licence; training data is not documented upstream |
| EasyOCR library | https://github.com/JaidedAI/EasyOCR (PyPI `easyocr`) | Apache-2.0 | Allowed (installed from a hash-pinned wheel) |
| Ported `simple-lama-inpainting` 0.1.2 pre/post-processing | https://pypi.org/project/simple-lama-inpainting/0.1.2/ (sdist `LICENSE`) | Apache-2.0 (author enesmsahin; `utils/util.py` also credits advimman/lama and Sanster/lama-cleaner) | Allowed; ported code keeps an attribution line |

Open item: the Places365 provenance of the big-lama weights is not settled by any upstream
statement. The weights' declared licence permits redistribution, so S1 and the code slices
proceed; whether the production image may ship them commercially is Master's call before the
overlay image is published.

Pinned in `worker/Dockerfile` (sha256 of the file in the image): `big-lama.pt` 7ba7aa7a...9b4c, `craft_mlt_25k.pth` 4a5efbfb...db17, `latin_g2.pth` aaa95be1...8c55, YuNet 8f2383e4...2fa4. All four URLs and files above are the ones the image build downloads or copies; no model outside this table is baked.
