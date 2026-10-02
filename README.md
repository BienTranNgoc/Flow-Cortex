# Flow-Cortex

A personal project scaffolded on the same engineering conventions as the
DigiShield reference repo, deliberately scaled down to a solo project (Tầng 0–1:
foundations + architecture, no cloud infra yet).

- **Repo:** https://github.com/BienTranNgoc/Flow-Cortex
- **Stack:** Spring Boot 4.1 / Java 25 (API) · Python 3.12 media worker over
  RabbitMQ 4.3 and an S3-compatible store (Silo) · React + TypeScript + Vite
  (member app and admin console)

## Layout

```
Flow-Cortex/
├─ backend/      Gradle (Kotlin DSL, multi-module) · Spring Boot 4.1 · Java 25 · Dockerfile
│   ├─ boot/app/       composition root — executable app, ModularityTests, Flyway migrations
│   ├─ modules/{user,workflow,media}/   business modules (api → application → domain → infrastructure → web);
│   │                  media = broker topology, job outbox, result listeners, presigned URLs
│   ├─ shared/{web,security}/     cross-module libraries (ProblemDetail advice, JWT filter chain)
│   └─ buildSrc/       convention plugins (Checkstyle, JaCoCo, BOMs — ADR-0003)
├─ worker/       Python media worker (uv · ruff · mypy · pytest) — consumes media.jobs, runs ffprobe (ADR-0007)
├─ contracts/    JSON Schemas of the broker messages, the source of truth for Java and Python
├─ frontend/     member app — Vite · React · TypeScript · ESLint · Prettier · Vitest · Dockerfile + nginx.conf
├─ admin/        admin console (ADMIN only: runs, media jobs, users) — same stack, own image, port 5174 (ADR-0008)
├─ packages/     npm workspaces shared by both SPAs
│   ├─ api-client/   orval-generated OpenAPI client + unwrap/ApiError/token store (ADR-0005, generated part not hand-edited)
│   └─ ui-kit/       shared React pieces (route error boundary, client error reporter)
├─ infra/        minio/ — the store's one-shot init: buckets, uploads lifecycle, per-service users and policies
├─ observability/   optional overlay config: collector, Tempo, Loki, Prometheus, Grafana dashboards
├─ docker-compose.yml          one-command stack: postgres, rabbitmq, store, backend, worker, both SPAs
├─ compose.observability.yaml  the optional traces/logs/metrics overlay
├─ docs/         ADR.md — the decision log · ERD.md — the schema · openapi.yaml — committed contract-first snapshot (ADR-0005)
├─ openspec/     spec-driven change workspace (local, gitignored)
├─ scripts/      hook setup, commit-subject validation, smoke-probe.sh (compose smoke), check-telemetry.sh
├─ .githooks/    commit-msg (Conventional Commits) · pre-push (protect main)
├─ .github/      CI: `ci` runs the area workflows (backend, frontend, worker, contracts, compose-smoke) behind one `ci-gate` check · security · CODEOWNERS
└─ .editorconfig
```

## First-time setup

```bash
# 1. Enable the git hooks (per clone — hooks are tracked but not auto-active)
bash scripts/setup-hooks.sh

# 2. Backend — needs a JDK 25 on JAVA_HOME (no auto-provision configured)
cd backend && ./gradlew build      # compiles + runs tests

# 3. Member app and admin console (npm workspaces — install once at the root)
npm install
npm run dev -w frontend    # member app
npm run dev -w admin       # admin console
```

Prefer one command? `cp .env.example .env`, replace the `change-me` values, then
`docker compose up --build` brings the whole stack up — see
[LOCAL_SETUP.md](LOCAL_SETUP.md). Open the member app at <http://localhost:5173>;
admins use the console at <http://localhost:5174> (Users lives there). The API is
on `:8080`.

See [LOCAL_SETUP.md](LOCAL_SETUP.md) for the full run instructions and
[docs/ADR.md](docs/ADR.md) for why the project is set up this way.

## AI assistants & editor config

The working agreement for AI coding assistants (and a contributor cheat-sheet)
lives in **[AGENTS.md](AGENTS.md)** — one tool-neutral source of truth. Claude
Code, Cursor, GitHub Copilot and Gemini each read it via a thin pointer
(`CLAUDE.md`, `.cursor/rules/`, `.github/copilot-instructions.md`, `GEMINI.md`),
so everyone gets the same rules whether or not they use a given tool. Shared
VS Code settings and recommended extensions are in `.vscode/`. **Edit `AGENTS.md`
only** — the pointers must not drift.

`AGENTS.md` keeps the rules short and links one hop deeper to
[`agent-kit/`](agent-kit/), the vendored detail (working agreements, the
constitution + engineering checklists in `references/`, and the path-scoped
`rules/`). These are tool-neutral Markdown — any assistant or human can read
them.

The kit's *execution machinery* is intentionally **not** vendored: the osf-\*
agents, GitNexus / codebase-retrieval, hooks, skills, and MCP servers depend on
per-machine binaries, credits, and API keys. Set those up per developer with
[`agent-kit/docs/team-tooling-setup.md`](agent-kit/docs/team-tooling-setup.md)
and [`agent-kit/docs/agent-setup-runbook.md`](agent-kit/docs/agent-setup-runbook.md).
Skipping that setup is fine — `AGENTS.md` still applies; some one-hop references
just won't be actionable.

## Conventions (inherited from the reference repo)

- **Commits:** [Conventional Commits](https://www.conventionalcommits.org/),
  English subject, ≤100 chars — enforced by the `commit-msg` hook.
- **Branching:** `main` is production (maps to Xood's `production`) — never push
  or open a feature PR into it. Branch off `development`, PR into `development`;
  only `development` → `main` on release. The `pre-push` hook blocks direct pushes
  to `main` locally; enable branch protection on GitHub for the real gate.
- **Line endings:** LF everywhere, pinned by `.gitattributes` (`*.bat`/`*.cmd`/`*.ps1` stay CRLF).
- **Backend layering:** `api → application → domain → infrastructure → web` per module (Spring Modulith `@ApplicationModule`; only the `api` package is exported).
- **Tests:** written alongside code; coverage is measured now, gated later
  (see ADR-0001).

## Roadmap (next tầng, when the project earns it)

- **Tầng 2 — contract-first:** define an OpenAPI spec, generate the frontend API
  client (orval), add contract-drift checks. *(landed — see ADR-0005; both
  `backend-ci` and `frontend-ci` fail on an uncommitted regeneration.)*
- **Tầng 3 — CI:** GitHub Actions build/test/lint (`backend-ci`, `frontend-ci`),
  `CODEOWNERS`, and a report-only Trivy scan live in `.github/`. *(landed — the
  scans gate nothing yet; flip them to blocking once the numbers are known, the
  same way JaCoCo is measured before it gates.)*
- **Tầng 4 — containers & infra:** `docker compose up --build` runs the whole
  stack locally (see LOCAL_SETUP.md). Deploy pipelines and infra-as-code wait
  until there is something real to deploy.
