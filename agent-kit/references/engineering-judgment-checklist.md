# Engineering Judgment Checklist — proportional deliberation, and the cost borne by others

Reference for the osf kit. The kit's other lenses answer *"is this correct?"*. This one answers
the two questions above correctness: **how much deliberation does this decision deserve**, and
**who pays for it after it ships**. Written 2026-08-23 after an audit measured seven judgment
dimensions at zero hits across the whole default planning path (`/explore`, `/feat`, `/fix`,
`/proposal`, `/apply`, `/refactor`, `/chore`) — they existed in the kit only inside *opt-in*
critique skills (`/probe-loop`, `/discuss-chain`, `/review`). Judgment that only runs when
someone remembers to ask for an audit is not judgment the pipeline has.
<!-- CUSTOM:judgment-lens 2026-08-23 (Master) — re-apply if the kit is reinstalled -->

> **When the osf kit pulls this in:**
> - **`/explore` / `/feat`** — §1 runs on **every** plan and *gates how much of the rest runs*.
> - **`/proposal`** — §2/§3 become numbered requirements and separate tasks; §5 decides whether a
>   decision record is owed. See §10 (build-lens baseline).
> - **`/apply`** — §3 sequencing, §4 slice ceiling.
> - **`/review`** — §2 shared-artifact findings, §6 pre-deletion gate.

---

## 1. One-way or two-way door — classify FIRST; it sizes everything else

*Bezos, 2015 Amazon shareholder letter.* A **two-way door** is reversible: decide at roughly 70%
of the information you wish you had and move. A **one-way door** is not: it earns deliberation,
a second reader, and a written record.

**"Can I roll back the code?" is the wrong test** — it is the mistake that makes engineers
classify a schema change as reversible. Use these markers instead:

- **Data has been written in the new shape.** Reverting code does not un-write rows.
- **An external party is now sending you traffic** against a contract — a webhook registered, a
  callback URL handed over, a partner integrated.
- **A client version is in the wild** you cannot force-upgrade.
- **A published API/schema has consumers outside your deploy control** (in this workspace:
  anything in `@contracts/*`, consumed by BE *and* FE — a plan-schema change is a one-way door
  by definition).
- **A price, a legal commitment, or a public announcement** went out.

The failure mode is symmetric and both halves are common: agonising over reversible tool picks
while pushing a genuinely irreversible schema shape past a single reviewer.

*This marker list is a practitioner synthesis, not a named theorem — a good checklist, not a proof.*

## 2. Blast radius — shared artifact, or single consumer?

**This section exists because it is the documented failure mode of AI coding agents specifically,
which is what runs this kit.** Cortex's 2026 benchmark reports incidents-per-PR up ~23.5% and
change-failure-rate up ~30% correlated with AI-assisted adoption; Google's own DORA 2025 found a
*negative* relationship between AI adoption and delivery stability. The stated mechanism: an agent
optimises for **local correctness inside one repo**, while the cross-repo consumer graph *"lives
outside that boundary — it isn't in the training data."*

**The gate: before changing anything that could have more than one consumer, ENUMERATE the
consumers. Do not infer them.** The tools are already here and free:

| Question | Tool |
|---|---|
| Who depends on this exported symbol? | `mcp__gitnexus__impact` (trace depth-2 before modifying) |
| Who calls this API route? | `mcp__gitnexus__api_impact` / `route_map` |
| Did the response shape drift? | `mcp__gitnexus__shape_check` |
| Cross-repo callers | **grep the other repo** — `route_map`'s `consumers` field is EMPTY when the caller lives in another repository (measured: 92/92 routes on one backend). Empty ≠ no consumers. |

Owning the tool is not owning the judgment. The gate is that a shared-artifact change **stops and
lists its consumers before proceeding** — not that a tool exists should the agent think of it.

## 3. A data-shape change is 4–5 deploys, not one

*Fowler, **Parallel Change** (expand / migrate / contract), 2014.*

"Add a NOT NULL column" is actually:

1. Add the column **nullable** → deploy
2. **Dual-write** old + new → deploy
3. **Backfill** existing rows
4. Add the **NOT NULL** constraint → deploy
5. **Drop the old column** → deploy ← *the contract phase*

Fowler's own warning is about step 5: **the contract phase must actually execute.** A half-migrated
state with two live representations is worse than where you started, and "cleanup later" is
exactly how it becomes permanent. In this kit that means the contract phase is **its own checkbox
in tasks.md**, never an implied follow-up (and never a `[DEBT]` item the audit has to catch).

**Ordering rule.** At every moment of a rolling deploy the schema must satisfy **both** the old and
the new code:

- A migration that **adds** capability runs **before** the code deploy that uses it.
- A migration that **removes** capability runs **after** the code deploy that stopped using it.

A `tasks.md` that pairs a migration with its code change in one step has not stated an order.
That is a finding, not a detail.

**This section is the *shape* of a data change. The *execution* — locks, batched backfills,
`NOT NULL` and index mechanics, what `down()` can actually undo, and this workspace's verified
migration traps — is `agent-kit/references/migration-safety-checklist.md`. Pull it the moment a
plan implies a schema or data change; getting the shape right does not save a migration that locks
a hot table.**

## 4. Slice ceiling — the number, not "keep it small"

