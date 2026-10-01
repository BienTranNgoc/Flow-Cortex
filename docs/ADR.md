# Architecture Decision Log — Flow-Cortex

Lightweight ADRs: one short entry per decision that a future reader (including
future-you) would otherwise have to reverse-engineer. Newest at the bottom.
Format per entry: **Context → Decision → Consequences**.

---

## ADR-0001 — Scale the reference conventions down for a solo project

- **Context:** Flow-Cortex adopts the DigiShield reference repo's conventions,
  but DigiShield is a multi-team enterprise system (9 modules, Terraform/EKS,
  three static-analysis gates, full BRD/FRD/ADR doc set). Copying all of that
  into a one-person project is over-engineering.
- **Decision:** Adopt only "Tầng 0–1" now:
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
  (AWS-only), **Kafka** (partition head-of-line blocking, no per-message ack,
  a 5-minute poll interval — the right tool for the later run-event stream,
  the wrong one for tasks), **BullMQ** (no Java client), **a DB-table queue**
  (couples the worker to the schema; a broker was chosen for its learning
  value), and the **delayed-message plugin** for backoff (archived).
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
  - **Lessons from Xood**, adopted: the inline/remote split (HTTP-provider
    steps stay in the API, where the keys live), a backend reaper for stale
    steps, a keyless worker. Rejected: a schema-less message body, a default
    capability fallback, an HMAC callback (results come back over the broker,
    so there is no second auth mechanism), and an unguarded URL fetch.
  - Kafka remains the candidate for a later run-event stream; run events stay
    in-process until then.

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
