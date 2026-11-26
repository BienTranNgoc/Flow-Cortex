# syntax=docker/dockerfile:1

# --- build stage: the locked runtime dependencies + the worker, in /app/.venv ---
# Not pinned to $BUILDPLATFORM: uv installs native wheels (pydantic-core,
# rpds-py, ...) that must match the architecture the image runs on.
FROM python:3.12-slim AS build
COPY --from=ghcr.io/astral-sh/uv:0.12.21 /uv /usr/local/bin/uv
ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never
WORKDIR /app

COPY pyproject.toml uv.lock .python-version ./
# --no-build: dependencies come from wheels only, so no sdist setup code runs; uv
# still builds the worker itself.
RUN uv sync --frozen --no-build --no-dev --no-install-project
COPY src ./src
RUN uv sync --frozen --no-build --no-dev --no-editable

# --- runtime stage: ffmpeg from the distro, unprivileged user -------------------
FROM python:3.12-slim AS media-runtime
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg \
      fontconfig fonts-dejavu-core fonts-montserrat fonts-open-sans fonts-quicksand \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --no-create-home worker \
 && install -d -m 0700 -o worker -g worker /run/flowcortex

# Caption fonts: the build fails here, as root, when a declared family does not resolve to
# itself. fc-cache -f writes /var/cache/fontconfig, so it cannot run after USER.
COPY fonts/ /usr/share/fonts/truetype/flowcortex/
RUN fc-cache -f
COPY docker/check_fonts.py /opt/check_fonts.py
RUN python3 /opt/check_fonts.py

COPY --from=build /app/.venv /app/.venv
ENV PATH=/app/.venv/bin:$PATH \
    PYTHONUNBUFFERED=1
USER 10001
WORKDIR /tmp

HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=3 \
  CMD ["python", "-m", "flowcortex_worker.healthcheck"]

ENTRYPOINT ["python", "-m", "flowcortex_worker"]

