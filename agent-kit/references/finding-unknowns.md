# Finding Unknowns Lens

Map-vs-territory reference for the osf kit. Distilled from Thariq's "the map is not the
territory" (@trq212, 2026) and the community `finding-unknowns` skill (github.com/oso95/finding-unknowns)
— reframed to route INTO the osf kit instead of duplicating it.

> **When to pull this in (load on-demand — skip for simple, well-specified tasks):**
> a task is ambiguous, long-horizon, high-stakes, or in a domain/codebase area you don't
> know well ("I'll know it when I see it"-shaped). It gives a shared vocabulary (the
> unknown-type 2×2) + a routing table into existing osf skills. It is a **lens, not a
> skill** — no ceremony, most tasks need zero or one technique.
>
> **Intended pull points** (wire per-skill for auto-trigger — see the routing table):
> `explore` / `/proposal` before finalizing a plan (extends CLAUDE.md §4 zero-fog);
> `/probe-loop` · `/discuss-chain` to name *which kind* of unknown a finding is;
> `/grill-me` for the interview priority rule; `autopilot` / `apply` for
> conservative-and-continue; the **Quiz gate** post-implementation (net-new — no skill
> covers it yet).

## The core idea

The prompt is a **map** — a compressed representation of work that lives in the
**territory** (the codebase, the design, the real constraints). The gap between them is
made of **unknowns**, and every unknown hit mid-task forces a guess about intent. On a
capable model, unresolved unknowns — not capability — are the quality bottleneck on
long-horizon work: when a long task comes back wrong, the cause is rarely "couldn't do
it", it's that nobody found the unknowns until they were expensive. Each technique below
is a *cheap probe* bought to avoid *expensive rework*.

## The four kinds of unknown

| | User is aware of it | User is not aware of it |
|---|---|---|
| **User knows it** | **Known known** — it's in the prompt. | **Unknown known** — tacit taste so obvious they'd never write it, but they spot a violation instantly ("that's not what I meant"). |
| **User doesn't know it** | **Known unknown** — a gap they can name. | **Unknown unknown** — not considered at all; may not know what "good" even looks like. |

Naming the *type* of unknown picks the technique. Most tasks have one dominant type.

## Why over- and under-specifying both fail (a caution for THIS kit)

Instruction-following is a dial. **Too specific** → the agent follows the letter even when
the territory says a pivot serves better. **Too vague** → it fills gaps with generic
best-practice that may not fit this codebase/taste. This kit's CLAUDE.md sits at the
*specific* end by design, so the counterweight matters: **preserve pivot-authorization**
(CLAUDE.md §16) so a beneficial course-change is never suppressed by a prescriptive rule.

## Operating principles (these are already osf rules — this names them)

1. **Search before you ask.** Resolve every unknown you can reach by reading code/web
   yourself; spend the user's attention only on unknowns that live in their head —
   intent, taste, priorities. This *is* CLAUDE.md §1 (verify-don't-guess) + §16 (probe
   verifiable ambiguity, ask only preference/intent).
2. **Make unknowns reactable.** People react faster than they specify. Convert a silent
   guess into a named assumption, a rendered variant, a mock with fake data, or a plan
   that leads with its most contestable decision. When the env can render HTML, an
   **Artifact** is usually the best medium; otherwise clear markdown.
3. **Source code is the richest reference.** When the user points at a lib/module/site
   they like, read the *implementation*, not the screenshot. (CLAUDE.md §5; `/clean-room`.)
4. **Surface, decide, log — don't stall.** Mid-implementation unknown + user unavailable
   → pick the conservative option, log the deviation, keep going. A written-down deviation
   is recoverable; a silent guess is not. (Fits autonomous-mode execution.)
5. **Finding unknowns can change the problem.** The highest-value outcome is discovering
   the user should solve a *different* problem, or that the result could be far better than
   imagined. Say so — that's the loop working, not a scoping failure. (CLAUDE.md §16.)

