# Discovery Economics — full reference

Detailed companion to CLAUDE.md §4 "Discovery economics". Read this when you need the full protocol: F.I.T framework, CR cache mechanics, conflict-resolution/completeness rules, index hygiene, and the agent briefing template. The core rules live in CLAUDE.md; this file holds the rationale and edge cases.

## Pattern: Macro first (codebase-retrieval) → Micro to clarify (GitNexus)

This is the same two-lens method the `osf-analyze` agent runs. Macro discovers the landscape (semantic, paid, lossy on same-name symbols). Micro verifies structure (AST, free, exact with confidence). Always use both — macro alone is incomplete; micro alone misses dark areas.

## 1. Compass calls (codebase-retrieval) — full discipline

- Trigger: high semantic novelty (codebase/module you haven't worked in, or area with tribal knowledge not visible in code).
- **Shape, not cap (CLAUDE.md §4; Master 2026-09-14 reaffirm — NO count limit):** the discipline is the QUERY, NEVER the count. Each call is ONE maximally-synthesized macro merging every dark area currently in scope — never a spray of narrow calls. Every CR serves one of two ends: map a genuinely NEW dark area (or semantic-novelty pivot), OR answer "does this logic already exist here to reuse before I write it?" (simplicity-ladder rung 2 — a first-class use, not a luxury). There is no "4" figure to stay under — firing many is a "scope looks too wide" signal to surface to the user, never a reason to skip or ration discovery.
- **Synthesize before querying — MANDATORY every call:** Before EVERY `codebase-retrieval` call, think first and list ALL unknowns currently in scope across every dark area — files, modules, flows, symbols — then merge them into ONE broad query covering everything. Never call a narrow query when merging is possible; never split into separate calls what one synthesized query can carry.
- Each Augment query costs ~40-70 credit. Only "spend" a call when you actually discover a new dark area that GitNexus cannot answer — not every time you switch files.
- F.I.T framework: **F**ormat clear, **I**ntent matches the real question, **T**iming when index is fresh.
- State in your response: "Compass opened on <area>: found <key files/modules>" — so subsequent turns know orientation is done.

## 1.5. CR cache (hook-maintained) — a CROSS-AGENT dedup layer, NOT a same-session re-read

- A PostToolUse hook (`cr-cache-append.sh`) auto-appends every `codebase-retrieval` result to a **session-scoped** file `${TMPDIR:-/tmp}/cr-cache/<session-id>__<project-path "/" → "-">.md` (keyed by `CLAUDE_CODE_SESSION_ID`, like `/enhance`). Storage is TMPDIR — **ephemeral by design**, wiped on reboot or ~3-day idle. Session-keying makes its value **cross-agent + post-compact within ONE conversation, intentionally NOT cross-session** (cross-session TMPDIR proved unreliable; a stale other-session landscape is worse than re-fetching — fresh context bound to the conversation is the point). The MAIN session may also persist a **distilled** Macro Landscape into the SAME file via `cr-landscape-write.sh` — readers prefer the `## DISTILLED LANDSCAPE` section over the raw entries. **Canonical home when an OpenSpec change exists (Master 2026-09-09): `openspec/changes/<change>/content/macro-landscape.md`** — the session cache is wiped on compact/restart, the content file is not; write both, brief agents with the content file, and treat the cache as the raw appendix.
- **When to read it:** only when your context is FRESH — a freshly spawned subagent (empty context), or after `/compact`/`/clear`. Read the file, and a dark area already covered there does NOT justify a new call — reuse the cached landscape and verify specifics with GitNexus/Read (free).
- **When NOT to read it:** within your own live session, where the prior CR result is already in context. Re-Reading the file then is duplicate tokens — skip it.
- Entries are stamped `branch@sha (clean|dirty)`. On git drift, treat them as hints: the file/module landscape usually still holds, but verify symbols structurally before acting.
- **Staleness guard (active) before reuse.** Before REUSING a landscape (cached or in-conversation), compare its `@sha` stamp to the current HEAD of the checkout it was read from (`git -C <path> rev-parse HEAD` — on a worktree setup `list_repos` reports the INDEX's checkout, a sibling, so its sha never matches and must not be used for this test); if commits since the stamp touch files named in the landscape, mark just those areas STALE and re-macro only them — intersect changed files with landscape files so unrelated commits don't false-flag (§1.8 step 2). Stamp comparison is the cheapest signal; `git -C <dir> log --since=<stamp> --name-only` is the fallback when the ROOT isn't itself a git repo. Flag, don't auto-purge — the agent judges.
- Subagents inherit the parent's `CLAUDE_CODE_SESSION_ID` (verified) and share TMPDIR, so the session-keyed cache crosses the spawn boundary — **this is the primary payoff**: a subagent reusing the parent's cached landscape avoids a paid CR call. (Edge: if a SUBAGENT itself calls CR, its hook event may carry a sub-session id → its raw lands in a sub-session file, not auto-shared back; the agent reports new dark areas to the orchestrator instead.) Realizing reuse needs the spawn briefing (§6) — or MiM threading (§1.8), which is stronger because it hands over the distilled landscape directly.
- **Proactive surfacing (hook):** the `cr-budget-guard.sh` PreToolUse hook now opens this cache file at CR-call time and injects its entry count + recent queries as advisory context — so even a fresh context is told "a landscape already exists, reuse it before spending a call" without relying on the agent remembering to read it. It advises, never blocks (cache is keyed by session+directory and surfaces recent queries + whether a DISTILLED LANDSCAPE section exists, but its existence isn't proof THIS query is already answered — the agent judges relevance).

## 1.7. directory_path — scope-driven, fail-safe to ROOT

`codebase-retrieval` takes a REQUIRED `directory_path` (absolute path; the tool's own `<RULES>` mandate it and note it "allows searching across multiple projects dynamically"). It is ALSO the cr-cache key — the hooks key the cache file by `cr-cache/$(directory_path | tr '/' '-').md` (`cr-cache-append.sh:12,26`, `cr-budget-guard.sh:50,53`). So the value you pass decides BOTH what CR searches AND which cache bucket is read/written.

- Resolve `directory_path` from the task (the files in play), NOT from CWD — the main session's CWD is the home dir, not the project.
- **Root workspace with sub-repos** (CLAUDE.md §4): pick by scope, then keep it consistent for the whole task.
  - Unsure which sub-repos are touched, or genuinely cross-repo → **FAIL-SAFE to the ROOT** workspace. CR indexes every sub-repo under it, so cross-repo touch-points stay visible. A silently-missed cross-repo dependency is worse than a heavier/noisier index.
  - Whole task provably inside one sub-repo → that sub-repo (lighter index, right credit). A sub-repo-keyed cache file is legitimate, NOT "fragmentation".
- **Bad fragmentation = mixing** ROOT on one call and a subdir on another for the SAME area within the SAME task → the two calls miss each other's cache. Multiple cache files for genuinely different scopes are normal (e.g. xood has `…-xood.md`, `…-xood-xood-backend.md`, `…-xood-xood-frontend.md` — root + two sub-repos).
- **Worktree rule (Master 2026-09-09, measured):** when the change lives in git worktrees (`xood-backend-hotfix`, `*-xp147-next`, …), `directory_path` = **the worktree being edited**, one macro per repo-worktree touched, all merged into ONE landscape. Measured 2026-09-09 with the same question at ROOT vs a worktree: ROOT returned the SAME file from 6 checkouts of one repo (half the payload was duplicate `consent.module.ts` from other branches) AND served a stale pre-edit copy of the file, while the worktree query was exact, current, and surfaced a related pattern the ROOT run missed. ROOT is the fail-safe only when no worktree is in play and the touch is genuinely cross-repo; it is never the right scope for a worktree-heavy tree. The cache key follows the worktree path — that is correct, not fragmentation.
  - **Ticket-workspace folder — the clean cross-repo CR scope (Master 2026-09-14, probe-verified).** When a ticket's worktrees are GROUPED in one folder `<root>/<slug>-ws/` holding exactly ONE checkout per repo (the git skill's ACTION: WORKTREE default), point `directory_path` at that FOLDER for a single macro covering all the ticket's repos at once — it is soup-free BY CONSTRUCTION (one checkout per repo → the "same file from 6 checkouts" ROOT failure is structurally impossible), and it becomes its own clean cache bucket. This is the sanctioned way to get CR cross-repo WITHOUT the ROOT duplicate/stale penalty; it does not exist for the flat sibling layout (where the touched worktrees are scattered among unrelated same-repo checkouts). Measured 2026-09-14: CR indexes and serves the relocated `<slug>-ws/<repo>` paths normally (an earlier same-session auggie disconnect was a transient crash, not the layout). Per-repo depth stays available — point at a single `<slug>-ws/<repo>`.
- **Two different cross-repo mechanisms — don't conflate:** CR goes cross-repo via `directory_path=ROOT`; GitNexus goes cross-repo via a **group** (`repo:"@group"` + `group_sync`, Contract Registry). There is no "CR group".
- **Freshness edge:** when the ROOT is only a folder of sub-repos and is not itself a git repo, the cr-cache `branch@sha` stamp at root is meaningless (each sub-repo has its own branch) → treat such entries as hints and verify staleness per sub-repo via `gitnexus list_repos`.

## 1.8. Macro-in-Main / Micro-in-Agent (MiM) — hoist the macro, isolate the micro

When a skill will spawn a CR-capable subagent (analyze/apply/verify), run the macro CR in the MAIN session instead of inside the subagent, so the result is reused across phases without re-paying.

Why: the cr-cache (§1.5) already gives cross-agent reuse, but it is directory-keyed — the next agent must JUDGE whether the cached landscape covers its query. Running the macro in main, distilling it, and threading it into each agent's briefing removes that judgment: reuse is exact and orchestrator-controlled. Keep the DISTILLED landscape (small) in conversation — never the raw CR output (tens of KB), which is exactly what the subagent boundary exists to keep out of the precious main context.

Standard sequence in main, before spawning (conditional-reuse):
1. `gitnexus list_repos` (when touching code) — learn which sub-repos exist → decide `directory_path` scope (§1.7, fail-safe ROOT) + glance at freshness. The agent still runs its own list_repos freshness check.
2. Reuse check — is there already a fresh Macro Landscape in the conversation covering this area+scope? Yes → reuse, skip to step 4. **Staleness guard before reuse:** compare the landscape's `@sha` stamp to the current HEAD of the checkout the landscape names (`git -C <dir> rev-parse HEAD`; `list_repos` only for index freshness; `git -C <dir> log --since=<stamp> --name-only` when no sha); if commits since the stamp touch files named in the landscape, mark just those areas STALE and re-macro only them. Intersect changed files with landscape files — don't purge on unrelated commits.
3. Macro CR (only if needed) — only when no landscape yet AND there is a real dark area AND §3 "skip compass" does not apply. ONE synthesized query, `directory_path` = the chosen scope → distill into a Macro Landscape. (The PostToolUse hook auto-appends the RAW result to the session cache; ALSO persist the DISTILLED landscape into the same session file so a post-`/compact` or fresh reader gets the distilled version, not just raw: `printf '%s' "<the distilled landscape>" | ~/.claude/hooks/cr-landscape-write.sh "<directory_path>"` — and, when an OpenSpec change exists, write the same distilled text to `openspec/changes/<change>/content/macro-landscape.md`, the copy that survives compaction and that `/apply` briefs from.)
4. Spawn — paste the distilled Macro Landscape + the skip-CR briefing into the agent prompt. Shape: `agent-kit/docs/templates/macro-landscape.template.md` (Checkouts table with per-checkout `@sha` + index coverage → Territory → Seams → CONSTRAINTS → Traps → Dark areas/Discoveries).

Recall CR only on a new dark area outside the landscape, or a scope (`directory_path`) change. Same scope/pipeline → one macro suffices; later phases reuse. Payoff is multi-phase pipelines over one area; for a one-off single spawn don't force it.

**Robustness — macro is BEST-EFFORT, never a hard dependency.** The auggie MCP server (`mcp__auggie__codebase-retrieval`) can disconnect mid-session; when it does, NEITHER the main session NOR subagents can call CR. In that state, skip the macro step entirely and proceed GitNexus-first + Grep/Read — the analysis still completes, just without the semantic macro lens. (Verified 2026-06-24: a live MiM dry-run on `xood-backend` had auggie drop, then reconnect via `/mcp`; main then fired one macro CR, the distilled Macro Landscape was threaded to `osf-analyze`, and the agent's first tool call was `gitnexus list_repos` with ZERO CR calls — reusing the landscape exactly as designed.)

Macro Landscape format (what lives in conversation) — a CLUSTERED surprise-map, not a flat dump:
```
## Macro Landscape — codebase-retrieval @ <branch>@<sha> · dir=<abs project root>
Query: <the one synthesized query asked>
### <cluster / module A>   (e.g. "auth")
- <symbol> (<file:line>) — <role>  →depends: <symbol|file>
- ...
### <cluster / module B>
- ...
## CONSTRAINTS (immutable)   — include ONLY if any exist; carried verbatim through every phase, never distilled or trimmed away
- <hard prohibition | numeric pin (port/threshold/version) | scope boundary>
Open dark areas: <none | list>
```
Group entries under module/cluster headings (infer clusters from the CR result + GitNexus `clusters` resource if available) and add lightweight `→depends:` edges where known — flat prose loses the multi-hop structure that makes scoping cheap. **Distill it as a SURPRISE MAP:** keep only what is dark / non-obvious / project-specific, plus the load-bearing `file:line` anchors; DROP what any competent model already knows from the framework/stack (standard conventions, common-library behavior). A surprise map, not a tutorial — keep it small. The `## CONSTRAINTS (immutable)` block is the one section distillation and `/compact` must NEVER drop: hard prohibitions, numeric pins, scope boundaries — thread it verbatim into every downstream phase.

Agent briefing line (paste when spawning):
```
Macro already ran in main — Macro Landscape: [paste block]. directory_path used = <abs root>.
SKIP codebase-retrieval; go straight to GitNexus micro-verify — seed context/impact from EVERY
symbol in the landscape (the seed-set), node-budget ~3-5x seeds, depth-1 default / depth-2 only on
edge confidence >=0.8, never deeper. Carry the CONSTRAINTS (immutable) block verbatim.
Spend a CR call ONLY for a NEW dark area outside the landscape — then use the SAME directory_path;
report it back so the orchestrator updates the landscape.
```

Relationship to cr-cache: MiM and the cr-cache are ADDITIVE. The in-conversation landscape is fast same-session reuse but dies on `/compact` or `/clear` and is invisible to other skills/sessions. The cr-cache (raw, on disk) survives those and is the only shared layer across skills and sessions. Keep both.

## 2. Map detail (GitNexus + Read) — per sub-question

- `mcp__gitnexus__impact` BEFORE any modify to a symbol with exports.
- `mcp__gitnexus__context` to understand callers/callees of a specific symbol.
- `mcp__gitnexus__rename` with `dry_run: true` MANDATORY before any rename. Distinguish graph edits (high confidence, AST-based, safe to apply) from text_search edits (lower confidence, must be reviewed individually). Applying blind text_search edits from rename output is forbidden.
- `mcp__gitnexus__detect_changes` BEFORE any commit — pre-commit gate that maps the git diff to affected symbols/processes. Never commit without it (GitNexus AGENTS.md: "NEVER commit without detect_changes()"). **Hook-enforced:** the commit-gate PreToolUse hook (`~/.claude/hooks/commit-gate/`) blocks `git commit` in indexed repos when detect_changes hasn't run in the last 15 minutes at the current HEAD.
- Trust threshold: confidence >= 0.8 → trust as structural truth. MEDIUM/<0.8 → verify manually before acting.
- State: "Checked gitnexus impact: <N> callers in <files>" or "No external dependencies".
- **Tools the vendor `gitnexus-*` skills don't document** (read the `gitnexus-overlay` skill for params + current schema): `api_impact` (pre-change report for an API route handler — run BEFORE editing one), `route_map` (endpoint↔consumer↔handler map), `shape_check` (response-shape drift vs consumers), `tool_map` (MCP/RPC tool defs), `group_list`/`group_sync` (cross-repo Contract Registry). Group mode (`repo: "@<group>"`) and `impact{summaryOnly,crossDepth}` also live there.

## 2.5. Resolve conflicts + completeness (analyze-style)

- **GitNexus wins for structural claims.** When `codebase-retrieval` says "these are related" but `gitnexus__context`/`impact` shows no structural connection, trust GitNexus — CR may have matched on name similarity, not dependency. Conversely, a GitNexus connection that CR missed is a *hidden* dependency worth highlighting, not ignoring.
- **Completeness rule.** If `gitnexus__context` returns N dependents, all N MUST appear in your answer/report. Do not silently drop any. If `impact` reports fewer than `context`, investigate the gap.
- **Impact propagation — seed-complete, budget-capped, depth-2 ceiling.** Seed `context`/`impact` from EVERY symbol the Macro Landscape surfaced (the landscape IS the seed-set), not only the one named in the request — a hidden dependency the landscape surfaced but the question didn't name is exactly what gets missed. Cap traced nodes at a budget ≈ 3–5× the seed count; trace depth-1 by default, extend to depth-2 only on edges with confidence ≥0.8. Do NOT go past depth-2 — measured to add noise, not signal (RepoGraph k≥3 harmful; the leverage is *invoking* the graph at all, not depth). For each seed: run `context` → for each dependent, run `context` again (transitive, depth-2). Single-level tracing misses breaking chains.
- **Interface / type / contract changes** require tracing: all implementors, all call sites passing/receiving the type, all type assertions/casts, all generic constraints (`extends`). Skip this and you'll miss the exact "I changed an interface but didn't flag its consumers" scenario.
- **CR tool absent from the session (Master 2026-09-09).** The auggie MCP server can report Connected yet offer NO `codebase-retrieval` tool (observed 2026-09-09 in the xood root session: `--mcp-auto-workspace` with a non-git root). That is an environment limit, not a policy choice: the orchestrator and every osf-* agent record one deviation line and run the discovery pass at full depth with GitNexus `query` (+`goal`/`task_context`) for the semantic leg plus grep/Read — never skip discovery, never stall waiting for CR.
- **Fallback only per-symbol, not per-workflow.** If GitNexus returns "Symbol not found" for one symbol, fall back to Grep/Read *for that symbol* — keep using GitNexus for the rest.

## 3. Skip compass (do NOT call codebase-retrieval) when

- Typo / single-line / comment-only fix.
- User specified exact file and symbol in the prompt.
- The relevant module already appears in conversation context from a prior compass call in this task.
- Net-new file in isolated module with no exports yet.

## 4. Reset compass (call codebase-retrieval again) when

- Task pivots to an area with high semantic novelty (new module with unfamiliar concepts, area with business logic not yet in conversation).
- Do NOT reset just because the file path changes — semantic novelty is the trigger, not directory boundary.

## 5. Index hygiene — stale graphs mislead actively

- GitNexus stale index doesn't just degrade quality — it leads agent toward wrong decisions that look correct. Check `mcp__gitnexus__list_repos` → `staleness.commitsBehind` (absent = fresh) BEFORE trusting a micro-verify chain (task start, after branch switch, or long sessions between commits — the PostToolUse hook only nudges after git mutations, so mid-session drift goes unnoticed); reanalyze with a plain `gitnexus analyze` when in doubt, and confirm `indexedAt` moved — `analyze` exits 0 even when it fails.
- **Plain-index policy (Master 2026-09-14 — supersedes the old max-indexing rule):** every `gitnexus analyze` runs PLAIN — NO `--pdg`, NO `--embeddings`. GitNexus is the STRUCTURAL layer only (AST graph: `context`, `impact` call-graph, `query`, `trace`, callers/references). The SEMANTIC layer is CR (`codebase-retrieval`) — run the CR macro first, then a plain GitNexus index for structural micro follow-up. Consequences to honor: the embeddings half of hybrid `query` is gone (use CR for meaning), and the PDG-only tools `pdg_query` / `impact(mode:'pdg')` / `explain`-dependence return empty on a plain index (missing-layer, not "no dependence") — do not call them; use call-graph `impact` + CR. `.gitnexusrc` is `{}`/absent → plain; a fresh worktree needs no flags copied.
- **Group hygiene:** after re-indexing a repo that belongs to a group (discover via `mcp__gitnexus__group_list` / `gitnexus group list`), run `mcp__gitnexus__group_sync` (CLI: `gitnexus group sync <name>`) to rebuild the Contract Registry and cross-links — cross-repo impact answers are only as fresh as the last sync. Skip silently when no groups exist.
- **Worktree hygiene — one index per repo per session (measured 2026-08-26).** GitNexus indexes per
  *checkout*, not per repo: `xood-backend` and `xood-backend-xp147` are two full graphs of ~equal size
  (117k vs 115k nodes, 300k vs 294k edges). The MCP server loads each on first query and holds it —
  measured on one session: **116 MB idle → 880 MB after one index → 1,306 MB after a second → 1,782 MB
  after a third**, roughly linear at 430–480 MB per additional index. `pool-adapter.ts` caps the pool at
  `MAX_POOL_SIZE = 5` with a 5-minute `IDLE_TIMEOUT_MS`, so the design ceiling is ~2.2 GB per session —
  and on a worktree-heavy tree those 5 slots fill with duplicates of the SAME two repos. `list_repos`
  already pairs them under `siblings`: read it, pick one, stay on it. Prefer the main checkout unless the
  question is specifically about the worktree's branch. Multiply by the number of concurrent Claude Code
  sessions (each runs its own MCP server) before assuming there is headroom.
- codebase-retrieval index syncs in real-time (sub-10s) but immediately-edited files may not be queryable for a few seconds.

## 6. Agents follow the same pattern — no exceptions

- Any agent spawned (via Agent tool, subagent_type, or skill) must follow this identical macro-first + micro-verify pattern when exploring the codebase, plus the analyze-style conflict-resolution / completeness / depth-2 propagation rules above.
- When briefing an agent for codebase exploration, explicitly instruct: "**If the orchestrator already ran the macro (a Macro Landscape is included in this briefing): REUSE it and SKIP codebase-retrieval — go straight to GitNexus micro.** Otherwise: First, Read the session cache file the guard hook surfaces (`${TMPDIR:-/tmp}/cr-cache/<session-id>__<dir / → ->.md`) if it exists — **prefer its `## DISTILLED LANDSCAPE` section** if present, else the raw entries — and reuse any landscape already cached there; only spend a `codebase-retrieval` call on dark areas NOT already covered. Macro: think first, then merge ALL remaining unknowns across every dark area into ONE synthesized `mcp__auggie__codebase-retrieval` query (don't fire one call per dark area), passing `directory_path` = the absolute project ROOT given by the orchestrator (fail-safe ROOT on a sub-repo workspace — §1.7; do NOT default to CWD); spend another call on a genuine new-dark-area pivot or reuse/existence check (no count limit — surface scope-too-wide instead of rationing). Then micro: use `mcp__gitnexus__context` / `impact` — SEEDED from every symbol in the landscape (the seed-set), node-budget ~3-5x seeds, depth-1 default / depth-2 only on confidence >=0.8, never deeper — for specific symbol/impact questions. On conflict between the two, GitNexus wins for structural claims."
- Do NOT spawn an agent with a narrow task like "find where X is defined" — that's a single gitnexus query, not an agent task. Only spawn agents when the work justifies it (multi-step, cross-cutting, isolated context window needed, or you specifically want `osf-analyze` to enforce depth-2 propagation end-to-end).
