# Migration & Data-Change Safety Checklist

Reference for the osf kit. `engineering-judgment-checklist.md` §3 says *what shape* a data change
takes (expand → migrate → contract, and which end of the deploy it sits on). **This file is the
execution layer**: which lock it takes, how long it holds it, what a backfill must not do, and
what a `down()` can and cannot actually undo. The shape being right does not save a migration that
takes an `ACCESS EXCLUSIVE` lock on a hot table for four minutes.
<!-- CUSTOM:migration-safety-lens 2026-08-23 (Master) — re-apply if the kit is reinstalled -->

> **When the osf kit pulls this in:**
> - **`/feat` / `/fix` / `/explore`** — as soon as a plan implies a schema or data change, §1's
>   three questions run before the migration is written.
> - **`/proposal`** — §8 becomes tasks; the sequencing and the backfill are separate checkboxes.
> - **`/apply`** — §2 ordering, §5 per-operation mechanics.
> - **`/review`** — §5 red flags, §6 down-migration honesty.
>
> Skip entirely for a change that touches no persisted shape and no existing rows.

---

## 1. Three questions before a migration is written

1. **How many rows are already there?** Not "how many will there be" — *how many are there now, in
   production*. Every answer below changes at scale. Do not guess this: query it. An operation that
   is instant on 400 rows and locks the table for minutes on 4M rows looks identical in the diff.
2. **What still reads or writes the old shape while this runs?** The old application version is
   live during the migration and, in a rolling deploy, for a while afterwards. Also: queue workers
   mid-job, a cron that just started, a long-running report, another service.
3. **What happens if this stops halfway?** Not "will it fail" — *when it does*. Which of the steps
   have landed, is that state serviceable, and is the retry safe to run twice?

If any of the three has no answer, the migration is not ready to write.

## 2. Order — and the trap in a migrate-then-deploy pipeline

The rule from `engineering-judgment-checklist.md` §3:

- A migration that **adds** capability runs **before** the code deploy that uses it.
- A migration that **removes** capability runs **after** the code deploy that stopped using it.

**Most CI pipelines only implement the first half.** A pipeline shaped
`run migrations → update service → wait stable` is correct for every *expand* step and **wrong for
every *contract* step**: at the moment the migration runs, the old code is still serving. A
`DROP COLUMN` there removes a column the running application is still selecting.

**Therefore the contract phase is a LATER deploy, never the same one.** Not a later task in the same
pipeline run — a subsequent release, after the code that stopped using the old shape is fully
rolled out everywhere, including workers. This is the single most common way a correct
expand/contract plan still causes an incident.

*(Verified on this workspace 2026-08-23: `.github/workflows/deploy.yml` runs `RunTask migration`,
then a backfill task, then `Update service + wait stable`. Adds are safe; a drop in that same run
is not.)*

## 3. Locks — the part that turns a correct migration into an outage

Postgres holds every lock a statement takes **until the transaction commits**. The danger is not
usually the operation's own duration; it is two other things:

- **Lock queueing.** A migration waiting on an `ACCESS EXCLUSIVE` lock blocks *every* query that
  arrives behind it, including plain `SELECT`s — even though the migration itself has not started
  yet. One long-running query in front of it converts a millisecond migration into a full outage
  for its duration.
- **Batch transactions.** If the migration runner wraps the whole pending set in one transaction,
  every lock any migration takes is held until the *last* one commits.

**Two mitigations, both cheap:**

- Set a **`lock_timeout`** (a few seconds) at the start of a migration that touches a hot table, so
  it fails fast and retries instead of forming a queue behind itself. A failed migration you rerun
  is a much better outcome than a stalled site.
- Keep long operations (backfills, index builds) **out** of the schema transaction entirely — §4.

## 4. A backfill is not a migration

Backfilling existing rows is a **data job**, and putting it inside a schema migration is what makes
migrations take hours and hold locks. Separate it:

- **Batched, not one-shot.** Update in bounded chunks (by id range or a `LIMIT`ed loop), committing
  each batch, with a pause between them. One `UPDATE` over 4M rows is one enormous transaction, one
  enormous lock, and one enormous rollback if it fails at 95%.
- **Resumable and idempotent.** It will be interrupted. Running it again must be safe and must skip
  what is already done (`WHERE new_col IS NULL`), not redo it.
- **Its own task in `tasks.md`**, with its own checkbox — visible, re-runnable, and reportable.
  This workspace already has the pattern in CI (a dedicated backfill RunTask beside the migration
  RunTask); follow it rather than inventing a new place for it.
- **Verified after it runs.** A count of remaining un-backfilled rows is the proof the next step
  (tightening the constraint) is allowed to proceed. "The job exited 0" is not that proof.

