# Flow-Cortex

A personal project scaffolded on the same engineering conventions as the
DigiShield reference repo, deliberately scaled down to a solo project (Tầng 0–1:
foundations + architecture, no cloud infra yet).

- **Repo:** https://github.com/BienTranNgoc/Flow-Cortex
- **Stack:** Spring Boot 4.1 / Java 25 (backend) · React + TypeScript + Vite (frontend)

## Layout

```
Flow-Cortex/
├─ backend/      Gradle (Kotlin DSL) · Spring Boot 4.1 · Java 25
│   └─ src/main/java/com/flowcortex/{api,application,domain,infrastructure}
├─ frontend/     Vite · React · TypeScript · ESLint · Prettier · Vitest
├─ docs/         ADR.md — the decision log
├─ scripts/      commit-subject validation, one-time hook setup
├─ .githooks/    commit-msg (Conventional Commits) · pre-push (protect main)
└─ .editorconfig
```

## First-time setup

```bash
# 1. Enable the git hooks (per clone — hooks are tracked but not auto-active)
bash scripts/setup-hooks.sh

# 2. Backend — needs a JDK 25 on JAVA_HOME (no auto-provision configured)
cd backend && ./gradlew build      # compiles + runs tests

# 3. Frontend
cd frontend && npm install && npm run dev
```

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
- **Branching:** no direct pushes to `main`; feature branch + PR. The `pre-push`
  hook blocks it locally; enable branch protection on GitHub for the real gate.
- **Backend layering:** `api → application → domain → infrastructure` per feature.
- **Tests:** written alongside code; coverage is measured now, gated later
  (see ADR-0001).

## Roadmap (next tầng, when the project earns it)

- **Tầng 2 — contract-first:** define an OpenAPI spec, generate the frontend API
  client (orval), add contract-drift checks.
- **Tầng 3 — CI/CD:** GitHub Actions (build/test/lint), CODEOWNERS, dependency
  and image scanning (Trivy).
- **Tầng 4 — infra as code:** only when there is something real to deploy.