# --- preview runtime: headless Blender for the white-block shot preview ---------
# Blender publishes no Linux arm64 build of 4.5, so the stage refuses any other target.
# BLENDER_BIN is read by the handler at call time. MEDIA_QUEUE names a lane the role check
# does not know yet, so a container started before the lane exists exits 2 instead of
# taking jobs off media.jobs. LIBGL_ALWAYS_SOFTWARE keeps Mesa on llvmpipe whatever the
# host exposes under /dev/dri.
FROM python:3.12-slim AS preview-runtime
ARG TARGETARCH
ARG BLENDER_URL=https://download.blender.org/release/Blender4.5/blender-4.5.14-linux-x64.tar.xz
ARG BLENDER_SHA256=9ba871ff2ecd36526b77432745980b7e6664ecd0c7ca11c48849073dcfe06da3
RUN if [ "$TARGETARCH" != "amd64" ]; then \
      echo "preview-runtime is amd64 only: Blender publishes no Linux arm64 build of 4.5 (target '$TARGETARCH')" >&2; \
      exit 1; \
    fi \
 && apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates curl libegl-mesa0 libegl1 libgl1 libgl1-mesa-dri libice6 libsm6 libx11-6 \
      libxfixes3 libxi6 libxkbcommon0 libxrender1 libxxf86vm1 xz-utils \
 && curl -fsSL --proto '=https' --tlsv1.2 --retry 3 -o /tmp/blender.tar.xz "$BLENDER_URL" \
 && echo "$BLENDER_SHA256  /tmp/blender.tar.xz" | sha256sum -c - \
 && mkdir /opt/blender \
 && tar -xJf /tmp/blender.tar.xz --strip-components=1 -C /opt/blender \
 && rm /tmp/blender.tar.xz \
 && apt-get purge -y --auto-remove ca-certificates curl xz-utils \
 && rm -rf /var/lib/apt/lists/* \
 && /opt/blender/blender --version | head -n 1 | grep -q '^Blender 4\.5\.14' \
 && useradd --system --uid 10001 --no-create-home worker \
 && install -d -m 0700 -o worker -g worker /run/flowcortex

COPY --from=build /app/.venv /app/.venv
COPY blender/preview_scene.py /opt/flowcortex/blender/preview_scene.py
COPY fonts/be-vietnam-pro/ /usr/share/fonts/truetype/flowcortex/be-vietnam-pro/
COPY docker/preview_gate.py docker/preview-smoke-doc.json /opt/flowcortex/docker/
ENV PATH=/app/.venv/bin:$PATH \
    PYTHONUNBUFFERED=1 \
    BLENDER_BIN=/opt/blender/blender \
    WORKER_CAPABILITIES=preview_video \
    MEDIA_QUEUE=media.preview.jobs \
    LIBGL_ALWAYS_SOFTWARE=1 \
    HOME=/tmp
USER 10001
WORKDIR /tmp

# The image does not ship unless a half-second shot renders through the real handler.
RUN python /opt/flowcortex/docker/preview_gate.py

HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=3 \
  CMD ["python", "-m", "flowcortex_worker.healthcheck"]

ENTRYPOINT ["python", "-m", "flowcortex_worker"]

FROM python:3.12-slim AS provider-runtime
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --no-create-home worker \
 && install -d -m 0700 -o worker -g worker /run/flowcortex

COPY --from=build /app/.venv /app/.venv
ENV PATH=/app/.venv/bin:$PATH \
    PYTHONUNBUFFERED=1
USER 10001
WORKDIR /tmp

HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=3 \
  CMD ["python", "-m", "flowcortex_worker.healthcheck"]

ENTRYPOINT ["python", "-m", "flowcortex_worker.provider"]

# --- overlay-runtime: CPU torch, EasyOCR, OpenCV and the baked models ---------------------
# Consumed by no compose service yet; CI builds it and runs the offline self-check.
FROM build AS build-overlay
COPY engines/requirements-overlay.txt ./engines/requirements-overlay.txt
RUN uv sync --frozen --no-build --no-dev --no-editable --extra overlay
# torch and torchvision live on the PyTorch CPU index and the rest on PyPI, so the index
# strategy must look at both; --require-hashes keeps every wheel pinned to the lock file.
RUN uv pip install --python /app/.venv/bin/python --require-hashes --no-deps --no-build \
      --index-strategy unsafe-best-match -r engines/requirements-overlay.txt

FROM python:3.12-slim AS overlay-models
# Pins of the files that end up in /opt/flowcortex/models; overlay-runtime checks them again
# after the copy, so a swapped file fails the build at either place.
ARG LAMA_SHA256=7ba7aa7ac37a4d41fdbbeba3a2af7ead18058552997e3a3cd1a3b2210c9e6b4c
ARG CRAFT_SHA256=4a5efbfb48b4081100544e75e1e2b57f8de3d84f213004b14b85fd4b3748db17
ARG RECOGNISER_SHA256=aaa95be1c4a9cb3496879bed7c520886ce1164f89e026f0c54488394e74e8c55
ARG YUNET_SHA256=8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl unzip \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /models
# EasyOCR creates both directories on startup, so they exist and stay empty of anything else.
RUN set -eux; \
    mkdir -p lama easyocr/model easyocr/user_network; \
    fetch() { local url="$1" dest="$2"; curl -fsSL --proto '=https' --tlsv1.2 --retry 5 --retry-delay 10 --retry-all-errors -o "$dest" "$url"; }; \
    fetch https://github.com/enesmsahin/simple-lama-inpainting/releases/download/v0.1.0/big-lama.pt lama/big-lama.pt; \
    fetch https://github.com/JaidedAI/EasyOCR/releases/download/pre-v1.1.6/craft_mlt_25k.zip /tmp/craft.zip; \
    fetch https://github.com/JaidedAI/EasyOCR/releases/download/v1.3/latin_g2.zip /tmp/latin_g2.zip; \
    unzip -q /tmp/craft.zip -d easyocr/model; \
    unzip -q /tmp/latin_g2.zip -d easyocr/model; \
    rm /tmp/craft.zip /tmp/latin_g2.zip; \
    printf '%s  %s\n' \
      "$LAMA_SHA256" lama/big-lama.pt \
      "$CRAFT_SHA256" easyocr/model/craft_mlt_25k.pth \
      "$RECOGNISER_SHA256" easyocr/model/latin_g2.pth \
      "$YUNET_SHA256" face_detection_yunet_2023mar.onnx > /SHA256SUMS; \
    grep -v face_detection /SHA256SUMS | sha256sum -c -

FROM media-runtime AS overlay-runtime
USER root
COPY --from=build-overlay /app/.venv /app/.venv
COPY --from=overlay-models /models/ /opt/flowcortex/models/
COPY --from=overlay-models /SHA256SUMS /opt/flowcortex/SHA256SUMS
COPY models/face_detection_yunet_2023mar.onnx /opt/flowcortex/models/
WORKDIR /opt/flowcortex/models
RUN sha256sum -c ../SHA256SUMS
WORKDIR /tmp
ENV FLOWCORTEX_OVERLAY_MODELS=/opt/flowcortex/models \
    EASYOCR_MODULE_PATH=/opt/flowcortex/models/easyocr \
    WORKER_CAPABILITIES=clean_overlay
USER 10001
# --- audio-build stage: the locked venv plus the torch audio engines -------------------------
# One hash-pinned torch base (requirements-torch-cpu.txt), then the engines. Only demucs is a
# source distribution; it builds against the hash-pinned setuptools and wheel installed just
# before, without build isolation, so no build backend is fetched unpinned.
FROM build AS audio-build
COPY engines/ ./engines/
RUN uv pip install --python /app/.venv/bin/python --require-hashes --no-deps --only-binary :all: \
      --index-url https://download.pytorch.org/whl/cpu --extra-index-url https://pypi.org/simple \
      --index-strategy unsafe-best-match -r engines/requirements-torch-cpu.txt \
 && uv pip install --python /app/.venv/bin/python --require-hashes --no-deps --only-binary :all: \
      --index-url https://download.pytorch.org/whl/cpu --extra-index-url https://pypi.org/simple \
      --index-strategy unsafe-best-match -r engines/requirements-audio-build.txt \
 && uv pip install --python /app/.venv/bin/python --require-hashes --no-deps \
      --only-binary :all: --no-binary demucs --no-build-isolation \
      -r engines/requirements-audio.txt \
 && uv pip uninstall --python /app/.venv/bin/python wheel \
 && uv pip check --python /app/.venv/bin/python

# --- audio-models stage: every model is downloaded once and checked against a pinned digest ----
# A moved or replaced file fails the build; the runtime never touches the network.
FROM audio-build AS audio-models
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl \
 && rm -rf /var/lib/apt/lists/*
ENV TORCH_HOME=/models/torch
RUN /app/.venv/bin/python -c "from demucs.pretrained import get_model; get_model('htdemucs')"
WORKDIR $TORCH_HOME/hub/checkpoints
RUN printf '%s  %s\n' 8726e21a993978c7ba086d3872e7608d7d5bfca646ca4aca459ffda844faa8b4 955717e8-8726e21a.th \
    | sha256sum -c - \
 && test "$(ls | wc -l)" = 1
WORKDIR /models/speakers
RUN curl -fsSL --proto '=https' --tlsv1.2 --retry 5 --retry-delay 10 --retry-all-errors -o silero_vad.onnx \
      https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx \
 && curl -fsSL --proto '=https' --tlsv1.2 --retry 5 --retry-delay 10 --retry-all-errors \
      -o 3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx \
      https://huggingface.co/csukuangfj/speaker-embedding-models/resolve/0743f301363dec56491a490f6d6cbc9d67f9a3bf/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx \
 && printf '%s  %s\n' \
      9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6 silero_vad.onnx \
      aa3cfc16963a10586a9393f5035d6d6b57e98d358b347f80c2a30bf4f00ceba2 \
      3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx \
    | sha256sum -c -
WORKDIR /models/beat_this
RUN curl -fsSL --proto '=https' --tlsv1.2 --retry 5 --retry-delay 10 --retry-all-errors -o final0.ckpt \
      https://cloud.cp.jku.at/public.php/dav/files/7ik4RrBKTS273gp/final0.ckpt \
 && printf '%s  %s\n' 8c328b45f59d8dd3dff219253ff6a8d6482be57d0133a29140e2febbf8eb8331 final0.ckpt \
    | sha256sum -c -

# --- audio-runtime stage: ffmpeg, the venv with the engines, the models, the licences -----------
FROM python:3.12-slim AS audio-runtime
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg libgomp1 libsndfile1 \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --no-create-home worker \
 && install -d -m 0700 -o worker -g worker /run/flowcortex

COPY --from=audio-build /app/.venv /app/.venv
COPY --from=audio-models /models /app/models
COPY docker/AUDIO-LICENSES.md docker/speakers-NOTICE.txt /app/licenses/
# numba (librosa) caches compiled functions; the venv is read-only for user 10001, so the cache
# goes to /tmp or importing librosa.core fails.
ENV PATH=/app/.venv/bin:$PATH \
    PYTHONUNBUFFERED=1 \
    TORCH_HOME=/app/models/torch \
    NUMBA_CACHE_DIR=/tmp/numba-cache
USER 10001
WORKDIR /tmp

HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=3 \
  CMD ["python", "-m", "flowcortex_worker.healthcheck"]

ENTRYPOINT ["python", "-m", "flowcortex_worker"]

# --- trend-build stage: the locked venv plus the RT-DETR person detector ------------------------
# The same hash-pinned torch base as the audio image, then the transformers set (wheels only).
FROM build AS trend-build
COPY engines/requirements-torch-cpu.txt engines/requirements-trend.txt ./engines/
RUN uv pip install --python /app/.venv/bin/python --require-hashes --no-deps --only-binary :all: \
      --index-url https://download.pytorch.org/whl/cpu --extra-index-url https://pypi.org/simple \
      --index-strategy unsafe-best-match -r engines/requirements-torch-cpu.txt \
 && uv pip install --python /app/.venv/bin/python --require-hashes --no-deps \
      --only-binary :all: -r engines/requirements-trend.txt \
 && uv pip check --python /app/.venv/bin/python

# --- trend-models stage: the detector files at a pinned revision, checked against pinned digests --
# A moved or replaced file fails the build; the runtime never touches the network.
FROM trend-build AS trend-models
ARG RTDETR_REVISION=df939e661d8c52e80608d1ec566561aabd25a4e7
ARG RTDETR_CONFIG_SHA256=2ed2a305c51eef46715eb755a02b2a266ecfb752936cc9574bb5714601c2742d
ARG RTDETR_PREPROCESSOR_SHA256=ffb4b9461a1dad746be8f0f9c8330ed7743a1ba5fba4f75c232cd281b3d4c64a
ARG RTDETR_WEIGHTS_SHA256=5263d5521eff3e356f6cd8a371fd5dfb891725beda5f713674f79669115cdc64
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /models/trend/rtdetr_r50vd
RUN set -eux; \
    for file in config.json preprocessor_config.json model.safetensors; do \
      curl -fsSL --proto '=https' --tlsv1.2 --retry 5 --retry-delay 10 --retry-all-errors \
        -o "$file" \
        "https://huggingface.co/PekingU/rtdetr_r50vd/resolve/$RTDETR_REVISION/$file"; \
    done; \
    printf '%s  %s\n' \
      "$RTDETR_CONFIG_SHA256" config.json \
      "$RTDETR_PREPROCESSOR_SHA256" preprocessor_config.json \
      "$RTDETR_WEIGHTS_SHA256" model.safetensors \
    | sha256sum -c -; \
    test "$(ls | wc -l)" = 3

# --- trend-runtime stage: ffmpeg, the venv with the detector, the model, the licences -----------
# MEDIA_QUEUE names a lane the role check does not know yet (the lane arrives with the backend
# enum value that routes role suggestion), so a container started before then exits 2 instead of
# taking jobs off media.jobs. HF_HOME is a writable path for libraries that create a cache dir;
# the offline switches keep every load on the baked files.
FROM python:3.12-slim AS trend-runtime
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg libgomp1 \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --no-create-home worker \
 && install -d -m 0700 -o worker -g worker /run/flowcortex

COPY --from=trend-build /app/.venv /app/.venv
COPY --from=trend-models /models/trend /app/models/trend
COPY docker/TREND-LICENSES.md /app/licenses/
ENV PATH=/app/.venv/bin:$PATH \
    PYTHONUNBUFFERED=1 \
    HF_HUB_OFFLINE=1 \
    TRANSFORMERS_OFFLINE=1 \
    HF_HOME=/tmp/hf \
    WORKER_CAPABILITIES=role_suggest \
    MEDIA_QUEUE=media.trend.jobs
USER 10001
WORKDIR /tmp

HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=3 \
  CMD ["python", "-m", "flowcortex_worker.healthcheck"]

ENTRYPOINT ["python", "-m", "flowcortex_worker"]
