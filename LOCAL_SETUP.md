# Local setup — Flow-Cortex

Goal: a fresh clone runs in a few minutes.

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK  | 25      | `JAVA_HOME` must point at it — Gradle here is **not** configured to auto-download a JDK. |
| Node | ≥ 20    | For the frontend. |
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

The backend now has a real datasource (PostgreSQL + Flyway, `ddl-auto: validate`),
so it needs a database to boot. The tests bring their own (Testcontainers, needs
Docker running); `bootRun` needs a Postgres you start yourself — easiest is the
compose one:

```bash
cd backend

./gradlew build            # compile + run all tests (Testcontainers → needs Docker)

# Start just the database (compose reads POSTGRES_PASSWORD / JWT_SECRET from .env):
cp ../.env.example ../.env         # once — then edit the values
docker compose -f ../docker-compose.yml up -d postgres

set -a; . ../.env; set +a          # export JWT_SECRET (32+ chars) + POSTGRES_PASSWORD
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/flowcortex \
SPRING_DATASOURCE_USERNAME=flowcortex \
SPRING_DATASOURCE_PASSWORD=$POSTGRES_PASSWORD \
SPRING_PROFILES_ACTIVE=dev \
  ./gradlew bootRun                # start the API on http://localhost:8080

# Public probe (no auth):
curl http://localhost:8080/actuator/health
# -> {"status":"UP"}

# /api/v1/** is authenticated now — log in first, then use the token:
curl -s -XPOST http://localhost:8080/api/v1/auth/login \
  -H 'content-type: application/json' \
  -d '{"email":"admin1@flowcortex.dev","password":"AdminPass123!"}'
# -> { "token": "..." }   (the `dev` profile seeds admin1@/admin2@ (AdminPass123!)
#     + member1@flowcortex.dev (MemberPass123!) — dev-only, never real passwords)
```

Coverage report after `./gradlew test` (multi-module — the boot app aggregates ITs):
`backend/boot/app/build/reports/jacoco/test/html/index.html` (and each
`backend/modules/<m>/build/reports/jacoco/test/html/` for a module in isolation).

## 2. Frontend (Vite + React)

```bash
cd frontend
cp .env.example .env.local     # adjust VITE_API_BASE_URL if needed
npm install
npm run dev                    # http://localhost:5173 (or next free port)

npm run test                   # Vitest
npm run lint                   # ESLint
npm run typecheck              # tsc
npm run format:check           # Prettier
```

## 3. Run the whole stack with Docker (optional)

For a one-command end-to-end run (or a demo) without installing a JDK or Node
locally — only Docker is needed:

```bash
cp .env.example .env               # once — set JWT_SECRET (32+ chars) and POSTGRES_PASSWORD
docker compose up --build
# postgres -> internal (healthchecked; backend waits for it)
# backend  -> http://localhost:8080   (API + /actuator/health)
# frontend -> http://localhost:5173   (SPA; nginx proxies /api -> backend)

# The stack seeds two admins + one member (see §1). Log in through the SPA, or:
curl http://localhost:8080/actuator/health   # public probe
```

This is for running/demoing the assembled stack (Postgres + API + SPA). Day-to-day
coding is still faster with a standalone Postgres + `./gradlew bootRun` + `npm run
dev` (hot reload) as in §1. To exercise the workflow kill switch, restart
the backend with `FLOWCORTEX_WORKFLOW_ENABLED=false docker compose up -d backend`.

## Troubleshooting

- **Gradle can't find a JDK 25 / toolchain error** → `JAVA_HOME` isn't pointing
  at a JDK 25. Export it (e.g. in `~/.bashrc`) and reopen the shell.
- **Port 5173 already in use** → Vite falls back to the next free port; read the
  URL it prints.
