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
RUN uv pip install --python /app/.venv/bin/python --require-hashes --no-deps \
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
    fetch() { curl -fsSL --retry 5 --retry-delay 10 --retry-all-errors -o "$2" "$1"; }; \
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
RUN cd /opt/flowcortex/models && sha256sum -c ../SHA256SUMS
ENV FLOWCORTEX_OVERLAY_MODELS=/opt/flowcortex/models \
    EASYOCR_MODULE_PATH=/opt/flowcortex/models/easyocr
USER 10001
