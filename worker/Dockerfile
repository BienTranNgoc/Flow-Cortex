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
FROM python:3.12-slim AS runtime
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

ENTRYPOINT ["python", "-m", "flowcortex_worker"]
