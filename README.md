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
