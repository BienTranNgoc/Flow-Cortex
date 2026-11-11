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

**Image clean overlay** — the `clean_overlay` capability removes watermark-like overlays and
burned-in captions from one image: faces are protected, caption and logo regions are detected
(EasyOCR CRAFT plus a repeated-pattern search, or the `customRegions` boxes), filled with LaMa, and
the recognised text can be returned as layers.

- Params: `region` (`auto` or `manual`), `customRegions` (boxes `x`, `y`, `w`, `h` as ratios 0-1),
  `strength` (1-5), `extractText` (boolean). Any other key, or a value out of range, ends as
  `contract_violation`. Result: `clean.png` (BGRA when a PNG/WebP input had transparency) and the
  pipeline's result keys, among them `regionsRemoved` and `textLayers`.
- It runs only on the `overlay-runtime` image (`docker build --target overlay-runtime worker/`);
  there is no compose service until the owner of the capability enum adds the lane. The default
  `media-runtime` and `provider-runtime` images are unchanged and never import numpy, OpenCV or
  torch. The handler is registered under `clean_overlay` through `HANDLER_MODULES`.
- Models live in `/opt/flowcortex/models` (`FLOWCORTEX_OVERLAY_MODELS`), sha256-checked at build
  time and again in the final stage; nothing is downloaded at run time. Licences and source URLs:
  `models/MODEL-LICENSES.md`.
- Only the child process decodes the image: PNG, JPEG and WebP up to `overlay/protocol.MAX_PIXELS`
  (4,194,304 = 2048×2048, the largest benchmarked size within budget); anything larger is
  `input_rejected`.
- **Measure** — `docker run --rm --network none --memory 6g --entrypoint python -v
  "$PWD/worker/scripts:/bench:ro" <overlay image> /bench/bench_clean_overlay.py` prints P50, P95 and
  peak RSS per size (1024², 2048², 3840×2160, 5000²), the CPU model and the torch thread count.
  Budget: 900 s wall and 6 GiB RSS per image. A size above `MAX_PIXELS` is run once and must end
  as `input_rejected` ("rejected by pixel cap (expected)"); only sizes within the cap count toward
  over budget. The `overlay-image` CI job runs it after the self-check and fails when a size
  within the cap is over budget; then lower `MAX_PIXELS` to the largest size within budget.
- CI measurement, latest run (GitHub-hosted runner, `cpu: AMD EPYC 9V45 96-Core Processor`, 4
  CPUs, 4 torch threads, `--network none --memory 6g`): 1024² P95 26.9 s (P50 26.0 s), peak RSS
  2.57 GiB; 2048² P95 120.0 s (P50 117.5 s), peak RSS 4.70 GiB; 3840×2160 and 5000² are rejected by
  the pixel cap. Runner speed varies between runs (2048² P95 was 225.0 s on an EPYC 9V74 runner),
  so the time budget has wide headroom while memory is the limiter: before the cap existed,
  3840×2160 peaked at 6.11 GiB (over the 6 GiB budget) and 5000² was killed by signal 9 (OOM).
  Hence the 2048² cap. The production worker host's figures are still to be added there.
- Promotion rule (ADR-0011): the measured P95 is compared with
  `flowcortex.media.max-runtime.clean_overlay` once the enum owner adds the capability; the
  max-runtime must sit above it with headroom.