*Cohen / Cisco, published in* Best Kept Secrets of Peer Code Review *(SmartBear, 2006) — ~2,500
reviews over 3.2M LOC, still the largest published study of its kind.* Defect detection falls off
sharply past roughly **200–400 lines** per review; the 100–300 band catches several times more per
review. Review speed under ~300–500 LOC/hour correlates with more defects found.

**So a task whose diff will plausibly exceed ~400 lines gets split before it is written**, not
apologised for afterwards. This is the concrete trigger for `/apply`'s existing slice protocol.

*Citation hygiene: the anchor study is 2006, pre-modern tooling. The widely-repeated "PRs over
1,000 lines see 70% lower detection" figure comes from a vendor blog with no published methodology
— do not repeat it as fact. DORA's finding that PR size and review time rise sharply under AI
adoption (and that 31% more PRs merge with **no review at all**) is directional support only:
survey-based, and its magnitudes swung 2–4× between the 2025 and 2026 editions.*

## 5. Decision record — the trigger, not the ceremony

*Nygard 2011; Fowler's bliki summary.* Write one when **both** hold:

1. the decision is a **one-way door** per §1, **and**
2. a competent person could reasonably have chosen the alternative.

Contents: **decision · context · alternatives considered · consequences · what would make us
revisit.** Never edited once accepted — superseded by a later record, so the history survives.

In this kit `design.md` already plays this role for an OpenSpec change. **The gap is a decision
made inside a `/fix` or `/feat` slice that never produces a `design.md`** — those need the same
five lines somewhere durable, or the "why" gets reverse-engineered from `git blame` months later.

Both failure modes are real and symmetric: ceremony for trivial reversible picks (so nobody reads
them), silence on the consequential ones (so the context is lost).

## 6. Before removing something — open the fence first

Before deleting, "simplifying", or bypassing existing logic that is not obviously dead: **state
why it was added** — from git history, the commit message, the ticket, the original PR. Not from
inference. If you cannot state it, you have not opened the fence yet, and this is a finding.

*Chesterton's Fence (1929) is a folk heuristic re-derived independently across the industry, not a
technical paper — cited for practical value, not academic weight. It earns its place because it
catches a documented LLM failure mode: confidently "cleaning up" code whose purpose was never
checked.* Note the boundary with CLAUDE.md §6: pre-existing dead code is **mentioned, not deleted**
— this section governs code you have a reason to touch.

## 7. A new pattern or a new dependency decides for the whole codebase

Two related brakes, both above the simplicity ladder rather than inside it:

**Precedent.** When a change introduces a genuinely new approach where the codebase has no blessed
pattern, **say so out loud** rather than deciding silently. The first instance becomes the example
the next contributor copies — and here the next contributor is frequently another LLM, which
imitates the nearest example more readily than a human reviewer who would ask *"why is this one
different from the other three?"* (Ladder rung 2 covers the opposite case — reuse what exists.
This covers the case where nothing exists yet.)

**Innovation tokens** *(McKinley, ~2015)*: a team has roughly three; every unconventional choice
spends one. Before adding a new dependency or paradigm — (1) imagine solving it with what is
already here; (2) if you cannot, write down exactly what makes the current stack prohibitively
expensive for this; (3) commit to retiring the overlapping old thing on a date. **Boring ≠
inferior** — boring means the failure modes are already known. Ladder rung 3 already blocks a new
dep when stdlib does the job; the marginal value here is the **running count**, because three
novel choices interacting is far worse than three novel choices separately.

## 8. The seniority failure mode — check the motive, not only the artifact

Senior engineers over-engineer **more** readily than mid-level ones, precisely because they can
justify the complexity with real experience. *"I've seen this bite a team"* is sometimes true and
sometimes a rationalisation — seniority does not make the rationalisation rarer, it makes it
**more convincing**.

The simplicity ladder inspects the diff. This inspects the reasoning that produced it: **would
this design look the same if nobody were going to see it?**

*(Resume-driven development, gold-plating, analysis paralysis are convergent folk terms with wide
informal consensus, not sourced theory. Named here for recognisability, not authority.)*

## 9. What NOT to encode from this literature

- **DORA's numeric benchmarks** — survey-based, self-reported, correlational; magnitudes swung
  2–4× between the 2025 and 2026 editions. The *direction* (smaller batches ↔ lower failure rate)
  is reasonably triangulated across years; **no specific threshold is**.
- **"Elite performers deploy X times per day"** as a target — gameable in both directions and
  context-free.
- **Any causal claim from *Accelerate*** — structural equation modelling on self-report is
  standard for the field, but it is not experimental.

## 10. Build-lens baseline (what `/proposal` requires)

- [ ] The plan states **one-way or two-way door**, naming the §1 marker that decided it.
- [ ] A one-way-door change **enumerates its consumers** with the §2 tools — not by assumption,
      and not by reading an empty cross-repo `consumers` field as "none".
- [ ] A change to a persisted shape that already has rows carries **expand / migrate / contract as
      separate tasks**, and states the **migration-vs-deploy order** (§3).
- [ ] The **contract-phase task exists as its own checkbox** — not implied, not "cleanup later".
- [ ] No planned task will plausibly exceed **~400 lines** of diff (§4).
- [ ] A one-way-door decision that had a real alternative carries its **record** (§5).
- [ ] Any deletion of non-obviously-dead code **cites why it was added** (§6).
- [ ] A **new dependency or new pattern** is named as such, with the §7 justification.
