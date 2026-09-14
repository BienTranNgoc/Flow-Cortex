# Working agreements — detail

Overflow file for `~/.claude/CLAUDE.md`. CLAUDE.md loads on **every** request, so it carries only the short form of each agreement; the rationale, case studies, and measured numbers live here and are read on demand.

Read this when: a CLAUDE.md line is ambiguous in context, a decision needs the reasoning behind an agreement, or a past failure is worth re-reading before repeating its shape.

---

## Evidence standards — the failures behind the rule

**The 2026-06-25 backend miss.** A plan assumed an endpoint already returned a field that it actually stripped. Static checks were green; the browser was green because it ran on empty data. The feature was wholly broken on real data. One unverified premise silently invalidated everything built on it.

**Three plan points falsified after surviving a full cycle (measured 2026-07-27):**

| Point | What killed it |
|---|---|
| A "survivor defect" | Structural: the symbol had zero callers anywhere — dead code, and an archived deviation had already decided it |
| A "coverage gap" | Reality: the case was already covered end-to-end, and prod was 2.5% larger than the fixture, not materially larger |
| The central premise of an entire OpenSpec change (a config anchor "drifting") | Reality: one live Redis key was empty, so the anchor was already correct and the real cause lay elsewhere |

The lesson is not "run more gates" — it is that **structural facts and runtime facts fail differently**, so a claim resting on one does not inherit confidence from the other. Opus-5-generation models probe on their own; the standing requirement is that the *evidence appears in the artifact*, not that a particular gate ran.

**Claims about code specifically.** Tests check code; nothing checks a sentence describing code.
- An absolute claim ("never", "every", "always") needs a pinning test, or it should not be written. Pin by mutation: remove the mechanism, confirm the expected test goes red, restore byte-identical, and show it.
- A measured number carries the experiment that produced it, by test name. Never infer a number and print it in a measurement table.
- "Đã ghi nhận" must be greppable before the loop closes. No grep result = not recorded.

Anti-rationalization table: `agent-kit/references/anti-rationalization.md`.

---

## Findings — end-state detail

Any pass that surfaces findings (`/review`, `/probe-loop`, `/discuss-chain`, `/analyze`, `/verify`, ad-hoc audits) drives **every** finding to one of three end-states in the same turn:

- **User-decision** — genuine preference, scope, or tradeoff. Ask A/B/★C.
- **Actionable, in-scope, low-risk** (Minor included) — resolve now. A read-only pass marks it *fold-by-default* and the caller folds it in without the user having to ask; an implementing pass just does it.
- **Deliberately not done** — record with a one-line reason and a removal condition. Recorded ≠ dropped.

