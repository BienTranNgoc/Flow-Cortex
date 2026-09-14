# Execution Harness — full reference

Companion to CLAUDE.md §12 (on-demand references). Read this when an implementation will span MULTIPLE sessions / context windows after a spec exists (post-`proposal`, during `apply`). The front of the pipeline (enhance → plan → discuss/probe-loop → proposal) is about *deciding what to build*; this file is about *staying on the spec while building it* without quality decaying as the window fills.

Skip for one-shot tasks that finish in a single context. Use when: the task has a `tasks.md` checklist, touches many files, or you expect to `/compact` or `/clear` mid-implementation.

Distilled from Anthropic's *Effective harnesses for long-running agents* (Nov 2025) and *Effective context engineering for AI agents* (Sep 2025). The patterns are adapted to this kit (OpenSpec `proposal/tasks.md` + GitNexus + the references/ lenses).

## 1. The two-pass shape (initializer → coding loop)

Long runs fail when the agent treats a 30-step task as one monolithic action. Split it:

- **Initializer pass (once, at start):** make the environment *resumable before writing feature code*.
  - Baseline green: deps installed, build passes, existing tests pass. A red baseline poisons every later "did I break it?" check.
  - Materialize the task list: `proposal/tasks.md` checkboxes ARE the kit's "feature-list JSON" (the harness article's progress artifact). One checkbox = one coherent slice.
  - Record the resume point in `HANDOFF.md` (§8): what's done, what's next, the test command, decisions made.
- **Coding loop (per session):** pick ONE slice → implement → verify green → check the box → commit → update HANDOFF.md. Never carry two half-done slices across a `/compact`.

Rule: **a slice is the unit of progress, not the whole task.** If a slice can't be verified independently, it's too big — split it in `tasks.md`.

## 2. Context rot — keep the main window lean

"Context rot" = output quality degrades as the window fills, *before* the hard limit. The fix is not a bigger window; it's putting less in it.

- **Delegate heavy reads to subagents.** Sweeping many files = `Explore`/`osf-analyze`; the conclusion returns, the file dumps don't. The main loop keeps the *decision*, not the raw bytes. (This is why discovery-economics caps compass calls — same principle, execution side.)
- **Just-in-time retrieval.** Pull the spec section / file you need *now*, not the whole spec up front. Re-reading a doc later is cheap (sdd-cache 304s); a preloaded window that rots is not.
- **Structured note-taking before compaction.** Before `/compact`, write the state into `HANDOFF.md` — durable memory the compaction can't blur. Then `/compact` WITH a keep-hint (files touched, decisions, test cmd). Never trust unguided auto-compact for long work.
- **One concern per context.** `/clear` between unrelated slices. Planning residue biases execution — same for a previous slice's dead context.

Trigger to act: at ~60% window, stop and choose — finish the current slice and `/clear`, or note-take + `/compact`. Don't push on into the rot zone.

## 3. Verify-as-you-go (tighten the inner loop)

The Agent SDK loop is *gather context → act → verify* — the verify step is per-slice, not just at the end.

- **Define the check before the code** (§4): the test or command that proves the slice. "Add validation" → write the failing test first, then pass it.
- **Mark done only on green.** A checked box in `tasks.md` must mean a verification passed, never "looks right." (See anti-rationalization below.)
- **`detect_changes` before every commit** — hook-enforced in indexed repos (CLAUDE.md §4). Depth-2 impact trace before modifying exported symbols (CLAUDE.md §4).
- **`/verify` against the spec before `/archive`** — the kit's final coherence gate; it catches slices that drifted from the proposal.

## 4. When to spend the heavy planning path (judgment, not a step)

The front-loaded critique layers are powerful but they fill context — pick by task weight, don't run all of them reflexively (§2 Simplicity):

| Task shape | Critique before proposal | Pipeline |
| --- | --- | --- |
| Small, well-understood, reversible | none, or `/discuss` (1 pass) | `/autopilot` Light |
| Medium, some unknowns | `/discuss` only | `/autopilot` Verified |
| Large / ambiguous / hard to reverse / security boundary | `/probe-loop` only (it already includes discuss + research + pre-mortem + verdict) | `/autopilot` Full |

`/discuss` and `/probe-loop` overlap in purpose — running both sequentially is belt-and-suspenders that burns the window you'll need for execution. Choosing the tier *is* the skill; adding more pre-steps is not.

## 5. Anti-rationalization

| Rationalization | Reality |
| --- | --- |
| "I'll keep all the context, I might need it" | That's context rot. Delegate the read, keep the conclusion (§2). |
| "The whole task is one big edit" | Unverifiable = too big. Split into slices in `tasks.md` (§1). |
| "Box checked, it looks right" | A checked box means a check *passed*. No green, no check (§3). |
| "Auto-compact will remember" | Unguided compaction blurs state. Note-take to HANDOFF.md first. |
| "Both discuss and probe-loop to be safe" | They overlap; doubling them starves execution of window (§4). |
| "I'll run /verify at the very end only" | End-only verify finds drift after it's expensive. Verify per slice (§3). |

## 6. Sources

- Anthropic — *Effective harnesses for long-running agents* (Nov 26, 2025): https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents
- Anthropic — *Effective context engineering for AI agents* (Sep 29, 2025): https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents
- Anthropic — *Building agents with the Claude Agent SDK* (Sep 29, 2025): https://claude.com/blog/building-agents-with-the-claude-agent-sdk
