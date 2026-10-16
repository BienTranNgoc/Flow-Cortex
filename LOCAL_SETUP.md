# Local setup — Flow-Cortex

Goal: a fresh clone runs in a few minutes.

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Docker | with Compose v2 | Enough on its own for the whole stack (§3). The backend and worker tests also need it (Testcontainers). |
| JDK  | 25      | `JAVA_HOME` must point at it — Gradle here is **not** configured to auto-download a JDK. |
| Node | ≥ 20    | For the member app, the admin console and the shared packages (npm workspaces). |
| uv   | any recent | Only for working on the worker (§2b); it installs Python 3.12 itself. |
| Git  | any     | Hooks are enabled per clone (step 0). |

Check the JDK:

```bash
echo "$JAVA_HOME"
java -version   # should report 25.x
```

## 0. Enable git hooks (once per clone)

```bash
bash scripts/setup-hooks.sh
```

This points `core.hooksPath` at `.githooks/`, activating:
- **commit-msg** — rejects commits that are not Conventional Commits or not in English.
- **pre-push** — blocks direct pushes to `main` / `master`.

Branch flow: `main` is production (maps to Xood's `production`). Branch off
`development`, open the PR into `development` (`gh pr create --base development`);
only `development` is merged into `main` on release.

**Windows:** line endings are pinned to LF by `.gitattributes`. If a clone made
before it landed still has CRLF files, re-checkout once on a clean tree:

```bash
git config core.autocrlf false
git rm -r --cached -q . && git reset --hard HEAD
```

## 1. Backend (Spring Boot)

The backend needs PostgreSQL (Flyway, `ddl-auto: validate`), RabbitMQ and the
object store to become ready. The tests bring their own (Testcontainers, needs
Docker running); `bootRun` needs them started yourself — easiest are the compose
ones (their ports are published on 127.0.0.1):

```bash
cd backend

./gradlew build            # compile + run all tests (Testcontainers → needs Docker)

# Start the database, the broker and the store (compose reads .env):
cp ../.env.example ../.env         # once — then replace every change-me value
docker compose -f ../docker-compose.yml up -d --wait postgres rabbitmq minio minio-init

set -a; . ../.env; set +a          # export the values from .env
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/flowcortex \
SPRING_DATASOURCE_USERNAME=flowcortex \
SPRING_DATASOURCE_PASSWORD=$POSTGRES_PASSWORD \
SPRING_RABBITMQ_USERNAME=$RABBITMQ_DEFAULT_USER \
SPRING_RABBITMQ_PASSWORD=$RABBITMQ_DEFAULT_PASS \
S3_ENDPOINT=http://localhost:9000 \
S3_PUBLIC_ENDPOINT=http://localhost:9000 \
S3_ACCESS_KEY=$BACKEND_S3_ACCESS_KEY \
S3_SECRET_KEY=$BACKEND_S3_SECRET_KEY \
SPRING_PROFILES_ACTIVE=dev \
  ./gradlew bootRun                # start the API on http://localhost:8080

# Public readiness probe (no auth). It is 503 until the backend has declared the
# broker topology at startup:
curl http://localhost:8080/readyz
# -> {"status":"UP"}

# /api/v1/** is authenticated — log in first, then use the token:
curl -s -XPOST http://localhost:8080/api/v1/auth/login \
  -H 'content-type: application/json' \
  -d '{"email":"admin1@flowcortex.dev","password":"AdminPass123!"}'
# -> { "accessToken": "...", "expiresIn": 900 }  (the refresh token arrives as an HttpOnly cookie)
```

The `dev` profile seeds two admins, `admin1@flowcortex.dev` and
`admin2@flowcortex.dev` (`AdminPass123!`), and one member, `member1@flowcortex.dev`
(`MemberPass123!`), plus three shared demo workflows: "Echo Demo", "Failing Demo"
and "Check a video file". These are dev-only credentials, never real passwords.
An admin's **Users** page lists only the users that admin created: `member1`
belongs to `admin1`, so sign in to the console as `admin1` to see it; `admin2`
starts with no users of its own. The **Runs** and **Media jobs** pages list every
user's runs, whichever admin signs in.

Health and metrics (`/actuator/health`, `/actuator/prometheus`) answer on the
management port 8081, which compose never publishes; on 8080 use `/livez` and
`/readyz`.

Coverage report after `./gradlew test` (multi-module — the boot app aggregates ITs):
`backend/boot/app/build/reports/jacoco/test/html/index.html` (and each
`backend/modules/<m>/build/reports/jacoco/test/html/` for a module in isolation).

## 2. Member app and admin console (Vite + React, npm workspaces)

`frontend/` (member app), `admin/` (admin console) and `packages/*` (the shared
API client and UI pieces) are npm workspaces with one lockfile at the root, so
install from the root, never inside a workspace:

```bash
npm install                            # once, at the repo root

npm run dev -w frontend                # member app on http://localhost:5173 (or the next free port)
npm run dev -w admin                   # admin console on http://localhost:5174

npm test                               # Vitest, every workspace
npm run lint                           # ESLint, every workspace
npm run typecheck                      # tsc, every workspace
npm run test -w admin                  # one workspace only
npm run gen:api                        # regenerate packages/api-client from docs/openapi.yaml
```

Both apps call the API at the relative path `/api/v1`, so nothing needs
configuring: each dev server proxies `/api` to the backend of §1 on
<http://localhost:8080>, the way the nginx images do in compose. Start the
backend first. The backend sends no CORS headers, so open the apps on their dev
server ports, never through another origin.

## 2b. Media worker (Python)

```bash
cd worker
uv sync
uv run ruff check . && uv run mypy && uv run pytest
```

The tests start RabbitMQ and the object store with Testcontainers (Docker must be
running) and run the real `ffprobe`, so `ffmpeg` must be on `PATH`
(`apt-get install ffmpeg`). Details: [worker/README.md](worker/README.md).

## 3. Run the whole stack with Docker (optional)

For a one-command end-to-end run (or a demo) without installing a JDK, Node or
Python locally — only Docker is needed:

```bash
cp .env.example .env               # once — replace every change-me value (see the comments in it)
docker compose up --build
```

`.env.example` lists every variable compose reads, with what each one does. It
sets `SPRING_PROFILES_ACTIVE=dev`, so the stack seeds the demo accounts and the
three demo workflows of §1 (Echo, Failing, "Check a video file"); remove that
line for any shared or deployed stack.

`docker compose ps -a` then shows nine services `healthy` and `rabbitmq-init` and
`minio-init` `Exited (0)`:

| Service | What it is | Address (all bound to 127.0.0.1) |
|---|---|---|
| `postgres` | the database | not published |
| `rabbitmq` | the broker: `media.*` and `provider.*` job/result/progress queues | AMQP `localhost:5672`, management UI <http://localhost:15672> (the `RABBITMQ_DEFAULT_*` account) |
| `minio` | the S3-compatible object store (the Silo image) | S3 API `localhost:9000`, console <http://localhost:9001> (the `MINIO_ROOT_*` account) |
| `rabbitmq-init` | one-shot: creates the provider worker's restricted broker user (`PROVIDER_RABBITMQ_*`), then exits | — |
| `minio-init` | one-shot: creates the buckets, the 1-day uploads lifecycle, the run-input expiry and one least-privilege user per service, then exits | — |
| `mailpit` | the mail sink: catches every email the backend sends (confirmation links); nothing is delivered | web UI <http://localhost:8025>, SMTP `localhost:1025` |
| `backend` | the API | <http://localhost:8080> (`/livez`, `/readyz`) |
| `worker` | the Python media worker | none — and no internet access |
| `provider-worker` | the Python provider worker (fixture adapter until OpenAI is connected) | none |
| `frontend` | the member app (nginx, proxies `/api` to the backend) | <http://localhost:5173> |
| `admin` | the admin console (same) | <http://localhost:5174> |

Open the member app at <http://localhost:5173> and sign in as `member1`.
Admins use the console at <http://localhost:5174>: Runs, Media jobs and Users
(user management moved there from the member app).

**Check a video file, end to end.** In the member app open "Check a video file",
pick a short video or audio file and press Start: the file is uploaded, the worker
inspects it, and the run page shows the duration, dimensions and codecs, with a
summary (`video-summary.txt`) and the technical details (`video-details.json`) to
download. The run then
appears on the console's Runs page. Any short phone video works; to make a
1-second test clip:

```bash
ffmpeg -f lavfi -i testsrc=duration=1:size=320x240:rate=25 -f lavfi -i sine=duration=1 \
  -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest /tmp/clip.mp4
# no ffmpeg on the host? use the worker image's:
docker compose exec -T worker ffmpeg -y -v error -f lavfi -i testsrc=duration=1:size=320x240:rate=25 \
  -f lavfi -i sine=duration=1 -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest /tmp/clip.mp4
docker compose cp worker:/tmp/clip.mp4 /tmp/clip.mp4
```

`scripts/smoke-probe.sh` runs the same flow from the command line against the
running stack (a clip, a wrong-size upload, a text file posing as `.mp4`, the
download and the admin list) and exits non-zero on any miss. With
`FLOWCORTEX_PRODUCT_VIDEO_ENABLED=true` set for both the stack and the script it
also takes a Product Video run through the provider worker, a script approval and
the media worker's render to a downloaded `video.mp4`. CI runs it, Product Video
included, on every PR that touches the stack (`.github/workflows/compose-smoke.yml`).

What to know about the media path:

- **Uploads go straight to the store.** The browser PUTs the file to a presigned
  URL, never through the API. Upload and download URLs are signed for
  `S3_PUBLIC_ENDPOINT` (`http://localhost:9000`, the store's loopback port), while
  the backend and the worker reach the store at `S3_ENDPOINT` (`http://minio:9000`,
  inside the compose network). Production is a configuration change only: Silo
  behind `https://media.<domain>`, DNS-only behind Cloudflare, since its proxy
  caps request bodies at 100 MB; a later move to Cloudflare R2 would use
  `https://<account>.r2.cloudflarestorage.com`, because R2 presigned URLs cannot
  use custom domains.
- **Upload limits.** `FLOWCORTEX_MEDIA_UPLOAD_MAX_VIDEO_BYTES` (500 MB),
  `FLOWCORTEX_MEDIA_UPLOAD_MAX_AUDIO_BYTES` (100 MB), `FLOWCORTEX_MEDIA_UPLOAD_MAX_IMAGE_BYTES`
  (10 MB) and `FLOWCORTEX_MEDIA_UPLOAD_EXTENSIONS` (`mp4,mov,webm,mkv,m4a,mp3,wav`). The server
  also reads each file's first bytes and refuses one whose content does not match its extension
  (`FLOWCORTEX_MEDIA_UPLOAD_CONTENT_CHECK_ENABLED`). An upload
  URL is valid for 15 minutes, and uploaded files are deleted after **1 day**.
  A run keeps its own copy of each input image (`runs/<run>/inputs/` in the media
  bucket), tagged so the store deletes it after `RUN_INPUT_RETENTION_DAYS` (30);
  keep that at least as long as a run may wait for its script review.
- **Browser origins.** `MINIO_API_CORS_ALLOW_ORIGIN` lists the origins allowed to
  upload: `http://localhost:5173,http://127.0.0.1:5173`. Add the production origin
  later; never `*`.
- **No run history yet.** The run page's address is the only way back to a run,
  so download the report before you leave the page.
- **The worker has no internet egress**, by design: its only network is the
  internal compose network with the broker and the store.
- **Every published port is loopback-only** (`127.0.0.1`). The backend's
  management port 8081 and the broker's metrics port 15692 are not published.
- **Store keys.** The backend, media worker and provider worker each have their own store user
  (`BACKEND_S3_*`, `WORKER_S3_*`, `PROVIDER_S3_*`); the root account (`MINIO_ROOT_*`) is used only by
  `minio` and `minio-init`. The backend and the media worker share the broker account
  (`RABBITMQ_DEFAULT_*`); the provider worker has its own (`PROVIDER_RABBITMQ_*`), which
  can only consume `provider.jobs` and publish to the `provider` exchange.

**Kill switches (rollout notes).** Each is an env var in `.env`, read at startup;
apply a change with `docker compose up -d backend`.

- `FLOWCORTEX_MEDIA_ENABLED=false`: the member app's upload and the start of a run
  with a media step answer 503 "Media engine is currently disabled", and the start
  form says so; a media step that was already claimed fails with "The media engine
  is switched off right now." Text-only workflows still run.
- `FLOWCORTEX_ADMIN_ENABLED=false`: every `/api/v1/admin/**` call answers 503 and
  the console's Runs and Media jobs pages show "Admin console is disabled". The
  member app is unaffected.
- `FLOWCORTEX_WORKFLOW_ENABLED=false`: no new runs (503); started and finished runs
  stay readable.

**Demo switches.** Compose turns all five on; `application.yml` keeps the first four
off for a bare `bootRun` and any environment that does not set them. Same mechanism
as the kill switches: env var in `.env`, then `docker compose up -d backend`.
`GET /api/v1/features` (no sign-in needed) answers what the running backend has, and
the member app reads it once per session.

| Variable | On | Off |
|---|---|---|
| `FLOWCORTEX_AUTH_EMAIL_CONFIRMATION_ENABLED` | a new registration must open the emailed link before it can sign in | accounts are confirmed on creation and registering signs in |
| `FLOWCORTEX_PHONE_VERIFICATION_ENABLED` | the profile offers phone verification by SMS code | the phone endpoints answer 503 and the profile hides the section |
| `FLOWCORTEX_MEDIA_UPLOAD_CONTENT_CHECK_ENABLED` | an upload whose first bytes do not match its type is refused | the bytes are not checked (the size caps still apply) |
| `FLOWCORTEX_WORKFLOW_CANCEL_ENABLED` | a member can stop a running run | stopping answers 503 and the button is hidden |
| `FLOWCORTEX_CREDITS_ENABLED` | runs are charged per step from a 1000-credit signup grant, and refunded on system failures | no balance check, charge or refund, and no balance in the header |

- **Email.** Every email the backend sends lands in Mailpit at
  <http://localhost:8025>; open the confirmation link from there. The link points
  at `FLOWCORTEX_APP_PUBLIC_URL` (default `http://localhost:5173`). A bare `bootRun`
  sends to `localhost:1025`, so start the sink first:
  `docker compose up -d mailpit`.
- **SMS.** No SMS is delivered. Each code goes to the `sms_outbox` table, which
  admins read in the console (`GET /api/v1/admin/sms-outbox`), or:

  ```bash
  docker compose exec postgres psql -U flowcortex flowcortex \
    -c "SELECT phone_e164, body, created_at FROM sms_outbox ORDER BY created_at DESC LIMIT 5"
  ```

**Resetting state.** `docker compose down -v` deletes every volume (database,
broker, store), so the next `up` seeds from scratch. You need it, or the narrower
fix named in each case, when:

- **Queue settings changed.** Queue arguments are fixed once a queue exists, so
  changing `FLOWCORTEX_MEDIA_CONSUMER_TIMEOUT` (or the delivery limit in code) on an
  existing broker makes the backend stay not-ready and log one ERROR naming the
  queue and the differing argument. Delete **both** job queues — `media.jobs` and
  `provider.jobs` — and restart the backend, or `down -v`:

  ```bash
  docker compose exec rabbitmq rabbitmqctl delete_queue media.jobs
  docker compose exec rabbitmq rabbitmqctl delete_queue provider.jobs
  docker compose up -d backend
  ```

  Deleting a queue drops the messages still waiting in it, so stop the workers first
  (`docker compose stop worker provider-worker`) if any run is in flight. The queues
  are recreated by the backend at startup with the new argument.
- **A store key was rotated.** `minio-init` creates a user for the new
  `BACKEND_S3_ACCESS_KEY` / `WORKER_S3_ACCESS_KEY` but leaves the old one in place.
  Remove it (`<old access key>` is the previous value):

  ```bash
  docker compose run --rm --no-deps --entrypoint sh minio-init -c \
    'MC_HOST_ROOT="http://$MINIO_ROOT_USER:$MINIO_ROOT_PASSWORD@minio:9000" mc admin user remove local <old access key>'
  ```
- **Runs from before the media engine.** A database from an earlier checkout can
  hold runs whose input is raw JSON text; they display as such. `down -v` clears
  them.

**Upgrading an existing deployment** (a host with its own `.env` and volumes, such
as the demo server). Compare the host's `.env` with `.env.example` before pulling a
new release: compose refuses to start **any** service while a required variable is
missing, and names it. For the Product Video release:

1. Add to `.env`:
   - **required:** `PROVIDER_RABBITMQ_USER` and `PROVIDER_RABBITMQ_PASS` — the provider
     worker's own broker user (letters, digits and `. _ ~ -` only; it goes in a URL);
   - optional, defaults shown in `.env.example`: `RUN_INPUT_RETENTION_DAYS`,
     `FLOWCORTEX_MEDIA_PROVIDER_MAX_RUNTIME`, `FLOWCORTEX_MEDIA_PROVIDER_QUEUE_TIMEOUT`.
2. Re-run both one-shot init services, so the broker user, the store policies and
   the run-input expiry exist (each is idempotent), then bring the rest up:

   ```bash
   docker compose up -d --force-recreate rabbitmq-init minio-init
   docker compose up -d
   ```

3. If `FLOWCORTEX_MEDIA_CONSUMER_TIMEOUT` changed, delete `media.jobs` and
   `provider.jobs` as in **Queue settings changed** above; otherwise the backend
   stays not-ready.

**Port 5173 already taken** (another local project, say). Publish the member app
on another port with an override file kept outside the checkout, and list the new
origin for uploads:

```bash
cat > ~/flow-cortex.ports.yaml <<'EOF'
services:
  frontend:
    ports: !override
      - "127.0.0.1:5190:8080"
EOF
# in .env:
#   COMPOSE_FILE=docker-compose.yml:/home/<you>/flow-cortex.ports.yaml   (an absolute path)
#   MINIO_API_CORS_ALLOW_ORIGIN=http://localhost:5190,http://127.0.0.1:5190
docker compose up -d
```

Without the new origin, the browser's upload is blocked by CORS.

This is for running/demoing the assembled stack. Day-to-day coding is still
faster with the compose infrastructure + `./gradlew bootRun` + `npm run dev`
(hot reload) as in §1 and §2.

## 4. Observability overlay (optional)

`compose.observability.yaml` adds traces (Tempo), logs (Loki), metrics
(Prometheus + a PostgreSQL exporter) and dashboards (Grafana), all fed by one
OpenTelemetry collector. No core service depends on it: without it the backend
and the worker still create trace ids and log them, they just export nothing.

```bash
# .env needs GF_SECURITY_ADMIN_PASSWORD and POSTGRES_EXPORTER_PASSWORD (see .env.example);
# without the first, compose refuses to start and names the variable.
docker compose -f docker-compose.yml -f compose.observability.yaml up -d --build
# or, to make every `docker compose` command include it, add this line to .env:
#   COMPOSE_FILE=docker-compose.yml:compose.observability.yaml
```

- Grafana: <http://127.0.0.1:3000>. Sign in as `admin` with `GF_SECURITY_ADMIN_PASSWORD`;
  anonymous access and sign-up are off. Dashboards: Media engine, Spring Boot 3.x
  Statistics and JVM (Micrometer) for the backend, RabbitMQ-Overview, PostgreSQL
  Database, and Silo object store. Where the vendored ones come from:
  `observability/grafana/dashboards/SOURCES.md`.
- The admin password is applied only when the `grafana_data` volume is first
  created; changing `GF_SECURITY_ADMIN_PASSWORD` later has no effect. To change it
  on an existing volume (the new password is read from stdin, not the command line):

  ```bash
  printf '%s' '<new password>' | docker compose -f docker-compose.yml -f compose.observability.yaml \
    exec -T grafana grafana cli admin reset-admin-password --password-from-stdin
  ```
- Prometheus: <http://127.0.0.1:9090> (Status → Targets shows backend, rabbitmq,
  minio and postgres-exporter). Its API answers cross-origin requests only from
  `http://127.0.0.1:9090`, and its OTLP and remote-write endpoints take writes
  without authentication, so the port stays on loopback.
- Only those two ports are published, on loopback. Tempo, Loki, the collector,
  the exporter, the backend's 8081 and the broker's 15692 stay inside the compose network.
- The overlay sets `MINIO_PROMETHEUS_AUTH_TYPE=public` so Prometheus can scrape the
  object store without a token. While it is on, the store's metrics also answer
  without credentials on <http://127.0.0.1:9000/minio/v2/metrics/cluster>. That is
  loopback-only here; a shared or deployed host needs a bearer token instead.

Turning the overlay on recreates `backend`, `worker` and `minio`, which take their
exporter and metrics settings from it, and they come back healthy.

**Trace a failed job.** In the admin SPA (<http://127.0.0.1:5174>), open
**Media jobs** and copy the failed row's full job id. In Grafana, open
**Explore → Loki** and run:

```
{service_name=~"flow-cortex|flow-worker"} | jobId="<job id>"
```

Both services' lines for that job appear: dispatch, pickup, the worker's
`input downloaded` / `job answered`, and the API's `Job result applied` with the
error code. Expand a line and follow its `trace_id` link to Tempo. The trace runs
from the run's HTTP request through the job publication, the worker's processing
(with its `ffprobe` child span) and the API's result handling. In a trace,
**Logs for this trace** leads back to the log lines. A step the reaper closed has
its own trace (the reaper's), and its log lines carry the same `jobId` and `runId`.

**Check it end to end.** After one "Check a video file" run (upload, run, download), pass
the `X-Trace-Id` response header of `POST /api/v1/workflows/{id}/runs` to:

```bash
scripts/check-telemetry.sh <trace id>
```

It checks for one trace across both services, for log lines from both, for every
metrics target `up`, and for zero `X-Amz-Signature` / `X-Amz-Credential` /
`X-Amz-Security-Token` (any case) in the trace and log stores. The scanned traces
must include that run's trace and the upload-grant and outputs-URL requests. It
exits non-zero on any miss, and gives up after `TOTAL_TIMEOUT_SECONDS` (600).

The scan covers only what the stores return for the last `SINCE_MINUTES` (default
60), so run it within the hour after the run. Older data, and data stamped with a
time outside that window, is not read: a clean result says nothing about it. It
needs `bash`, `python3`, GNU `date` and `docker` on the host, and `curl` in the
Grafana image (Tempo and Loki are reached from inside that container).

**Memory.** Measured with `docker stats` after a few runs: about 1.15 GiB for the
overlay. Roughly: Tempo 500 MiB, Grafana 410, Loki 110, Prometheus 80,
collector 60, exporter 13. Every overlay service has a `mem_limit`; together
they cap the overlay near 2.3 GiB.

**Turn it off.** Remove the `COMPOSE_FILE` line from `.env` first if you added
it, then recreate the core from the core file alone:

```bash
docker compose --env-file .env up -d --remove-orphans
```

That removes the overlay containers and recreates `backend`, `worker` and `minio`
with their core settings: exporters `none`, and minio metrics behind auth again.
Do not use `docker compose -f docker-compose.yml -f compose.observability.yaml down`,
which stops the core too. Removing only the overlay services is not enough either,
because it leaves the backend and the worker exporting to a collector that is gone.
Telemetry data stays in the `tempo_data`, `loki_data`, `prometheus_data` and
`grafana_data` volumes.

The exporter's read-only `flowcortex_monitor` role stays in PostgreSQL. To drop it
too (the next overlay start creates it again):

```bash
docker compose exec postgres psql -U flowcortex -d flowcortex -c 'DROP ROLE IF EXISTS flowcortex_monitor;'
```

## Troubleshooting

- **Gradle can't find a JDK 25 / toolchain error** → `JAVA_HOME` isn't pointing
  at a JDK 25. Export it (e.g. in `~/.bashrc`) and reopen the shell.
- **Port 5173 already in use** → `npm run dev` falls back to the next free port;
  read the URL it prints. For the compose stack, remap the port as in §3 and list
  the new origin in `MINIO_API_CORS_ALLOW_ORIGIN`.
- **An upload fails in the browser but `scripts/smoke-probe.sh` passes** → the
  page's origin is missing from `MINIO_API_CORS_ALLOW_ORIGIN`.
- **The backend never turns healthy** → `docker compose logs backend`. The ERROR
  "The media broker topology could not be declared" means a changed queue setting
  on an existing broker (see "Resetting state" in §3).