## 5. The five operations that actually bite

| Operation | Why it bites | Do this instead |
|---|---|---|
| **Add `NOT NULL` column** | Rejects writes from the still-running old code, which does not set it | Add nullable (with a default if useful) → dual-write → backfill → **then** `SET NOT NULL`, in a later step |
| **`SET NOT NULL` on existing column** | Full table scan under a strong lock | Add `CHECK (col IS NOT NULL) NOT VALID` → `VALIDATE CONSTRAINT` (weaker lock) → then `SET NOT NULL`, which can skip the scan (PG 12+) |
| **Create an index** | Ordinary `CREATE INDEX` blocks writes for its whole build | `CREATE INDEX CONCURRENTLY` — but it **cannot run inside a transaction block**, so it needs the migration runner's transaction disabled for that migration. Check what that costs before assuming it is available |
| **Rename a column/table** | There is no moment when both old and new code are correct | **Never rename in place.** Add new → dual-write → backfill → switch reads → drop old. A rename is an expand/contract, not a rename |
| **Change a column type / enum** | Rewrites the table; enum values cannot be removed | New column of the new type → backfill with the conversion → switch → drop. For enums, adding a value is cheap; removing one is an expand/contract on the column |

**Adding a column *with* a default is cheap on PG 11+** (no table rewrite) — this is the one case
where the intuitive thing is also the safe thing.

## 6. `down()` — be honest about what it can undo

A down-migration restores **schema**, never **data**. `down()` for a `DROP COLUMN` recreates an
empty column; the values are gone. Treat this plainly:

- For a destructive step, the real rollback plan is a **restore from backup**, and the plan should
  say so rather than implying `down()` covers it.
- **An untested `down()` is worse than an absent one**, because it invites someone to run it in an
  incident. If it is not exercised, say so in the migration.
- **`migration:revert` reverts the newest migration recorded in the database — not the one you
  wrote.** In a shared branch or parallel session, that is somebody else's migration. Count what is
  actually pending before reverting.

## 7. This workspace — verified facts, not inherited assumptions

Measured 2026-08-23 on `xood-backend`. Re-verify before relying on any of it; that is the point of
writing it down rather than remembering it.

- **Postgres 17**, TypeORM, **447 migrations**, of which **175 touch `NOT NULL`** — this hazard
  class is the norm here, not an edge case.
- **`migrationsTransactionMode` is never set**, so TypeORM's default (`all`) applies: **every
  pending migration in a deploy runs inside one shared transaction.** One failure rolls the whole
  batch back, and no migration in the set can use `CREATE INDEX CONCURRENTLY` without changing that
  mode system-wide. The single migration in the repo that wanted a concurrent index documents
  exactly this and chose a plain index instead — read it before proposing a change to the mode.
- **TypeORM selects pending migrations by `name`, not timestamp** — a partial apply can exit 0 and
  read green. Verify against the `migrations` table, with ids, not against the exit code.
- **CI runs migration → backfill → update service.** Correct for expand; a contract-phase drop in
  the same run hits still-live old code (§2).
- Committed migrations do **not** build the full schema (drift versus entities), so "migrations run
  without errors" on a fresh database proves less than it appears to.
- `npm run migration:run` goes through `env-cmd` and needs a `.env` the container does not have —
  run the TypeORM CLI directly inside the container instead.

## 8. Build-lens baseline (what `/proposal` requires)

- [ ] Current production **row count** for every table touched — queried, not estimated.
- [ ] Expand / backfill / contract are **separate tasks**, and the **contract task is scheduled for
      a later release**, not the same pipeline run (§2).
- [ ] The backfill is its own task: **batched, resumable, idempotent**, with the verification query
      that proves it finished.
- [ ] Each operation checked against §5 — and any `NOT NULL`, index build, rename, or type change
      states which of the safe forms it uses.
- [ ] Lock exposure named for anything touching a hot table (and `lock_timeout` set, or a reason
      why not).
- [ ] The rollback plan says plainly whether `down()` is sufficient or a **restore is required**.
- [ ] For a runner using one shared transaction: the whole batch is understood as atomic, and
      nothing in it needs an operation that cannot run in a transaction block.

## 9. What counts as proof

- **Run it against a copy of production data**, not a seeded dev database. Row count is the variable
  that decides everything in §3–§5, and dev has the wrong one.
- **Measure the lock duration**, not just the wall-clock success.
- **Verify the backfill by query** — remaining un-backfilled rows, not job exit code.
- **Confirm what actually applied**: read the `migrations` table with ids. An exit code of 0 is not
  evidence the set applied (§7).
