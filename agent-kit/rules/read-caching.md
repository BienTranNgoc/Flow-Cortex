---
paths:
  - "**/*cache*"
  - "**/*.service.ts"
  - "**/*.repository.ts"
  - "**/*.resolver.ts"
---

# Read caching — cache-aside + invalidate-on-write, profile first

**Don't add a cache until a slow path is measured.** Confirm the bottleneck with EXPLAIN / APM first — a Postgres index (200ms→5ms) is often cheaper and safer than a cache layer (which adds stale-data bugs, race windows, stampede, Redis memory pressure). Cache deliberately, per hot path — never blanket-cache every endpoint.

**When caching is warranted, default to cache-aside on a shared Redis store:**
- **Read:** `cache.get(key)` → hit returns; miss → query DB → `cache.set(key, data, ttl)` → return.
- **Write (create/update/delete):** mutate DB → **commit FIRST**, then `cache.del(key)`. Never delete before commit (a rollback would leave the cache empty/wrong).

**Two shapes — pick by data size:**
- **Bounded table** (e.g. the settings overlay, §14): warm the whole thing into an in-memory Map at boot — reads are O(1), zero per-read query; bust via Redis pub/sub.
- **Unbounded entities** (users, orders, runs…): lazy per-key cache-aside in a **shared Redis store** (single source of truth across instances). Skip an in-memory L1 tier until Redis latency is measured as the bottleneck — L1 needs a pub/sub backplane to stay coherent across instances and isn't worth the complexity early.

**Invalidation that's easy to get wrong:**
- Single entity: `cache.del('{entity}:{id}')` — trivial.
- **List / filtered queries (the hard one):** you can't enumerate every cached list that held the entity. Use a **per-entity-type generation counter** — fold `v{n}` into list keys (`{entity}:list:v{n}:{queryHash}`) and `INCR {entity}:version` on any write; old list keys orphan and expire by TTL. NEVER `KEYS pattern*` in prod (O(N), blocks Redis) — `SCAN`+`UNLINK` only if a pattern delete is unavoidable.

**Framework gotcha (NestJS):** `CacheInterceptor` auto-caches GET responses but does **NOT** auto-invalidate on writes — call `cache.del()` explicitly in the mutation service. The current `@nestjs/cache-manager` (NestJS 11 wrapper) is **Keyv-based**: configure stores via `CacheModule.registerAsync({ useFactory: () => ({ stores: [new KeyvRedis(url)] }) })` from `@keyv/redis` (the older `cache-manager-redis-yet` adapter is pre-Keyv/legacy); `get()` returns `undefined` on miss and TTL is in **milliseconds**. Reuse the Redis already running (e.g. BullMQ) — no new infra.

**Safety net:** short TTL with ±20% jitter (bounds staleness, breaks synchronized-expiry stampedes); negative-cache "not found" with a short TTL (stops cache-penetration); set Redis `maxmemory-policy allkeys-lru`. NEVER cache secrets / tokens / PII or rapidly-changing counters.
