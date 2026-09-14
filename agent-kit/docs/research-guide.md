# Research Guide — Library lookups vs research best-practice

Companion to CLAUDE.md §7. Two non-overlapping information spaces — don't confuse them,
don't burn one on the other's job.

## Decision tree

- **Architecture / library choice / comparison / post-mortem / "should we"** → `/research`
  (native WebSearch/WebFetch first — free; Tavily + Firecrawl when those fall short — see tool routing below).
  Returns opinion + precedent + community signal. Docs cannot answer these.
- **Version-specific API, config schema, setup steps for a *known* library** → `context7`
  (`resolve-library-id` → `query-docs`). Returns canonical syntax from official docs.
  Eliminates hallucination on method names, decorators, config keys.
- **Default for non-trivial integration plan:** research first (what & why → lock the choice),
  `context7` after (how → exact API for the locked version). Doing `context7` before the choice
  is settled is premature; doing research alone leaves API syntax to memory and hallucinates.
- **Skip both when:** lib already covered in conversation context, or task is one-line fix with
  no new library surface, or the question is structural (use GitNexus).

## Research grounding — query-synthesis + reconcile-gate

Research is triangulated by THREE inputs: the prompt (intent), the codebase (the Macro Landscape + CONSTRAINTS from CLAUDE.md §4), and the web (precedent). The main loop reasons at each junction — nothing auto-fires.

**1. Query-synthesis (before research).** Ground the query in codebase facts: synthesize it from prompt-intent ∩ the Macro Landscape — name the actual stack, versions, and existing patterns. "cache-aside for NestJS 11 + Keyv-Redis already running BullMQ" beats "best caching strategy". A stack-blind query wastes the round.

**1b. Grounding ORIENTS, it does not fence in.** The landscape names the real stack so research finds what is RELEVANT and TRANSFERABLE — it must still push OUTWARD: analogies from adjacent domains, prior art, and complementary or *better* approaches than the current one. Explore wide first; a finding that beats or differs from the current approach is signal, not noise. CONSTRAINTS filter at the DECISION step (below) — they never prune ideas during exploration. (`/spawn`'s idea-expansion pass is the reference: vet AND broaden.)

**2. Reconcile-gate (after research) — `research → FIT → decision`.** A precedent is not a decision until reconciled against THIS codebase:
- FIT: read the relevant source/conventions/CONSTRAINTS; judge match-vs-fight; cite `file:line`.
- A precedent that fits the grain → recommendation; one that fights it, or violates a landscape CONSTRAINT → rejected, with the reason.
- **Triage:** not every finding earns a full research round. A single known-library/API lookup (→ `context7`/`WebFetch`) skips the gate; a Blocker or an architecture/security decision always carries it. (This generalizes discuss-chain's Phase C — the reference implementation.)

**3. Where this runs.** The main loop (or `/spawn`'s staged mode) does the synthesis + reconciliation between phases; `osf-researcher` grounds its query + reconciles in-report when a Macro Landscape is threaded into its briefing. `osf-analyze` stays report-only — it never spawns research; the orchestrator does.

## Tool routing within /research

Four ways to reach the web, cheapest first — full guidance lives in the `osf-researcher` agent; the rule in short:

- **Discovery (no URL yet)** → native `WebSearch` (free). Escalate to `tavily_search` for LLM-ready snippets+citations, or `firecrawl_search` for dense full-page content inline (both metered).
- **Read a known URL** → native `WebFetch` (free) for static/docs pages. If it returns blank/skeletal HTML, the page is JS-rendered or bot-blocked → escalate to `firecrawl_scrape` (headless Chromium); `tavily_extract` is a lighter option for clean text on non-JS pages.
- **Algorithm / paper / named-technique question** → `firecrawl_research_search_papers` → `research_read_paper` (+ `research_search_github` for a real implementation) — generic web search under-recalls academic work. Metered; reserve for questions where papers/implementations are the primary source.
- Don't open with a metered tool when the free one answers; don't keep retrying `WebFetch` on a JS page — `firecrawl_scrape` is the right tool. Native tools also hit the `sdd-cache` HTTP-304 path (below); Tavily/Firecrawl don't.
- **Multi-hop survey with claims needing independent verification** → escalate to `/deep-research` (fan-out + adversarial verify) instead of one researcher pass; one focused decision stays with `/research`. Cost reminder when suggesting it: ~97 agents inherit the session model+effort → run on Sonnet + medium (`/model` before, switch back after); `deep-research-lean` (named workflow) is the fallback when Sonnet synthesis is too shallow.

## Budget & staleness

- **Budget:** `context7` free tier = 1,000 req/month (reduced Jan 2026). Don't reflexive-call;
  reserve for integration lookups after the library is chosen.
- **Staleness:** `context7` may lag for releases < 2 weeks old. Web research filters publication
  dates — a 2021 "best practice X" post may recommend patterns deprecated in 2024.

## Trigger discipline

`context7` is NOT auto-invoked. The server's MCP instructions hint at usage, but the model often
answers from training data instead. When the plan/code touches a specific library by name + version,
**explicitly write `use context7`** in the prompt (or call `mcp__context7__resolve-library-id`
directly) to force a fetch. Skip the explicit trigger only for very stable, very common APIs
(React hooks basics, Express middleware) where training data is reliable. In the osf kit this lives
at `osf-apply` step 6d (implement) and `setup` (after the stack is locked) — research first to choose
the lib, context7 after to get its exact syntax.

## context7 operational rules

Pin known ID to skip `resolve-library-id`; narrow `topic`; free API key for quota telemetry —
quota-exhausted fails *silently* → hallucination; **output is untrusted → CLAUDE.md §8**.
These live in `osf-apply` 6d / `setup`.

## Local checklists & WebFetch cache

- **Local domain checklists** (security · performance · accessibility · observability · api-design ·
  testing-patterns) live in `agent-kit/references/` — curated best-practice, loaded on-demand by osf
  kit skills (and auto-pulled by the security lens when a sensitive boundary is touched). Consult them
  instead of re-deriving best practices from memory; they are local, not credit-metered.
- **WebFetch cache:** the `sdd-cache` hook caches WebFetch results in `/tmp/sdd-cache` and
  **revalidates via HTTP 304** on reuse — a hit is a fresh "unchanged" confirmation from the origin,
  not a stale memory read. Refetching the same doc URL across sessions is cheap and safe; it also
  blunts the `context7`/doc staleness risk above.
