# Rollout Safety Checklist — every feature ships with an off switch

Reference for the osf kit. Standing rule from Master, 2026-08-22: *a feature that cannot be
turned off without a deploy is not finished.* A push that breaks something must be recoverable
by flipping a switch, not by a hotfix, a revert, or a rollback of the whole release.
<!-- CUSTOM:kill-switch-lens 2026-08-22 (Master) — re-apply if the kit is reinstalled -->

> **When the osf kit pulls this in:**
> - **`/proposal`** — a change that ships user-reachable behaviour carries its switch as a
>   numbered requirement (`R-xxx`) with its own task. See §5 (build-lens baseline).
> - **`/explore` / `/feat`** — the Stress-test step asks "what do we flip at 2am when this
>   misbehaves, and who can flip it without a deploy?"
> - **`/review`** — flags a new production surface with no runtime switch.

## 1. Why a deploy is not a rollback

Reverting a merge re-runs the whole pipeline, takes as long as the original deploy, and drags
along every *other* change that shipped with it. Worse, some changes are not revertible at all
once live — a migration that ran, a webhook the provider now sends, a token the client cached.
A switch is instant, scoped to one feature, and needs no build. The two are not substitutes:
keep the revert for bad code, use the switch to stop the bleeding first.

## 2. Two switches, not one — backend and view

They fail independently and are both required for anything user-facing:

- **Backend switch** — the write path, the job, the external call. Off = the endpoint refuses
  cleanly (a defined status + code, not a 500), the job no-ops, the integration is not called.
  This is what protects *data*: a buggy feature that is merely hidden in the UI can still be
  reached by a stale tab, a mobile client, a retry, or a direct call.
- **View switch** — the route, the nav entry, the button, the badge. Off = the user never
  reaches it, and no dead links are left behind. This is what protects *trust*.

**The view switch alone is not a kill switch.** Hiding a button while the endpoint still accepts
requests leaves the failure mode fully live.

## 3. Properties the switch must have

- [ ] **Flippable without a deploy** — an admin action or a runtime setting read per request/job.
      A build-time constant is not a switch. An env var alone means a container restart, so it
      is the *fallback*, not the mechanism.
- [ ] **Takes effect fast, and observably** — say the propagation bound (immediate / next poll /
      restart-only) and surface it, so whoever flips it knows when it took. A switch whose effect
      is unverifiable gets flipped twice in a panic.
- [ ] **Safe default** — new feature defaults **off** and is turned on deliberately; a switch
      protecting an existing surface defaults **on** so a missing/unreadable value never dark-ships.
      State the default and the behaviour when the value is absent or malformed.
- [ ] **Audited** — who flipped it, when, from what to what. During an incident this is the
      timeline.
- [ ] **Degrades, not dead-ends** — off must produce a coherent state: a "coming soon" surface,
      the previous behaviour, or an explicit "temporarily unavailable" — never a blank screen,
      a spinner forever, or a route that 404s from a nav item that still exists.
- [ ] **Reachable when the feature is broken** — the switch must not live behind the thing it
      turns off.

## 4. Reuse the project's existing mechanism — do not add a flag library

Simplicity ladder rung 2. Before designing anything: find how this project already toggles
things (a settings registry, an admin console, a config table, an existing hook) and add a key
to it. A second flag system means two places to look during an incident, which is exactly when
nobody can afford to look twice.

Only when a project genuinely has none: the smallest thing that is runtime-readable and audited
— usually a settings row read per request plus an admin endpoint — not a hosted flag SaaS added
for one boolean.

## 5. Build-lens baseline (what `/proposal` requires)

For a change that ships user-reachable behaviour:

- [ ] `R-xxx`: **a runtime switch for the backend surface**, naming the key, its default, and
      the behaviour when off (status + code, or no-op).
- [ ] `R-xxx`: **a view switch** for every route / nav item / entry point the change adds, and
      what the user sees instead.
- [ ] Both keys registered in the project's existing mechanism (§4), with the admin surface to
      flip them and the audit trail.
- [ ] Propagation bound stated (immediate / poll interval / restart) — and if restart-only, say
      so where an operator will see it.
- [ ] A task `← (verify: flag off → surface unreachable AND endpoint refuses cleanly; flag on →
      feature works; no dead-end state)`.
- [ ] A line in the change's rollout notes: **what to flip, where, and what "off" looks like**,
      so an on-call engineer who never read the spec can act on it.

Skip only for work with no production surface at all (pure refactor, internal util, docs) — and
say that it was skipped, rather than leaving it silently absent.
