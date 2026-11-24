# flowcortex-worker

One Python codebase serves two isolated runtimes:

- the media worker consumes the queue `MEDIA_QUEUE` names — `media.jobs` (the default) or
  `media.render.jobs` (`render_video`, `compose_dub`, compose service `render-worker`) or `media.audio.jobs`
  (`separate_audio`, compose service `audio-worker`) — reads assets, runs FFmpeg/FFprobe
  capabilities and publishes through exchange `media`. It runs the capabilities
  `WORKER_CAPABILITIES` lists, comma-separated (empty = every capability except the opt-in
  `preview_video` and `clean_overlay`, which need a dedicated image and run only when named). An
  unknown queue or capability name, or a listed handler that fails to import, exits non-zero
  before it connects; a job for a capability the worker does not run is a permanent
  `contract_violation`;
- the provider worker consumes the queue `PROVIDER_QUEUE` names — `provider.jobs` (script,
  storyboard, speech; the default) or `provider.video.jobs` (video clips, compose service
  `provider-video-worker`) — runs provider capabilities and publishes through
  exchange `provider`. Its deterministic fixture adapter is for development until OpenAI is wired.

Message shapes live in `../contracts/*.schema.json`.

**Audio lane** — `audio-worker` builds the `audio-runtime` Dockerfile target (the default `docker
build` target; `media-runtime` and `provider-runtime` are unchanged) on `media.audio.jobs` and
runs `separate_audio`. The image bakes torch (CPU), demucs `htdemucs`, the speaker and VAD models
and the beat model, each verified against a pinned sha256, so the engines need no network at run
time. Engines (`engines/separate.py`, `speakers.py`, `beats.py`) run as child processes of the
handler, so an out-of-memory kill or crash fails one job, not the worker. Licence texts for the
bundled models and libraries are under `/app/licenses` (`worker/docker/AUDIO-LICENSES.md`,
`speakers-NOTICE.txt`). Pool size: `MEDIA_AUDIO_WORKERS`; longest run:
`FLOWCORTEX_MEDIA_MAX_RUNTIME_SEPARATE_AUDIO`.

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
  torch. The handler is registered under `clean_overlay` through `HANDLER_MODULES` and is opt-in:
  the image sets `WORKER_CAPABILITIES=clean_overlay`, and a worker without that name never loads it.
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

## Preview video (`preview_video`)

Renders a white-block preview of one storyboard shot: dummies, stage, props, camera and
dialogue timing from a reduced storyboard document, through headless Blender 4.5 (Workbench,
software GL). `preview_video` is the worker capability, the name in `WORKER_CAPABILITIES` and
`HANDLER_MODULES`. The handler is registered but no job can reach it yet: `MediaJob.capability`
has no matching value until the enum owner adds the backend value `video.preview` with its queue,
routing and max-runtime (set from the cost figures below). It is opt-in: only a worker whose
`WORKER_CAPABILITIES` names `preview_video` (the `preview-runtime` image) serves it.

- **Input** — `doc` only: a storyboard document (`story.shots`, `story.sets`, `characters`);
  `tests/fixtures/preview/` holds five.
- **Params** — `shotId` (string), `aspect` (`9:16`, `16:9` or `1:1`), `send` (boolean, default
  false: also render the send version, which carries no labels, subtitles, extras or walls). Any
  other param, input, a wrong-typed leaf of the document, a document nested deeper than the JSON
  parser allows, or a shot longer than 720 frames (30 s at 24 fps) is refused before Blender starts.
- **Outputs** — `preview.mp4` (24 fps H.264 at 496×864, 854×480 or 640×640), `staging-card.json`,
  and `preview-send.mp4` when `send` is true. Data: `shotId`, `frames`, `width`, `height`,
  `durationSec`, `subjectInFrame` (false still succeeds), `blender`, `renderer`, `renderSec`
  (Blender's own build + render seconds, summed over both runs) and `warnings` (motions
  shortened or dropped, each with a closed `reason`).
- **Failures** — a refused document or param, a non-zero Blender exit, or an exit without a
  readable video and report is `PermanentError` `contract_violation`; a missing Blender is
  `provider_capability_unavailable` (the job is valid, the host lacks the tool); Blender
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

### Preview render cost

Measured by the `preview` CI job inside the built `preview-runtime` image: `docker run --cpus=4
--network none`, no `--device`, `preview_bench.py` over the 22 shots of the five fixture documents
at 9:16 (96 to 264 frames each, 3504 frames per version), through the real handler and
`ProcessRunner`. The bench fails, and prints no figures, unless every run reports a `llvmpipe`
renderer. Seconds are the handler call's wall time, as the job watchdog counts it.

| | per shot P50 | per shot P95 | per frame P50 | per frame P95 |
|---|---|---|---|---|
| preview | 27.43 s | 46.76 s | 0.187 s | 0.219 s |
| job with `send: true` (preview + send) | 53.77 s | 94.27 s | 0.362 s | 0.439 s |

Slowest single runs: 56.76 s (preview) and 115.96 s (job with `send: true`). CPU: AMD EPYC 9V45 96-Core Processor (a GitHub-hosted
`ubuntu-latest` runner limited to 4 CPUs), renderer `llvmpipe (LLVM 19.1.7, 256 bits)`, Blender
4.5.14 LTS. Run: https://github.com/BienTranNgoc/Flow-Cortex/actions/runs/43612114231 (job
`worker / preview`, step "Measure the render cost"). The 22 shots are a sample, so the handler caps
a shot at **720 frames (30 s at 24 fps)**, `MAX_FRAME_COUNT` in `preview/vocabulary.py`. At the
send-job per-frame P95 (0.439 s) a capped job takes about 316 s, and about 158 s without `send`
(0.219 s per frame). The enum owner sets the capability's max-runtime above the send figure with
headroom for runner speed (clean overlay P95 varied 2x between runners): 10 minutes leaves about
1.9x. Raise the cap only together with that max-runtime.
