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
RUN python3 /opt/check_fonts.py /usr/share/fonts/truetype/flowcortex/manifest.json

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

# --- audio-build stage: the locked venv plus the torch audio engines -------------------------
# One hash-pinned torch base (requirements-torch-cpu.txt), then the engines. Only demucs is a
# source distribution; it builds against the hash-pinned setuptools and wheel installed just
# before, without build isolation, so no build backend is fetched unpinned.
FROM build AS audio-build
COPY engines/ ./engines/
RUN uv pip install --python /app/.venv/bin/python --require-hashes --no-deps \
      --index-url https://download.pytorch.org/whl/cpu --extra-index-url https://pypi.org/simple \
      --index-strategy unsafe-best-match -r engines/requirements-torch-cpu.txt \
 && uv pip install --python /app/.venv/bin/python --require-hashes --no-deps \
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
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*
ENV TORCH_HOME=/models/torch
RUN /app/.venv/bin/python -c "from demucs.pretrained import get_model; get_model('htdemucs')" \
 && cd "$TORCH_HOME/hub/checkpoints" \
 && printf '%s  %s\n' 8726e21a993978c7ba086d3872e7608d7d5bfca646ca4aca459ffda844faa8b4 955717e8-8726e21a.th \
    | sha256sum -c - \
 && test "$(ls | wc -l)" = 1
RUN mkdir -p /models/speakers && cd /models/speakers \
 && curl -fsSL --retry 5 --retry-delay 10 --retry-all-errors -o silero_vad.onnx \
      https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx \
 && curl -fsSL --retry 5 --retry-delay 10 --retry-all-errors \
      -o 3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx \
      https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx \
 && printf '%s  %s\n' \
      9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6 silero_vad.onnx \
      aa3cfc16963a10586a9393f5035d6d6b57e98d358b347f80c2a30bf4f00ceba2 \
      3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx \
    | sha256sum -c -
RUN mkdir -p /models/beat_this && cd /models/beat_this \
 && curl -fsSL --retry 5 --retry-delay 10 --retry-all-errors -o final0.ckpt \
      https://cloud.cp.jku.at/public.php/dav/files/7ik4RrBKTS273gp/final0.ckpt \
 && printf '%s  %s\n' 8c328b45f59d8dd3dff219253ff6a8d6482be57d0133a29140e2febbf8eb8331 final0.ckpt \
    | sha256sum -c -

# --- audio-runtime stage: ffmpeg, the venv with the engines, the models, the licences -----------
FROM python:3.12-slim AS audio-runtime
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg libsndfile1 libgomp1 \
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