This is not "do everything": the end-state of an out-of-scope Minor is *recorded-with-reason*. Manage decision fatigue by resolving the small stuff, never by dropping it. A chain/decision cap (e.g. probe-loop's ≤5) limits *questions asked of the user*, never *findings accounted for*.

**A severity-filtered fix loop does not discharge the severities it filters out.** Orchestrated pipelines loop on CRITICALs by design, so a WARNING cannot block progress — which makes non-CRITICAL findings the easiest thing in the flow to lose: the loop ignores them *correctly*, and nothing downstream claims them unless told to. Whoever runs the loop owns carrying the rest by name into the pass that resolves them. Measured 2026-07-27: a final verify round's 5 WARNINGs + 3 SUGGESTIONs reached implementation only by hand-carry; two were one-line fixes closing a spec-text violation the pipeline would otherwise have archived.

---

## Planning to production-complete

A plan is done only when it leaves the affected behavior production-stable long-term: root-cause level, implied edge/error paths and tests included, no structural choice that works today but breaks under growth, and no "phase 2 / TODO later / temporary" scope — unless the user explicitly approved phased delivery, in which case each deferred item carries a label, its limit, and a concrete removal condition. The shared explore mode encodes this for all planning commands (`CUSTOM:production-complete`).

**Prefer an executable reference over a written description.** When a criterion can be made runnable or renderable — a failing test, a type signature, a scored rubric, an HTML mockup, a real captured payload — reference that instead of paraphrasing it in prose, which both under-specifies (gaps get guessed) and over-specifies (wording that was never the point gets fixed). An OpenSpec `design.md` pointing at a signature, fixture, or mockup file beats one describing them.

---

## Multi-session execution & slicing

For implementations spanning multiple context windows (post-`proposal`, large `tasks.md`), read `agent-kit/docs/execution-harness.md` — the two-pass initializer→coding loop, context-rot rules, per-slice verify, and the critique-tier judgment table.

**Slice delegated implementation to a 300k–400k token band per slice, recalibrated off the running average.** Both edges are load-bearing: above 400k the run is opaque for too long, so a wrong turn taken early surfaces only after everything downstream is built; below 300k it spends a whole gate cycle (checkboxes, typecheck, tests, structural) on a fraction of the budget.

Start at ~12 tasks, then divide the band by `tokens ÷ tasks` **summed across every slice so far** — never off one slice. Measured 2026-07-27: slice 1 alone read 52.7k/task and produced a recalibration less than half the correct size, because one of its tasks demanded a 26-suite regression run; the running average across four slices was 27.9k/task. Front-loaded verification, a first CRITICAL blast-radius analysis, or first-contact with a large file all inflate slice 1 the same way. The one legitimate undersize is a seam the plan itself declares ("verify this before anything builds on it") — announce it as such.

---

## Recommendations — the two bars

Every recommendation clears both before it is final: (1) grounded in current best practice, (2) reconciled to this project's actual stack and constraints. A precedent that violates a project constraint is rejected, not softened into "maybe later".

- Give the strongest actionable improvement for the request now. Don't hand over a watered-down version and push the real fix to "as a follow-up" unless the user asked for phased delivery. If a proposal surfaces a problem needing restructuring, propose the refactor directly.
- Scale the proposal to what the finding warrants — a one-sentence fix doesn't need a heavy audit trail.
- **Ambiguity a tool can measure** (a fact, data shape, or flow) → resolve it by probing, don't ask.
- **Ambiguity about preference or intent** (which approach, whether a refactor is in scope, how much to invest) → ask; only the user can decide it.
- **Review findings chain to a resolution.** Each CRITICAL/WARNING/SUGGESTION gets: resolution direction (root-cause, never symptom patch) → best-practice grounding (cluster related findings into one brief; `/research` for critical/warning/security, a cited local reference may satisfy a mechanical one-liner — the citation is never skipped) → project-reconciled recommendation, leaving the affected flow production-stable. `/review` encodes this as its Resolution Chain; the contract applies to ad-hoc reviews too.

---

## Session & context hygiene

Context rot is invisible from inside the rotted context, so these are actions rather than judgments to re-derive:

- **Two consecutive failed fix attempts → stop digging.** Recommend `/clear` (or `/rewind`) plus a rewritten prompt carrying what was learned; patching again inside the polluted context compounds the error.
- `/compact` always with a hint of what to keep (files touched, decisions, test commands) — unguided auto-compact drops the load-bearing details.
- Long or autonomous sessions: keep a `HANDOFF.md` (tried / worked / failed / next). `/checkpoint` writes it, `/resume-handoff` reads it.
- Prefer a fresh session for implementation once a plan is final — planning residue biases execution. Same reason UI polish past ~2 rounds in one session breeds slop.

---

## Next-step hints & chain contract

- End-of-skill: close with `→ Next: /<skill> — <one-line why>` (one default plus up to 2 alternatives when genuinely ambiguous), tailored to what actually happened. Skills with richer handoff menus (explore, analyze, enhance, spawn) keep them. Skip when the work is terminal.
- **Auto-chain only when all three hold:** (1) the next step is part of the skill's own documented chain, (2) the user's original request already covers that outcome, (3) no new user decision is needed in between. Otherwise hint and stop. Commit/push is never auto-chained.
- Worker subagents (`osf-*`) never chain — they end reports with `Recommended next: <step> — <why>` so the orchestrator can surface or act on it.

---

## Failure → rule

When a mistake repeats (≥2 times) or a user correction lands, prefer the cheapest durable home for the fix: automatic memory first, then a project-level `CLAUDE.md`, and only then the global one — every global line is paid on every request thereafter. Wait for the second occurrence before writing anything.

Default to a positive directive ("do Y instead"). Reserve **NEVER X — because Y** for high-stakes boundaries: positive framing is followed more reliably, and prohibitions spend their force when overused.

Sibling tool, scoped to one skill: an anti-rationalization table (`| Rationalization | Reality |`) listing the excuses used to skip a step next to why each is wrong. Rules stop repeat mistakes across sessions; those tables stop them mid-task inside the running skill. Use them only where a step is genuinely high-stakes — on instruction-following models they are a tax everywhere else.

---

## Standalone-checkout boundary — the case

Learned 2026-07-16: `xood-frontend` CI broke on `@contracts/*` → `../xood-backend/src/contracts`. Local and Docker-dev builds were green because the sibling repo happened to exist on the machine; GitHub Actions produced TS2307 ×3 plus a TS7006 cascade on the first main deploy. Fix: vendor type-only mirrors into `src/lib/consent-contracts.ts` and drop the alias plus the dependency it dragged in.

Verify self-containment cheaply: after removing the alias, a green local build **is** the proof — the compiler has no path out of the repo.


## Composable logic under one arbiter (Master 2026-09-08)

**Principle.** Prefer many small deterministic algorithms that complement each other over one monolithic rule; a good algorithm may ship before it is switched on. Both halves are senior practice only with a spine:

- **One ordered arbiter.** Every reader/guard/gate registers in a single table with explicit precedence (an array of `{name, when, verdict}` evaluated in order, or a state machine) so that one request yields exactly one outcome and "who wins" is readable in one place. A reader added as an `if` inside a 10k-line service has an implicit precedence (its line number) — that is how two lanes produce contradicting bubbles in the same turn.
- **Optional ≠ dead.** A path that is not live yet is acceptable only when (a) a flat feature flag reaches it, (b) tests lock both flag states, (c) the receiver of its output exists. Otherwise it is speculative abstraction and the simplicity ladder (§6) removes it.
- **Fixture-locked.** Each algorithm is pure and tested against recorded real inputs (server shapes, DB rows), never hand-made fixtures.
- **Strangler, not big-bang.** Migrating an existing service to the arbiter table happens one reader at a time behind the same tests; never a rewrite while QC/autopilot lanes are open on that file.

**The miss that shaped this (xood /chat, 2026-09).** ~30 readers grew incident-by-incident inside `chat-conversation.service.ts` (13k lines) with two independent turn-close lanes; QC repeatedly measured contradictory pairs in one turn (picker then "no match", server sentence beside model prose). The readers were individually correct; the missing piece was the ordered arbiter.

## Instruction classes a gate must own — and where a human stays (Master 2026-09-11)

**Compliance-gap class.** "Do X before you write" instructions to an agent (a structural query before an edit, a lookup before a write-style call, a read before an action) fail silently and are undetectable from the agent's text. Three independent 2026 studies measured it: The Compliance Gap (arXiv 2605.01771 — 0% actual compliance under default framing while the agent verbally agrees; blinded text-only raters caught 0 of 15), MANTRA (arXiv 2605.06334 — missing-required-call + missing-anchor = 76% of tool-use compliance failures), omission-constraint decay (arXiv 2604.20911 — suppressive "don't / first do" constraints decay with context depth while additive ones hold). Our own measurement (2026-09-10, four osf-apply lanes, 0 GitNexus calls each despite the instruction) is the same phenomenon. **Rule:** never fix this class by re-wording a brief. It is owned by a hook or an orchestrator gate that reads the tool-call log (`subagent-tool-census`); the brief line stays only as documentation of intent. All three are preprints — cite the convergence, not the decimals.

**Self-reports.** 23–27% of real coding-agent failure episodes are inaccurate self-reporting (arXiv 2605.29442); an LLM judge grading a "done" claim reaches AUROC ≤ 0.65 where a structural check of the actual state reaches 0.83–0.95 (arXiv 2606.09863). Ground truth — box state, the test runner's summary line with ran/skipped counts, the git diff, the DB row — is the only accepted evidence of completion. Never a paraphrase of the agent's claim, never a second LLM reading the claim.

**Three human stops in unattended chains (decided 2026-09-11).** (1) A validation or business question, (2) a one-way door, (3) an acceptance GAP (a segment × basic-job journey with no requirement) are never self-resolved by a ★Recommend: the chain ends with a `⏸ … needs Master` marker and the questions with options. Engineering two-way-door choices still self-resolve, tagged `[AUTO-RESOLVED]`. Reason: a ★Recommend on a validation question is a guess by the same model family that wrote the plan (self-preference up to 50%, arXiv 2604.06996), and validation — "are we building the right thing" — belongs to the people closest to users (Boehm; Anthropic evals 2026).

**Reviewer calibration.** A reviewer asked to find gaps reports some even when the work is sound (Anthropic best-practices; Google's review guide keeps "Nit:" for exactly this). Because every filed finding must reach an end-state, SUGGESTIONs are filtered at RAISE time (observable benefit + in scope + not a satisfied convention), not dropped later.

**Acceptance readback — the validation step the chain lacked.** `/proposal` step 8 spawns `osf-acceptance-reader` (perspective-based reading — ISTQB CTFL 2018 v3.1 §3.2.4; Basili 1996: ~30% more defects, no meeting needed, no effect when the reader already knows the document — hence never the author) to produce `content/acceptance.md`; `/discuss-chain` critiques the rows, `/apply` re-maps them before slice 1, `qc-hunt` runs them under the severity rule "a basic job failing in ≤ 3 turns is SEV1 regardless of mechanism count". The miss that shaped it: xood /chat 2026-09-04 — a NEW seller asked for 10 Mid-Autumn captions, took the first card, was forked into a video script, got zero captions; the pieces had surfaced across QC rounds R5/R13/R17 as three separate P1s. Research ledger: `agent-kit/docs/research-pipeline-mindset-2026-09-11.md`.
