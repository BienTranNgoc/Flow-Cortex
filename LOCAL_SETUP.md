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

## 1. Backend (Spring Boot)

```bash
cd backend

./gradlew build            # compile + run all tests
./gradlew bootRun          # start the API on http://localhost:8080

# Verify it's up:
curl http://localhost:8080/api/v1/health
# -> {"status":"UP","service":"flow-cortex"}

curl http://localhost:8080/actuator/health
# -> {"status":"UP"}
```

Coverage report after `./gradlew test`:
`backend/build/reports/jacoco/test/html/index.html`

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

## Troubleshooting

- **Gradle can't find a JDK 25 / toolchain error** → `JAVA_HOME` isn't pointing
  at a JDK 25. Export it (e.g. in `~/.bashrc`) and reopen the shell.
- **Port 5173 already in use** → Vite falls back to the next free port; read the
  URL it prints.
