# Agent orchestration — patterns, budgets, failure modes

On-demand lens for skills that decide **how much machinery to spend** on a task: how many agents, sequential or parallel, how deep to loop, and when to do the work inline instead.

Sources: Anthropic, *Building Effective Agents* and *How we built our multi-agent research system*. Numbers below are theirs, not estimates.

---

## 1. The budget question comes before the pattern question

**Cost multipliers, measured:** an agent uses ≈**4×** the tokens of a chat turn; a multi-agent system ≈**15×**. Token usage alone explains ~**80%** of the variance in task performance — so spending more genuinely buys more, which is exactly why an unbudgeted system overspends silently.

A task earns multi-agent machinery only when its value justifies that multiplier. The stated good fit is **heavy parallelization over information that exceeds one context window**. The stated poor fits:

- Tasks where every agent needs the **same shared context**
- **Heavy interdependencies** between subtasks
- **Most coding work** — too few genuinely parallelizable subtasks
- Anything needing real-time coordination between agents

The last one matters for a coding kit: reach for fan-out when the work is *discovery over disjoint areas*, not when it is *implementation of one coherent change*.

---

## 2. Effort scaling — how many agents

Embed the ladder explicitly rather than letting the orchestrator guess; guessing is what produces fifty subagents for a one-line question.

| Task shape | Agents | Tool calls |
|---|---|---|
| Simple fact-finding ("who calls X", "does this field exist") | **0–1** — usually inline in the main loop | 3–10 |
| Direct comparison across a few known items | **2–4** | 10–15 |
| Complex open research over disjoint areas | **10+** | scales |

**Zero is a real answer.** If the main loop can settle the question with a few direct tool calls, an agent is a round-trip that buys nothing and loses detail on the way back.

---

## 3. Pattern catalog — pick the cheapest that fits

Add complexity **only when it demonstrably improves outcomes**. For many tasks a single well-retrieved call is enough.

| Pattern | Use when | Do **not** use when | Failure mode |
|---|---|---|---|
| **Prompt chaining** | Fixed sequential steps; accuracy over speed | Task needs dynamic adaptation | Rigidity on unexpected variation |
| **Routing** | Distinct categories, classification is reliable | Categories blur | Misroute → specialized prompt on wrong input |
| **Parallel — sectioning** | Subtasks genuinely independent | Hidden dependencies | "Independent" tasks weren't; results are wrong |
| **Parallel — voting** | Need confidence from several perspectives | Errors are correlated | Repeated calls don't fix a shared blind spot |
| **Orchestrator-workers** | Subtasks unpredictable, must be decomposed at runtime | Structure is known up front (use sectioning) | Over-decomposition; missed cross-worker dependencies |
| **Evaluator-optimizer** | Clear criteria + iteration measurably helps | Single-pass is enough | **Circular feedback; unproductive loop** |
| **Autonomous agent** | Open-ended, step count unknown, guardrails exist | Predefined workflow, low error tolerance | Compounding error across turns |

**Sequential vs parallel is decided by data dependency, not by taste.** Stages that consume each other's output run sequentially; only genuinely independent stages fan out. Intra-stage parallelism is unaffected by an inter-stage dependency.

---

## 4. Loop discipline (evaluator-optimizer)

Any "iterate until it's good" loop needs a stated exit, or it becomes the documented failure mode. Every such loop declares up front:

- **A hard iteration cap**, and what happens on hitting it (report as unresolved — never silently return the last draft as final).
- **A convergence test**: what specifically must change for another round to be worth it. "Still not perfect" is not a convergence test.
- **A no-progress rule**: two consecutive rounds that surface nothing new end the loop. Diminishing returns are the signal, not a reason to push harder.

---

## 5. Briefing a subagent

Vague briefs are the top cause of duplicated and misdirected work — *"research the semiconductor shortage"* sent several agents down the same path. Every brief carries four fields:

1. **Objective** — the decision this work must inform
2. **Output format** — the shape expected back
3. **Tool guidance** — which sources/tools apply, which to skip
4. **Boundaries** — the scope, and explicitly what belongs to a sibling agent

**Artifact bypass.** A subagent that produces bulk output writes it to a file and returns the **path plus a short summary**, not the content. Passing full content up through stages is a game of telephone: detail degrades at every hop and the orchestrator's context pays for all of it.

---

## 6. Search and probe strategy

- **Broad to narrow.** Start with short, wide queries to see what exists, then progressively narrow. Starting narrow finds only what you already suspected.
- **Parallel tool calls.** Issue independent tool calls together in one message (3+ where the work allows) rather than one at a time.
- **Prefer specialized tools over generic ones** when both could answer — a purpose-built lookup beats a generic search on both precision and tokens.

---

## 7. Guardrails, not rigid rules

Prefer heuristics and frameworks over absolute constraints, plus explicit guardrails against spiraling behavior. Rigid rules break on the cases they didn't anticipate; a heuristic with a stated failure mode degrades gracefully.

**State and error compounding.** Long-running work needs resumption from a checkpoint rather than a restart, and errors handled where they occur — a compounding error across many turns is the defining risk of the autonomous pattern.

---

## 8. Evaluating agent output

Judge with a rubric, not a vibe. Dimensions that transfer to any research or analysis leg:

- **Factual accuracy** — do claims match their sources?
- **Citation accuracy** — do cited sources actually support the claims?
- **Completeness** — is every requested aspect covered, or explicitly marked N/A?
- **Source quality** — primary sources preferred over aggregators?
- **Tool efficiency** — was the tool count and selection appropriate?

Small samples work: effect sizes are usually large enough to detect with **~20 cases**. For state-mutating tasks, evaluate the **end state**, not each intermediate step.
