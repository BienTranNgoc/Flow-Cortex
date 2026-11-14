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

## Preview video (`preview_video`)

Renders a white-block preview of one storyboard shot: dummies, stage, props, camera and
dialogue timing from a reduced storyboard document, through headless Blender 4.5 (Workbench,
software GL). The handler is registered but no job can reach it yet: `MediaJob.capability` has no
`preview_video` value until the enum owner adds `video.preview` with its queue, routing and
max-runtime (set from the cost figures below).

- **Input** — `doc` only: a storyboard document (`story.shots`, `story.sets`, `characters`);
  `tests/fixtures/preview/` holds five.
- **Params** — `shotId` (string), `aspect` (`9:16`, `16:9` or `1:1`), `send` (boolean, default
  false: also render the send version, which carries no labels, subtitles, extras or walls). Any
  other param, input or a wrong-typed leaf of the document is refused before Blender starts.
- **Outputs** — `preview.mp4` (24 fps H.264 at 496×864, 854×480 or 640×640), `staging-card.json`,
  and `preview-send.mp4` when `send` is true. Data: `shotId`, `frames`, `width`, `height`,
  `durationSec`, `subjectInFrame` (false still succeeds), `blender`, `renderer`, `renderSec`
  (Blender's own build + render seconds, summed over both runs) and `warnings` (motions
  shortened or dropped, each with a closed `reason`).
- **Failures** — a refused document or param, a missing Blender, a non-zero Blender exit, or an
  exit without a readable video and report is `PermanentError` `contract_violation`; Blender
  ended by a signal (OOM kill) is a `TransientError` and is retried. The last 25 lines of Blender's
  output are logged with the exit code.
- **Environment** — `BLENDER_BIN` (default `/opt/blender/blender`), `PREVIEW_SCENE_SCRIPT`,
  `PREVIEW_FONT`, read at call time.

**Image `preview-runtime`** — `docker build --target preview-runtime worker/`, amd64 only (Blender
publishes no Linux arm64 build of 4.5; the stage stops on any other `TARGETARCH`). The tarball is
downloaded from `download.blender.org` and checked against its sha256, Blender lives in
`/opt/blender`, and the build runs `docker/preview_gate.py`: half a second of the smoke document
must render through the real handler as 12 frames at 496×864 with the subject in frame, or the
image is not produced. The stage sets `WORKER_CAPABILITIES=preview_video`,
`MEDIA_QUEUE=media.preview.jobs` and `LIBGL_ALWAYS_SOFTWARE=1` (Mesa stays on llvmpipe even when
the host exposes `/dev/dri`, so production renders the way the cost below was measured). The
role check refuses `media.preview.jobs` until the enum owner adds it to `MEDIA_QUEUES`, so a
container started early exits 2 instead of consuming `media.jobs`; that owner adds the queue with
no edit to the image.

**Scene tests** — `BLENDER_BIN=/path/to/blender uv run pytest tests/test_preview_scene.py -rs`
(skipped without `BLENDER_BIN`; about a minute on a 12-core machine, software rendering). The
`preview` CI job runs them against the pinned tarball, builds the image and measures the cost.