## Routing table — unknown → technique → osf path

| You observe | Dominant unknown | Technique | osf path |
|---|---|---|---|
| New to the domain / this codebase area; doesn't know what to ask or what good looks like | Unknown unknowns | **Blindspot pass** | `/probe-loop` (audit) · `/discuss-chain` (research each) · `/explain` (learn existing code). *Net-new flavor:* pure "teach me this domain BEFORE any plan" — run an explain/probe pass framed *"find my unknown unknowns so I can prompt you better"* |
| Taste / visual / UX criteria only judgeable by reacting; scope unsettled | Unknown knowns | **Brainstorm & prototype** | `/design-discovery`, `/design-brief`, `/taste-skill`, `/ui` + a throwaway **Artifact** mock (fake data, N divergent directions) to react to BEFORE wiring a backend |
| User can name their open questions but hasn't answered them | Known unknowns | **Interview** | `/grill-me` — prioritize questions whose answer *changes the architecture*, one at a time |
| User can point at an example but can't describe it | Hard-to-verbalize want | **Reference code** | `/clean-room` (port external repo) · `/research` (precedent) · GitNexus micro |
| Ready to build; mid-flight guesses would be expensive | Residual unknowns before commitment | **Implementation plan** | `/proposal`, `/feat`, `explore`/plan mode — **lead the plan with high-veto decisions** (see below) |
| Building now; the plan is meeting reality | Unknowns discovered in flight | **Implementation notes** | `execution-harness.md` / `autopilot` / `apply` — keep a `Deviations` log (see below). `HANDOFF.md` is the *cross-session* baton; this is the *intra-run* journal |
| Work done; other people must understand and approve it | The reviewers' unknowns | **Pitch & explainer** | `/docs` + **Artifact** (package spec + prototype + notes; lead with the demo) |
| Work done; user hasn't internalized the diff | The user's unknowns about the change | **Quiz** | `/quiz` — report of the change (HTML Artifact when possible) + a quiz the user must pass before merge (see below) |

When the user says a technique's literal name — "do a blindspot pass", "interview me",
"quiz me on this change" — skip diagnosis and go straight to it.

## The three net-new pieces (self-contained here; Quiz now also shipped as `/quiz`)

**Plan-ordering rule** (for `explore` / `/proposal`): a plan exists to be *reacted to*.
Order it by veto-probability, not by execution order — put at the TOP, in this order:
(1) data-model / schema changes, (2) new or changed type interfaces / public contracts,
(3) user-facing UX flows. Push mechanical refactoring, moves, and boilerplate to the
bottom under a "trust me on this part" heading. The user's scarce attention goes to what
they're most likely to change.

**Deviations log** (for autonomous runs): keep **`openspec/changes/<name>/implementation-notes.md`**
— the canonical path, alongside `verify-fixes.md` — with a running `## Deviations` section.
Outside an OpenSpec change, the deviations go in the final report to the caller instead. Each entry: *what the plan said → what
the territory forced → the conservative choice made → why*. It is the learning artifact
for the next attempt — NOT the HANDOFF (that is cross-session resume state).

**Quiz gate** (post-implementation, before merge): after a long session a diff read gives
only shallow understanding, because behavior depends on existing code paths. Produce a
report (HTML Artifact when possible) covering what changed, the intuition, and which
existing paths the behavior now depends on — then a short quiz the user must pass. Merge
only on a clean pass. This is the kit's *anti-slop for understanding*, complementing
`/review` and `/verify` (anti-slop for code). **Shipped as `/quiz`** — read-only; it
recommends the merge, never performs it (§12).

## Skip-guard (hard rule)

Most tasks need **zero or one** technique. A simple, well-specified task — "rename this
function", a typo, a one-line fix — deserves a direct answer, not a framework. Running the
full arc on trivial work is worse than not having this lens. Match one technique to the
dominant unknown and the phase; skip the rest. (CLAUDE.md §2.)
