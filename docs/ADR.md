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
  from that file into `frontend/src/api/generated/` (`npm run gen:api`).
  Both `backend-ci` and `frontend-ci` regenerate their artifact and run
  `git diff --exit-code` against it, so an uncommitted regeneration on
  either side — a controller change without a refreshed snapshot, or a
  snapshot change without a refreshed client — fails CI.
- **Consequences:** The frontend's types can never silently diverge from
  what the backend actually serves; a mismatch is a CI failure, not a
  runtime surprise discovered later. Every controller change now carries a
  small extra step before commit (`./gradlew test` regenerates the
  snapshot; `npm run gen:api` regenerates the client). The snapshot must be
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
