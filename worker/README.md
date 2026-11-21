# flowcortex-worker

One Python codebase serves two isolated runtimes:

- the media worker consumes the queue `MEDIA_QUEUE` names — `media.jobs` (the default) or
  `media.render.jobs` (`render_video`, compose service `render-worker`) — reads assets, runs
  FFmpeg/FFprobe capabilities and publishes through exchange `media`. It runs the capabilities
  `WORKER_CAPABILITIES` lists, comma-separated (empty = all). An unknown queue or capability
  name, or a listed handler that fails to import, exits non-zero before it connects;
- the provider worker consumes the queue `PROVIDER_QUEUE` names — `provider.jobs` (script,
  storyboard, speech; the default) or `provider.video.jobs` (video clips, compose service
  `provider-video-worker`) — runs provider capabilities and publishes through
  exchange `provider`. Its deterministic fixture adapter is for development until OpenAI is wired.

Message shapes live in `../contracts/*.schema.json`.

**Run** — `docker compose up worker provider-worker provider-video-worker` from the repo root (env in `.env`, see
`.env.example`). Local entrypoints are `uv run python -m flowcortex_worker` and
`uv run python -m flowcortex_worker.provider`. The API declares both broker topologies; workers
only consume and publish.

**Clear a dead letter** — inspect the message in the management UI (`127.0.0.1:15672`), then
`docker compose exec rabbitmq rabbitmqctl purge_queue <queue>` once it has been handled; a DLQ has
no TTL or consumer, so `WorkerDeadLetters` keeps firing until it is emptied.

**Test** — `uv sync && uv run ruff check . && uv run mypy && uv run pytest`. Needs Docker
(Testcontainers starts `rabbitmq:4.3.6-management` and the pinned Silo image) and `ffmpeg` /
`ffprobe` on `PATH` (`apt-get install ffmpeg`). `tests/test_broker_semantics.py` pins the broker's
delivery-limit, reject/nack and consumer-timeout behaviour; it takes about 10 s.

**Regenerate the contract models** — after editing a schema, run `bash scripts/gen_contracts.sh`
and commit `src/flowcortex_worker/contracts/`; never edit those files by hand (CI diffs them).

**Telemetry** — standard `OTEL_*` variables; `OTEL_TRACES_EXPORTER=none` (and `_LOGS_`, `_METRICS_`)
keeps spans and trace ids in the JSON logs without a collector.
