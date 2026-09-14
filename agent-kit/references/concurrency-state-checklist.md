# Concurrency & State-Integrity Checklist

Case-generating reference for the osf kit. Written 2026-08-22 after a payment plan
passed `openspec validate` + the Debt & Coverage Audit clean, yet shipped a flow where
a user could open two tabs and create two *distinct* pending transactions for the same
subscription — the second one perfectly valid, perfectly idempotent, and perfectly wrong.
<!-- CUSTOM:concurrency-lens 2026-08-22 (Master) — re-apply if the kit is reinstalled -->

> **When the osf kit pulls this in:**
> - **`/proposal`** — a change that writes state carrying a *lifecycle* (anything with a
>   status column, a pending phase, money, quota, or entitlement) makes the invariants below
>   numbered requirements (`R-xxx`). See §7 (build-lens baseline).
> - **`/explore` / `/feat`** — the Stress-test step walks §1 to *generate* cases, not to
>   confirm the ones already mentioned.
> - **`/review`** — flags a check-then-act on shared state with no DB-level guard.
>
> The kit's existing lenses do NOT cover this: the coverage audit only catches cases someone
> already said out loud, and observability tells you afterwards that it happened.

## 1. The four questions that generate the cases

Ask them in this order, out loud, for every state-writing operation. They are ordered by how
often they turn up something real.

1. **Two at once, same user** — the user opens a second tab / double-taps / has a phone and a
   laptop. Do they now hold two live objects? Which one wins? What happens to the loser?
2. **Two at once, different actors** — user + admin, user + scheduled job, user + webhook from
   a provider. Whose write lands last, and does last-write-wins lose information?
3. **The gap between check and act** — between `if (noPendingOrder)` and `INSERT`, another
   request runs the same `if`. Both pass. What stops both from inserting?
4. **Arriving out of order / late** — the callback for attempt #1 arrives *after* attempt #2
   was created. A retry lands after a cancel. A webhook is delivered twice, 40 minutes apart.
   Does the late message resurrect dead state?

If a question has no answer in the plan, that is a case, not a detail.

## 2. Idempotency ≠ single-active. Do not let one stand in for the other

The trap that motivated this file. They solve different problems and a plan that has one
often *feels* covered for the other:

| | Idempotency key | Single-active invariant |
|---|---|---|
| Answers | "the SAME request arrived twice" | "TWO DIFFERENT requests are both alive" |
| Keyed on | a request id the client reuses on retry | the subject (user, account, resource) |
| Beats | network retry, double-click, webhook redelivery | second tab, second device, second session |
| Fails against | two tabs — each mints its own fresh key, so both are "first" | a genuine retry — it looks like a second actor |

A flow that takes money, holds a slot, or grants entitlement needs **both**, and the plan must
say so separately. Writing "we use idempotency keys" and considering concurrency handled is the
exact failure this checklist exists to stop.

## 3. Name the invariants — as sentences, before any schema

Invariants are what turn vague "handle concurrency" into testable requirements. Write each as a
sentence that a DB constraint or a test could enforce:

- *"A user has at most one payment attempt in a non-terminal state."*
- *"The sum of allocated quota never exceeds the purchased quota."*
- *"An entitlement is granted only from a settled payment, and exactly once."*
- *"A cancelled attempt never returns to pending."*

Then, for each: **where is it enforced, and what happens to the request that violates it?**
An invariant with no enforcement point is a comment, not a rule.

## 4. Enforce at the deepest layer that can say no

Ordered strongest → weakest. Pick the deepest one the operation can reach; the layers above it
are for *message quality*, not for correctness.

1. **DB constraint** — partial unique index (`UNIQUE (user_id) WHERE status IN (…open…)`),
   exclusion constraint, `CHECK`. Survives every race, every process, every deploy. Cannot be
   bypassed by a new caller written next year.
2. **Atomic conditional write** — `UPDATE … WHERE status = 'pending'` and branch on rows-affected;
   `INSERT … ON CONFLICT DO NOTHING`; a version column (optimistic lock) rejected on mismatch.
3. **Row lock in a transaction** — `SELECT … FOR UPDATE` on the parent, then act. Correct, but
   its scope is one transaction: everything must go through the same path.
4. **Distributed lock (Redis etc.)** — for cross-service work that has no shared row. Needs a TTL
   and a fencing token; a lock without either turns one outage into a stuck queue.
5. **Application `if`** — never sufficient alone. Between the read and the write, anything can
   happen. Fine as the layer that produces the *friendly error message* once a deeper layer said no.

**Rule: if the plan's only defence is #5, the plan has no defence.**

**Rule: enumerate every writer before you trust a guard.** A constraint/lock/validation protects a piece of state only as far as the writer with the weakest path to it. A careful `SELECT … FOR UPDATE` in one service is defeated the moment a *second*, unlocked writer touches the same column — a settings save that rewrites the whole row from a stale snapshot, an admin tool, a migration, a sibling repo. Grep **every** mutation site of the state and confirm each goes through the same guard, not just the path you happen to be editing; a lock at one write site and a blind `UPDATE` at another is the same defect as no lock.

## 5. State machines — write the transition table

For any status column, the spec carries a table, not prose:

- Every state, every allowed transition, and the *actor* allowed to make it.
- Which states are **terminal** (nothing leaves) and which are **open** (they hold a resource).
- **What expires an open state** — a pending payment that nobody finishes is otherwise pending
  forever, and forever means the user is blocked forever. Name the TTL, name what closes it
  (job, lazy check on next request), and name the state it lands in.
- What a message for a transition that is no longer valid does — reject, ignore, or record as
  an anomaly. Silence here is how late webhooks resurrect dead rows.

## 6. Multi-instance reality

Assume ≥2 app instances and ≥2 queue workers unless the plan says otherwise in writing:

- In-memory state (a `Map` of locks, a cached flag, a debounce) is **per-instance** and therefore
  not a guarantee.
- `@Cron`/scheduled jobs run on *every* instance. Say what stops N copies (advisory lock, leader
  election, unique job id).
- Webhooks and callbacks are delivered to whichever instance answers, more than once, in any order.

## 7. Build-lens baseline (what `/proposal` requires)

When a change writes state with a lifecycle, the spec MUST carry:

- [ ] The invariants of §3, as `R-xxx` requirements, in sentence form.
- [ ] For each invariant, the enforcement layer per §4 — and a migration task if it is a constraint.
- [ ] The §5 transition table in `design.md`, including terminal states and the TTL that closes
      each open state.
- [ ] Both idempotency **and** single-active addressed separately when the flow touches money,
      quota, or entitlement (§2).
- [ ] The user-facing behaviour of the *rejected* second request — an error code, a message, and
      whether it offers a way out (resume / cancel the existing one). A blocked user with no
      escape hatch is a support ticket by design.
- [ ] Tasks with `← (verify: N concurrent … → exactly one …)` on the enforcing task.

## 8. Tests that actually prove it

A sequential unit test proves nothing about a race. Require:

- **Concurrent test** — fire N (≥10) real parallel requests at the operation; assert exactly one
  side effect and that the other N−1 got the intended rejection. Against the real DB, not a mock:
  the constraint is the thing under test.
- **Out-of-order test** — deliver the callbacks/webhooks in reverse order, and twice.
- **Expiry test** — let an open state pass its TTL; assert it closes and the user is unblocked.
