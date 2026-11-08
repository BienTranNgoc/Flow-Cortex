# Architecture Decision Log — Flow-Cortex

Lightweight ADRs: one short entry per decision that a future reader (including
future-you) would otherwise have to reverse-engineer. Newest at the bottom.
Format per entry: **Context → Decision → Consequences**.

---

## ADR-0001 — Adopt the reference conventions in tiers

- **Status:** the tiering below still holds; two parts are superseded. Product
  scope is full feature parity with Xood (ADR-0007, "Xood is the functional
  target", 2026-11-06), not a scaled-down personal project. The single Gradle
  module and the deferred `buildSrc` / Spring Modulith / contract-first / CI
  tiers have landed (ADR-0003, ADR-0005, `.github/`).
- **Context:** Flow-Cortex adopts the DigiShield reference repo's conventions,
  but DigiShield is a multi-team enterprise system (9 modules, Terraform/EKS,
  three static-analysis gates, full BRD/FRD/ADR doc set). Copying all of that
  on day one, before any product code exists, is over-engineering.
- **Decision:** Adopt only "Tầng 0–1" at the start:
  - Git discipline (Conventional Commits + protected `main`) — copied verbatim.
  - `.editorconfig`, ESLint/Prettier, layered package structure.
  - A single Gradle module (not a multi-module build) until a second bounded
    context actually exists.
  - Coverage (JaCoCo / Vitest) **measured, not gated** — the gate is a later,
    separate decision once real numbers exist.
  - Deferred: convention plugins (`buildSrc`), Spring Modulith, Checkstyle/
    SpotBugs gates, contract-first codegen, CI/CD, Terraform.
- **Consequences:** Fast to start and easy to run. When the project grows,
  promote to a multi-module build (extract `buildSrc` convention plugins), turn
  the coverage measurement into a ratchet gate, and add the deferred tầng in
  order. The reference repo remains the template for each step.

---

## ADR-0002 — Java 25 + Spring Boot 4.1, Gradle Kotlin DSL

- **Context:** Match the reference stack and the JDK already installed on the
  dev machine.
- **Decision:** Java 25 toolchain, Spring Boot 4.1.0, Gradle 9.6.1 (Kotlin DSL),
  `io.spring.dependency-management` for BOM-managed versions.
- **Consequences:** Spring Boot 4 reorganised some packages — e.g. the MVC test
  slice (`@WebMvcTest`) moved to `org.springframework.boot.webmvc.test.autoconfigure`
  and now needs `spring-boot-starter-webmvc-test` (it is no longer part of
  `spring-boot-starter-test`). Keep this in mind when copying Boot 2/3 examples.

---

## ADR-0003 — Modular monolith via Spring Modulith

- **Context:** The `workflow` module is the second bounded context, exactly
  the trigger ADR-0001 named for promoting off a single Gradle module. A
  layering convention enforced only by code review (as in ADR-0001's "Tầng
  0–1") does not scale past one contributor — a cross-module import that
  shouldn't exist can land silently.
- **Decision:** A Gradle multi-module build (`:boot:app` composition root +
  `:modules:user`/`:modules:workflow` + `:shared:web`/`:shared:security`) with
  Spring Modulith (BOM 2.1): every module is an `@ApplicationModule` with an
  explicit, exhaustive `allowedDependencies` list, and `:boot:app`'s
  `ModularityTests.verify()` fails the build on any undeclared cross-module
  import. Chosen over splitting into separate deployable services for the
  same "gradual extraction" rationale as the DigiShield reference repo's own
  ADR-001 — a real network/deploy boundary isn't warranted yet for a
  two-module solo project, but the code is laid out to extract cleanly later.
- **Consequences:** A direct sub-package of `com.flowcortex` (e.g.
  `com.flowcortex.api`) becomes its own Modulith module the moment anything is
  added to it — composition-root-only code lives under `com.flowcortex.app.*`
  instead. Cross-module reuse must go through a module's `@NamedInterface`
  `api` package, never its `domain`/`infrastructure` internals. Extracting a
  module into its own deployable later is a smaller, lower-risk move than it
  would have been from one flat package tree.

---

## ADR-0004 — Flyway migrations, `ddl-auto: validate`

- **Context:** Both business modules need persistence. Letting Hibernate
  auto-generate/alter the schema (`ddl-auto: update`/`create`) is convenient
  but non-reproducible across environments and silently drifts as entities
  change; a versioned migration tool makes schema changes explicit and
  reviewable instead.
- **Decision:** Flyway owns the schema — date-versioned migrations
  (`V<yyyy.MM.dd>.NNN__*.sql`) under `classpath:db/migration` — and Hibernate
  is `ddl-auto: validate` only: it checks entity mappings against what Flyway
  already applied and never mutates the schema itself. One dialect
  everywhere (Postgres) makes `validate` hold identically in every profile —
  Testcontainers Postgres for every test, the compose `postgres:17` service
  for dev, no H2 anywhere (H2 lacks `jsonb`/`timestamptz`, and enabling it
  would mean disabling Flyway for that profile, falsifying the very
  guarantee this ADR is about). On Spring Boot 4.1, Flyway needs three
  dependencies, not the commonly-documented one: `flyway-core` +
  `flyway-database-postgresql` (without it Flyway silently no-ops on an empty
  database) + `spring-boot-flyway` (Boot 4.1 split `FlywayAutoConfiguration`
  into its own starter — without it Flyway never runs at all).
- **Consequences:** Every schema change is an explicit, committed migration
  file reviewable in a PR — no environment can silently drift via auto-DDL,
  and a mapping/migration mismatch fails startup loudly instead of
  corrupting data at runtime. As of this change every table is greenfield
  (task group 3 has zero rows anywhere), so every migration to date is a
  plain `CREATE`; the expand → migrate → contract dance only starts to
  matter the day a populated table needs to change shape.

---

## ADR-0005 — Contract-first OpenAPI + generated frontend client

- **Context:** The backend and frontend are two separate codebases (no
  shared TypeScript monorepo), so nothing stops a controller's response
  shape from silently drifting away from what the frontend was hand-typed
  to expect — the exact failure mode a contract needs to catch at CI time,
  not at runtime in front of a user.
- **Decision:** `springdoc-openapi-starter-webmvc-ui:3.1.1` (pinned exactly —
  the version that resolved springdoc's early-Boot-4/Jackson-3 introspection
  friction, issues #3169/#3268) generates the live document at
  `/v3/api-docs`; a backend test writes it to the committed `docs/openapi.yaml`,
  the single source of truth. `orval` generates a typed `fetch`-based client
  from that file into `packages/api-client/src/generated/` (`npm run gen:api`;
  it lived in `frontend/src/api/generated/` until ADR-0008 shared it between
  both SPAs).
  Both `backend-ci` and `frontend-ci` regenerate their artifact and run
  `git diff --exit-code` against it, so an uncommitted regeneration on
  either side — a controller change without a refreshed snapshot, or a
  snapshot change without a refreshed client — fails CI.
- **Consequences:** The frontend's types can never silently diverge from
  what the backend actually serves; a mismatch is a CI failure, not a
  runtime surprise discovered later. Every controller change now carries a
  small extra step before commit (`./gradlew test` regenerates the
  snapshot; `npm run gen:api` at the repo root regenerates the client). The snapshot must be
  byte-deterministic across regenerations or the diff check itself becomes
  flaky — enforced by pinning a fixed relative `servers: - url: /` rather
  than letting springdoc bake in the test's random port.

---

## ADR-0006 — RFC 9457 `ProblemDetail` + Jakarta Bean Validation as the uniform error contract

- **Context:** An inconsistent per-endpoint error shape is a gap the
  DigiShield adoption analysis found in that very reference repo. Flow-Cortex
  wants one uniform, machine-parseable error contract from the first
  controller onward, not retrofitted after several modules already disagree.
- **Decision:** A global `@RestControllerAdvice` in `:shared:web` — extending
  `ResponseEntityExceptionHandler` so Spring MVC's own 405/415/406 handling
  is preserved rather than collapsed into generic 500s — maps every handled
  exception (`NotFoundException`, `ConflictException`, authentication/
  authorization failures, and Jakarta Bean Validation failures via
  `MethodArgumentNotValidException`/`ConstraintViolationException`) to
  `application/problem+json` (RFC 9457) with `type`/`title`/`status`/`detail`
  always set, plus a field-keyed `errors` member on validation failures.
  Every inbound request `record` carries `@Valid` + Jakarta constraints.
- **Consequences:** Every module's controllers get 400/401/403/404/409/500
  handling for free — a new module only has to throw the shared exception
  types, which is why `shared :: web` is in every module's
  `allowedDependencies` (ADR-0003). Clients, including the generated
  frontend client (ADR-0005), get one predictable error shape to branch on
  instead of a different one per endpoint.

---

## ADR-0007 — Media engine: RabbitMQ + a Python worker + an S3-compatible store

- **Context:** The pipeline's real work (download, demucs, FFmpeg) is slow,
  CPU-bound and Python-native, so it cannot run inside an API request or an
  API thread. The engine needs a durable hand-off to a separate process, a
  bound on every state a step can sit in, results applied exactly once despite
  at-least-once delivery, and a place for large files that never streams them
  through the API. This change ships that path with one capability, `probe`
  (ffprobe), on the seeded "Check a video file" workflow.
- **Decision — messaging:** RabbitMQ `4.3.6-management` (4.2's community
  support ended 2026-07-31). The API declares the topology at startup: exchange
  `media` (direct, alternate exchange `media.unrouted`), quorum queues
  `media.jobs`, `media.results`, `media.progress`, dead-lettering through
  `media.dlx` to one DLQ each. Outbound jobs go through Spring Modulith's event
  publication registry as the transactional outbox (a job exists iff the claim
  committed), with a resubmitter for failed publications; inbound results and
  progress are transactional `@RabbitListener`s. Alternatives: **SQS**
  (AWS-only), **Kafka** (partition head-of-line blocking, no per-message ack, a
  5-minute poll interval — the right tool for the later run-event stream
  (superseded by ADR-0009), the wrong one for tasks), **BullMQ** (no Java client),
  **a DB-table queue** (couples the worker to the schema; a broker was chosen for
  its learning value), and the **delayed-message plugin** for backoff (archived).
- **Decision — worker:** Python 3.12, one blocking `pika` connection owned by
  the main thread, prefetch 1, the handler on one worker thread that reaches
  pika only through `add_callback_threadsafe`. Message shapes are JSON Schemas
  in `contracts/`, validated by Bean Validation in Java and by generated
  pydantic models in Python. The worker is keyless and has no internet egress:
  its only compose network is `internal`.
- **Decision — input:** the member uploads the file through a stateless
  presigned PUT into a separate uploads bucket; the API derives the key from
  the caller's identity and HEADs the object when the run starts. Two buckets
  (`flowcortex-uploads`, `flowcortex-media`) instead of prefixes, because R2
  tokens cannot scope by prefix. URL ingest left this change: an IP-pinned HTTP
  guard cannot protect yt-dlp, which the reference system's worker runs on
  `params.url` unguarded (Xood `xood-media-worker/tasks/video_fetch.py:140-145`;
  its `_is_private_host` exists only at `src/swap_lib.py:496`). The download
  capability must therefore use egress isolation (a forward proxy or a
  dedicated network).
- **Decision — store:** `minio/*` has been 404 on Docker Hub since 2026-09-11,
  and `quay.io/minio/*` is frozen at 2025-09-07 with CVE-2026-41145, so the
  store is Silo, pinned by digest:
  `pgsty/silo:RELEASE.2026-09-16T00-00-00Z@sha256:635197cb9f36d01bee221d34d1c7d7960f6a95c48b0b6c01d99cd13bdae51a46`
  (amd64 + arm64; `mc` is bundled, so the one-shot init uses the same image).
  Garage is the fallback if Silo stops releasing. Production runs the same Silo
  on the Oracle A1 VM's block volume. Config is provider-neutral (`S3_*`), so
  another S3-compatible store is a configuration change.
- **Decision — timeouts and retries:** Temporal's three bounds: a queue
  deadline set at claim (`queue_timeout`), a lease from **pickup** extended by
  each heartbeat (`lease_expired`), and a run deadline per delivery
  (`run_timeout`), enforced by a backend reaper. `attempt` is an API-owned
  fencing token that every result CAS checks; it stays 1 here, and transient
  failures are retried by the broker only. API-owned retry (`attempt + 1` with
  backoff, Temporal's model) is the follow-up for the first real capability,
  demucs.
- **Measured during apply (not assumed):**
  - **Delivery limit on 4.3.6 quorum queues:** with `x-delivery-limit=3` a
    message is delivered 4 times, then dead-lettered with reason
    `delivery_limit`. A reject and a channel close with the message unacked are
    counted; a nack and a consumer-timeout return are not (they raise the new
    `x-acquired-count` instead). So the worker's transient path backs off, then
    rejects, never nacks.
  - **Presigned PUT on Silo:** `content-length` is signed, and a wrong byte
    count or Content-Type is refused with 403. Expiry is checked when the
    request starts: a PUT that starts inside a 5 s TTL and ends after it gets
    200.
  - **CORS:** Silo's CORS is global and env-only; a comma-separated
    `MINIO_API_CORS_ALLOW_ORIGIN` works (each listed origin is echoed, any
    other gets no allow header).
- **Bugs worked around (Spring Modulith 2.1.0 and 2.1.1; worth reporting
  upstream):** AMQP externalization drops the configured `.headers(...)`, so
  a `RabbitTemplate` before-publish post-processor sets the job's message and
  correlation ids. The insight starter CGLIB-proxies exposed
  `GenericFilterBean`s, and startup dies with an NPE; servlet filters are
  marked as infrastructure beans, which it skips.
- **Consequences:**
  - `/readyz` covers the topology declaration at startup and then ignores the
    broker, by design: the outbox and the resubmitter cover a broker outage,
    while a broker in readiness would turn a broker blip into a full outage.
    A broker-down alert belongs on the overlay's metrics.
  - A result whose routing key matches no queue is parked on `media.unrouted`
    instead of being dropped, and the reaper closes its step.
  - Only the first step of a workflow may be remote: its input is the
    uploaded file, which only the first step receives.
  - The backend and the worker share the broker's default account, acceptable
    only because every broker port is loopback-bound. A dedicated
    least-privilege worker user arrives with the `deploy-oracle-a1` change.
  - Queue arguments are immutable on the broker: changing the consumer timeout
    or the delivery limit on an existing broker leaves the backend not ready
    until the queue is deleted (LOCAL_SETUP.md §3).
  - **Divergence from DigiShield:** it has no broker in its compose, runs its
    worker as a Spring profile of the same JAR and keeps events in-process
    (Modulith only). Here the cross-process hop is the broker and the worker
    is a separate Python service, because the capabilities are Python tools.
  - **Xood is the functional target:** Nodi FlowCortex is built toward feature
    parity with Xood (`xood-backend`, `xood-frontend`, `xood-admin`,
    `xood-media-worker`, `xood-clay-render`) at their `main` branch. Every Xood
    capability, including every media-worker task, is in scope and is ported
    domain by domain. Xood
    sets *what* the product does; this ADR and ADR-0001…0008 set *how* it is
    built. Adopted as-is: the inline/remote split (HTTP-provider steps stay in
    the API, where the keys live), a backend reaper for stale steps, a keyless
    worker. Ported with a safer mechanism, same behaviour: message bodies get a
    JSON Schema in `contracts/` instead of none; an unknown capability is
    refused instead of falling back to a default; results return over the
    broker instead of an HMAC callback; URL fetch (`video_fetch`) runs behind
    egress isolation instead of unguarded.
  - ADR-0009 supersedes the earlier plan that named Kafka for a later run-event
    stream: run events go to the browser over SSE, fanned out between backend
    instances through a RabbitMQ exchange.

---

## ADR-0008 — npm workspaces + a separate admin SPA

- **Context:** The engine needs an operator view (every user's runs, the media
  jobs with their delivery counts and timeout markers, user management) that
  members must never load. The member SPA and that console would otherwise
  copy the generated API client and the error-boundary code.
- **Decision:** A separate `admin/` SPA (Vite, React, the same tooling as
  `frontend/`), its own nginx image (listening on 8080 in the container,
  published on host port 5174), with one permission table
  (`can(role, action)`) that both the route guard and the sidebar read. The
  repo root is an npm workspace (`frontend`, `admin`, `packages/*`) with one
  lockfile; `packages/api-client` holds the orval-generated client
  (ADR-0005) and `packages/ui-kit` the route error boundary and the client
  error reporter. Both are source packages consumed by Vite and TypeScript
  directly, with no build step. The alternative, `/admin/*` routes inside the
  member SPA, was rejected for learning value, and because it ships the admin
  code to every member.
- **Consequences:** Users moved from the member app to the console. The admin
  API (`/api/v1/admin/**`) has its own security chain and kill switch. Both
  SPA images build from the repo root, so a root `.dockerignore` keeps
  `backend/`, `worker/` and every `node_modules` out of the context. Nothing
  imports a sibling app by relative path (AGENTS.md §10); the packages are
  the sanctioned shared code. CI installs once at the root and runs each check
  across the workspaces.

---

## ADR-0009 — Realtime transport: Server-Sent Events

- **Context:** A run advances on the worker and in the API, and the run page
  learned about it by polling `GET /api/v1/runs/{id}` on a timer. The product
  needs push for run progress now and for chat later, always from the server to
  the browser. Several backend instances may run, and a browser can hold the
  stream through nginx in the frontend container and, in the AWS overlay,
  through Caddy as well.
- **Decision — transport:** Server-Sent Events over `GET /api/v1/realtime/stream`
  with repeated `topic` query values (`user`, `run:<uuid>`; a later module adds
  a topic by registering a `TopicAuthorizer`). SSE is one-way, rides plain
  HTTP, and the browser resumes it with `Last-Event-ID`. Alternatives: a
  **WebSocket** gateway (Xood runs three) is two-way and heavier than a one-way
  push needs; two directions are a later ADR. **LISTEN/NOTIFY** or **Redis** for
  fan-out were not added. **Kafka**, which ADR-0007 named for this stream, is
  not needed: the events are signals that a refetch repairs, not a log.
- **Decision — auth:** the client uses `fetch` with `Authorization: Bearer` and
  parses the stream in `packages/api-client`; native `EventSource` is not used.
  The access token lives in storage and the only cookie is path-scoped to
  `/api/v1/auth`, so `EventSource` cannot authenticate here without a new ticket
  endpoint or cookie. The endpoint answers 401 before any stream byte for a bad
  token or a deleted user, 400 for a topic no authorizer claims, 404 for a
  topic the caller may not read (a foreign run looks like a missing one), and
  429 past the per-user cap. A stream ends cleanly at the expiry of the token
  that opened it, and the client reconnects with its refreshed token.
- **Decision — events:** `run.updated` carries only `{runId}`; the client
  refetches the run, so the owner-scoped read model stays the one source of
  truth. Each frame has `id: <instanceId>:<seq>`, `event: <type>` and a JSON
  `data`. A heartbeat comment (`: ping`) goes out every 15 s.
- **Decision — fan-out and replay:** publishing sends the event to the durable
  fanout exchange `realtime.events`; each instance, the publisher included,
  consumes it from its own exclusive, auto-delete queue (`x-max-length` 10,000)
  and delivers to its local streams, so there is one delivery path. Messages are
  non-persistent. Each instance keeps an in-memory replay ring (1,000 events or
  120 s). A `Last-Event-ID` naming this instance and a seq inside the ring
  replays; anything else, including another instance's id, gets one `resync`
  event and the client refetches. A consumer that recovers from a broker failure
  sends `resync` to every open stream. Limits: 8 topics per stream and 5 streams
  per user, per instance; client backoff 1-30 s with full jitter. All are
  starting values, measured only for the heartbeat.
- **Decision — threads:** the module never blocks a caller. `publish` hands the
  event to its own single-thread executor with a bounded queue, and a full queue
  drops the event with a WARN; the hub owns its scheduler for heartbeats, expiry
  and writes. Neither is exposed as a Spring `TaskScheduler` or `Executor` bean,
  because Boot's shared pools back off when one exists. A bounded broker connect
  timeout keeps a hung broker from holding a committing thread. On context close
  the hub completes every stream so graceful shutdown does not wait on them.
- **Decision — proxies:** nginx gets a `/api/v1/realtime/` location with
  `proxy_buffering off`, a `proxy_read_timeout` of 150 s (ten heartbeats) and no
  `add_header`, since a location that declares one drops the server-level
  security headers; the backend also sends `X-Accel-Buffering: no`. Caddy routes
  that path to `frontend:8080` without `encode`, whose default matcher includes
  `text/*`, while every other path keeps compression. No polling fallback is
  kept: two live paths for one thing is the debt this decision removes.
- **Consequences:**
  - Supersedes the ADR-0007 consequence that left run events to a later Kafka
    stream, and that Decision's naming of Kafka for it.
  - The client owns the SSE parser and the reconnect loop, locked by tests.
  - A signal lost while connected is repaired by `resync` on consumer recovery
    and by the client's refetch on every reconnect and window focus; a publish
    dropped on a full queue is repaired only by the next event, reconnect or
    focus.
  - Replay is per instance, so a reconnect that lands on another instance
    resyncs; correctness costs one refetch.
  - A browser holds few HTTP/1.1 connections per origin; one stream per open
    run page and the per-user cap keep a normal session inside it.
  - Out of scope: two-way channels (a later ADR) and a durable event log.
  - The realtime module depends on `user :: api` and never on `workflow`; run
    ownership reaches it through the `TopicAuthorizer` SPI.

---

## ADR-0010 — Egress isolation: one forward proxy with a domain allowlist

- **Context:** ADR-0007 left URL ingest out because an IP-pinned HTTP guard
  cannot protect yt-dlp, and required the download capability to run behind
  egress isolation (`docs/ADR.md:191-196`, `:263-264`); this entry fulfils
  that. Every outbound call the stack makes today:
  - the backend: the Google OAuth token exchange and key fetch (both over
    `HttpURLConnection`), the help-center Gemini client (`HttpURLConnection`),
    the dub-step Gemini client (JDK `HttpClient`), and SMTP, which in the AWS
    overlay goes to an external relay;
  - the provider workers: `urllib` in the OpenAI, Seedance, Veo and Wan
    adapters, the Edge TTS websocket, and result downloads, which the adapters
    already restrict in code to a few vendor domains;
  - the media worker makes none.

  Before this change `backend` and both provider workers sat on the default
  compose network, which has a route out, so a bug in any of them could reach
  any host, including the cloud metadata address.
- **Decision:** a forward proxy plus a domain allowlist is the only way out.
  - **Proxy:** `egress-proxy` runs Smokescreen v0.1.0 with a default-deny
    policy (`enforce`) and a hostname allowlist. It sits on `internal` and on
    `egress`, the only network with a route out, and publishes no port.
    `backend`, `provider-worker` and `provider-video-worker` are attached to
    `internal` only, so a connection that skips the proxy fails.
  - **Private ranges:** after DNS resolution Smokescreen refuses private,
    loopback, link-local and carrier-grade-NAT addresses (and IPv6 forms that
    embed IPv4), even for an allowlisted name. Compose service names and the
    metadata address are therefore unreachable through it. No range exception
    is configured.
  - **Engine choice:** Smokescreen was built for SSRF defence, so the private
    range block is its default behaviour, and SMTP over CONNECT needs no port
    exception. It publishes no image for v0.1.0, so `infra/egress-proxy/`
    builds one: the release tarball is fetched per architecture and checked
    against pinned SHA-256 values, and the runtime stage is
    `gcr.io/distroless/static:nonroot` pinned by digest. `egress-proxy` also
    starts with `--disable-acl-policy-action=open` and loads its ACL through
    `--egress-acl-file`, because Smokescreen v0.1.0 enforces the disabled
    policy only on an ACL given by that flag (an `acl_file` in the config
    file alone still starts on an open default). The committed ACL therefore
    cannot be widened to an open policy by editing its default.
  - **Open tier for URL downloads:** a second service, `egress-fetch`, runs
    the same image with the policy `open` (any public host) behind the same
    private-range block, rate limits (100 concurrent requests, 50 requests per
    second, burst 150, 50 concurrent CONNECT tunnels) and its own network
    `fetch`, which is `internal` and has no member but `egress-fetch`. Only
    URL-fetch code joins `fetch`; `backend` and the provider workers stay on
    `internal` and cannot reach it. The first URL-fetch service joins `fetch`
    in its own change, so the tier is built and checked but idle until then.
  - **Topology:** services that publish a host port keep `default` (a
    published port on a container whose only networks are internal did not
    work when tried), so `postgres`, `mailpit`, `frontend` and `admin` are on
    `default` and `internal`. `127.0.0.1:8080` is kept by a one-line relay,
    `api-publish` (`alpine/socat`, pinned by digest), on `default` and
    `internal`, so every caller of that address is unchanged.
  - **Clients:** the backend JVM gets `JAVA_TOOL_OPTIONS` with the proxy and a
    `http.nonProxyHosts` list for the internal services; the provider workers
    get `HTTP_PROXY`, `HTTPS_PROXY` and `WSS_PROXY` (the Edge TTS websocket
    reads `WSS_PROXY`) and a `NO_PROXY` list for `minio`, `rabbitmq` and
    `otel-collector`. In the AWS overlay SMTP goes through the proxy with the
    Jakarta Mail `mail.smtp.proxy.host` and `.port` properties.
  - **Guard:** `scripts/check-egress-isolation.sh` reads
    `docker compose config` for the base file and each overlay and fails when
    an application service regains a non-internal network, when anything but
    the two proxies is on `egress`, when a proxy publishes a port, or when
    `fetch` is not internal or has another member. CI runs it.
  - **Allowlist per environment:** the committed list is
    `infra/egress-proxy/acl.yaml`, seeded with the hosts the code calls:
    `*.googleapis.com`, `api.openai.com`, `api.replicate.com`,
    `replicate.delivery`, `*.replicate.delivery`, `*.bytepluses.com`,
    `*.volces.com`, `*.aliyuncs.com` and `speech.platform.bing.com`. Compose
    mounts the file named by `EGRESS_ACL_FILE` (default: the committed one);
    `compose.aws.yaml` requires the variable, so the AWS host keeps its own
    copy outside the checkout, with its SMTP relay host added.
  - **Add a host without a code deploy:** edit the file `EGRESS_ACL_FILE`
    names, then run `docker compose up -d --force-recreate egress-proxy`. No
    application image is rebuilt and neither the backend nor a worker is
    restarted. A live reload was not measured, so recreate is the documented
    path. Policy `report` (allow and log unlisted hosts) exists for finding
    hosts before enforcing.
  - **URL downloads and yt-dlp:** they use `egress-fetch` and nothing else,
    because yt-dlp resolves and follows hosts a vendor allowlist cannot list,
    and an IP-pinned HTTP guard cannot protect it (ADR-0007). The risk that
    remains is that a URL-fetch bug can send traffic to any public host; the
    private-range block, the rate limits, the internal-only `fetch` network
    and the guard script bound it. **No residential upstream proxy exists at
    launch**: nothing has measured that a platform blocks the cloud address,
    and an upstream proxy adds cost and a third party that sees every URL. A
    measured platform block on the deployment's address reopens that decision;
    the hook is an upstream-proxy setting on `egress-fetch`, so no application
    code changes. Adding it is a change of its own. The open tier itself is
    reopened for review if the limits prove wrong for real traffic: the values
    are Smokescreen's README example, not tuned to Nodi.
- **Alternatives:**
  - **Squid:** rejected. Its default configuration uses the private ranges as
    source ACLs, so a destination block has to be written by hand and kept
    correct, and it denies CONNECT to ports other than 443 by default, so SMTP
    through the proxy needs an exception. Smokescreen gives both for free.
  - **No proxy: a host firewall, or `enable_ip_masquerade=false` on
    `default`:** rejected. Neither is portable to the compose smoke job, and
    the second fails open when the host does its own NAT.
  - **A single enforce-only proxy with no open tier:** rejected, because URL
    downloads cannot be allowlisted.
  - **Dropping the `backend` publish and sending callers through `frontend`:**
    rejected: `/readyz` is not under `/api/`, so the nginx proxy and several
    scripts and docs would change.
- **Consequences:**
  - **High availability:** a single `egress-proxy` container with
    `restart: unless-stopped`. It is a single point of failure for third-party
    calls; replicas are the follow-up. The image has no shell, so there is no
    container healthcheck and dependents wait with `service_started`.
  - **Fail closed:** while the proxy is down, or when a client ignores the
    proxy settings, the call fails instead of connecting directly: the
    network is internal. The Python adapters raise `TransientError` on
    `URLError`, and Google sign-in answers its existing token-endpoint
    failure.
  - **An allowlist gap breaks one provider** while the rest work.
    Operator-supplied base URLs (`SCRIPT_PROVIDER_BASE_URL`,
    `FLOWCORTEX_PROVIDER_BASE_URL`, `SEEDANCE_BASE_URL`, `GEMINI_BASE_URL`)
    point at hosts the seed cannot know, so the operator lists the exact host.
    A `*.` glob does not match the bare domain: `SEEDANCE_BASE_URL` may be the
    bare `bytepluses.com` (`worker/src/flowcortex_worker/provider/seedance_adapter.py:226`
    accepts it), which then needs its own entry, and a `GEMINI_BASE_URL` host
    outside `*.googleapis.com` needs one too.
  - **Client rule for later changes:** the JDK `HttpClient` and
    `HttpURLConnection` honour the JVM proxy properties, so a client built on
    them needs no extra setting. Any other client library is checked against
    the proxy when it is introduced, or configured with the proxy explicitly;
    Reactor Netty does not honour the properties by default. A client that
    ignores them fails closed on the internal network, which is a defect to
    fix, not a bypass.
  - **Services with a published port keep a route out** (`default`), so they
    must not run URL-fetching or other outbound-call code; the guard script
    lists them by name, and a new service fails the check until it is
    internal-only or consciously added to that list.
  - **Large downloads:** result downloads of up to 100 MB pass through the
    proxy; a 32 MB ranged download through `egress-proxy` completed in 2.7 s,
    and the tunnel idle timeout on slower or larger transfers was not measured.
  - **Client address:** direct calls to `127.0.0.1:8080` now reach `backend`
    from the relay container. Every host caller already shared one address,
    so per-IP limits behave as before; browser calls through `frontend` keep
    the forwarded client address.
  - **Operator docs:** `LOCAL_SETUP.md` describes which services have no
    internet route and how to add a host.
