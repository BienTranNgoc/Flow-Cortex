# flowcortex-worker

Consumes media jobs from RabbitMQ (`media.jobs`), reads the uploaded object from the store,
runs the capability (`probe` = ffprobe) and publishes progress and the result back through
exchange `media`. Message shapes live in `../contracts/*.schema.json`.

**Run** — `docker compose up worker` from the repo root (env in `.env`, see `.env.example`), or
locally with the variables of `src/flowcortex_worker/config.py` set: `uv run python -m flowcortex_worker`.
The API declares the broker topology; the worker only consumes and publishes.

**Test** — `uv sync && uv run ruff check . && uv run mypy && uv run pytest`. Needs Docker
(Testcontainers starts `rabbitmq:4.3.6-management` and the pinned Silo image) and `ffmpeg` /
`ffprobe` on `PATH` (`apt-get install ffmpeg`). `tests/test_broker_semantics.py` pins the broker's
delivery-limit, reject/nack and consumer-timeout behaviour; it takes about 10 s.

**Regenerate the contract models** — after editing a schema, run `bash scripts/gen_contracts.sh`
and commit `src/flowcortex_worker/contracts/`; never edit those files by hand (CI diffs them).

**Telemetry** — standard `OTEL_*` variables; `OTEL_TRACES_EXPORTER=none` (and `_LOGS_`, `_METRICS_`)
keeps spans and trace ids in the JSON logs without a collector.
