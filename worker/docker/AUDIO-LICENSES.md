# Audio worker image: third-party components

The `audio-runtime` image of `worker/Dockerfile` runs the `separate_audio`, `speakers` and `beats`
engines. This file is copied into the image at `/app/licenses/`. Each package ships its own licence
text in its `dist-info`; the models ship no text, so their licence and origin are recorded here.

| Component | Version | Licence | Origin |
|---|---|---|---|
| demucs (source, built in the image) | 4.0.1 | MIT | https://github.com/facebookresearch/demucs |
| htdemucs weights | `htdemucs` | MIT (same repository) | https://dl.fbaipublicfiles.com/demucs/ (fetched by `demucs.pretrained.get_model` at build time) |
| torch, torchaudio | 2.4.0+cpu | BSD-3-Clause | https://download.pytorch.org/whl/cpu |
| sherpa-onnx, sherpa-onnx-core | 1.13.8 | Apache-2.0 (bundles onnxruntime, MIT) | https://github.com/k2-fsa/sherpa-onnx |
| silero VAD `silero_vad.onnx` | release `asr-models` | MIT | https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx |
| CAM++ `3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx` | release `speaker-recongition-models` | Apache-2.0 | https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx |
| scikit-learn, joblib, threadpoolctl | 1.5.2, 1.4.2, 3.5.0 | BSD-3-Clause | https://pypi.org |
| beat_this | 1.1.0 | MIT | https://github.com/CPJKU/beat_this |
| beat_this checkpoint `final0.ckpt` | `final0` | MIT | https://cloud.cp.jku.at/public.php/dav/files/7ik4RrBKTS273gp/final0.ckpt |
| librosa | 0.11.0 | ISC | https://github.com/librosa/librosa |

The speaker clustering code derives from 3D-Speaker (Apache-2.0); its notice is
`/app/licenses/speakers-NOTICE.txt`.

Every downloaded model is verified against a sha256 digest pinned in `worker/Dockerfile`, so a
moved or replaced file fails the image build.
