# Trend worker image: third-party components

The `trend-runtime` image of `worker/Dockerfile` runs the `role_suggest` person detector. This
file is copied into the image at `/app/licenses/`. Each package ships its own licence text in its
`dist-info`; the model ships no text, so its licence and origin are recorded here.

| Component | Version | Licence | Origin |
|---|---|---|---|
| RT-DETR `PekingU/rtdetr_r50vd` (`config.json`, `preprocessor_config.json`, `model.safetensors`) | Hugging Face revision `df939e661d8c` | Apache-2.0 (model card) | https://huggingface.co/PekingU/rtdetr_r50vd/tree/df939e661d8c52e80608d1ec566561aabd25a4e7 |
| transformers | 4.46.3 | Apache-2.0 | https://pypi.org/project/transformers/ |
| tokenizers | 0.20.3 | Apache-2.0 | https://pypi.org/project/tokenizers/ |
| safetensors | 0.4.5 | Apache-2.0 | https://pypi.org/project/safetensors/ |
| huggingface-hub | 0.26.5 | Apache-2.0 | https://pypi.org/project/huggingface-hub/ |
| regex | 2026.11.19 | Apache-2.0 AND CNRI-Python | https://pypi.org/project/regex/ |
| tqdm | 4.70.1 | MPL-2.0 AND MIT | https://pypi.org/project/tqdm/ |
| PyYAML | 6.0.4 | MIT | https://pypi.org/project/PyYAML/ |
| torch | 2.4.0+cpu | BSD-3-Clause | https://download.pytorch.org/whl/cpu |
| numpy | 1.26.4 | BSD-3-Clause | https://pypi.org/project/numpy/ |
| Pillow | 13.0.0 | MIT-CMU | https://pypi.org/project/pillow/ |

The transitive packages of the torch base (filelock, fsspec, Jinja2, MarkupSafe, mpmath, networkx,
setuptools, sympy, typing-extensions, torchaudio) and of the worker itself are permissive
(MIT, BSD, Apache-2.0, PSF) with three exceptions that are not part of the detector: certifi
(MPL-2.0), edge-tts (LGPL-3.0, dynamically imported by the worker as a library) and yt-dlp
(Unlicense). The list comes from the venv of the image's install steps, with this command (standard
library only):

```
python -I -c "
import importlib.metadata as md
for d in sorted(md.distributions(), key=lambda d: d.metadata['Name'].lower()):
    m = d.metadata
    print(m['Name'], d.version, m.get('License-Expression') or [c for c in (m.get_all('Classifier') or []) if c.startswith('License ::')])
"
```

## YOLO and ultralytics

YOLO and ultralytics are deliberately not used: ultralytics is AGPL-3.0, which would extend its
source-disclosure terms to the service that runs it. RT-DETR under Apache-2.0 detects the same
`person` class without that obligation.

## Engine test photo

`worker/tests/engines/fixtures/one_person.jpg` is not part of the image; it is the input of the
engine test that runs the real model on a photo of one person.

- Title: "Man standing on street (Unsplash).jpg"
- Author: chelsea ferenando (https://unsplash.com/@chelferen)
- Licence: CC0 1.0 (published on Unsplash in 2017, before Unsplash's own licence; Wikimedia Commons
  tags the file CC0)
- Source: https://commons.wikimedia.org/wiki/File:Man_standing_on_street_(Unsplash).jpg, also
  https://unsplash.com/photos/r_-M00daj2Y
- The committed file is the unmodified 960x540 thumbnail
  https://upload.wikimedia.org/wikipedia/commons/thumb/c/cc/Man_standing_on_street_%28Unsplash%29.jpg/960px-Man_standing_on_street_%28Unsplash%29.jpg

Every downloaded model file is verified against a sha256 digest pinned in `worker/Dockerfile`, so a
moved or replaced file fails the image build.
