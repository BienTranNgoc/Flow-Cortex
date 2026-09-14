---
paths:
  - "**/.env*"
  - "**/*config*.ts"
  - "**/*settings*.ts"
  - "**/*.module.ts"
  - "**/config/**"
---

# Runtime config — DB-backed settings overlay, not more dotenv flags

**A knob that needs to change WITHOUT a redeploy/restart does NOT belong in dotenv. Stop adding env flags for runtime-tunable behavior — route them through a DB-backed settings store editable via a guarded API.** dotenv is read once at boot, so every new runtime flag there is one more edit-and-restart-and-forget. Reserve env for secrets and deploy-time (per-environment) config.

**Three tiers — decide the tier BEFORE adding any config:**
- **Tier 1 — env / secret manager (immutable at runtime):** secrets, credentials, connection strings, JWT signing keys, security toggles (`AUTH_REQUIRED`, `ENABLE_ADMIN_API`). Never moves out of env.
- **Tier 2 — DB settings table, editable via a guarded API (runtime, non-secret):** feature toggles, rate limits, model selection, quotas, maintenance mode, worker concurrency, tuning knobs.
- **Tier 3 — code default (compile-time fallback):** baked into a typed key-registry.

**Resolution precedence: `DB override > env > code default`.** This is additive — never delete existing env flags; env stays the boot default and a DB row only overrides it when set, so adopting this is non-breaking.

**Hard security boundary (ties to §10):** secrets / API keys / security-enforcement toggles NEVER enter a store editable via API. A DB-write path to a secret + an outbound channel = lethal-trifecta exfil. The typed key-registry must enumerate every valid key so the write API rejects unknown keys before touching the DB.

**Build vs buy:** default to a **custom typed settings overlay** when knobs are few (<~50), global (no per-user/segment targeting), and the team is small — it reuses existing Postgres + Redis, ~1–2 days. Adopt **Unleash/Flagsmith (self-host)** only when you need percentage/segment targeting, a non-engineer UI, or approval workflows; **ConfigCat (SaaS)** when zero-ops matters and flag-eval data leaving your infra is acceptable.

**Reference shape (NestJS + Postgres + Redis):**
- Tables: `app_settings(key PK, value, updated_by, updated_at)` + `settings_audit_log(key, old, new, actor, at)`.
- `SettingsService.get(key)` → in-memory Map > env > Zod default. `safeParse` on load (bad row → skip, don't crash); strict `parse` on write (bad value → 400).
- `SettingsService.set(key, value, actor)` → Zod parse → upsert → audit row → `redis.publish('settings:invalidated', key)`.
- Multi-instance invalidation: Redis pub/sub with **separate** publisher/subscriber connections; subscribe BEFORE the initial `loadAll()`; re-hydrate on `onModuleInit` so a missed message self-heals on restart. Single instance → short TTL suffices, but pub/sub future-proofs.
- Guard the write API with admin auth + role; record who/old→new in the audit log.
