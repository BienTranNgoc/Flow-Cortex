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
      ca-certificates curl xz-utils \
      libx11-6 libxi6 libxxf86vm1 libxfixes3 libxrender1 libxkbcommon0 libsm6 libice6 \
      libgl1 libegl1 libegl-mesa0 libgl1-mesa-dri \
 && curl -fsSL --retry 3 -o /tmp/blender.tar.xz "$BLENDER_URL" \
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
