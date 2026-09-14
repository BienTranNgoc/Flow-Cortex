# Anti-rationalization tables

The excuses that precede each failure mode, next to why each one is wrong. Pulled by the
CLAUDE.md section that owns the rule — read the relevant table when you notice yourself
reaching for one of these.

---

## §1 — Verify, don't guess (INPUT gate)

The exact excuses behind the 2026-06-25 miss: a plan assumed an endpoint already returned a
field it actually stripped. Static checks and browser-on-empty-data both looked green while
the feature was wholly broken on real data.

| Rationalization | Reality |
|---|---|
| "It's FE-only / no backend change needed." | The backend may strip the field at a serialization/projection layer the FE never sees. Read the actual serializer AND `curl` the live endpoint before asserting. |
| "The entity/mapper/type has the field, so the API returns it." | Persistence-mapper ≠ HTTP response. A gated projection/DTO can drop it silently. Verify the JSON payload, not the mapper. |
| "Static checks + a browser run are green, so it works." | Green on empty/edge data hides runtime stripping. Re-verify with real, populated data. |
| "I haven't checked, but it's probably X." | "Probably" is not evidence. One tool call (curl / Read / DB query) BEFORE the claim enters a plan. |
| "I'll verify / fix it at apply — mark it 'apply-time verify' / 'confirm in the browser later'." | A load-bearing fact a probe settles NOW (Read / GitNexus / curl / query) but deferred is an unverified premise the whole plan rests on — the deferral *reads* as diligence but is a punt. Settle it BEFORE the plan/spec locks. "Verify at apply" is legitimate ONLY for behavior that genuinely can't be known until runtime (true e2e), NEVER for a code-readable fact. |

---

## §18 — Verify, don't trust (OUTPUT gate)

| Rationalization | Reality |
|---|---|
| "The summary is from earlier this session, it's still accurate." | Context drifts and compresses; the resource may also have changed. Re-fetch before it ships. |
| "CR already showed me that code." | CR returns retrieval snippets — possibly stale, truncated, or from a lookalike symbol. Chain to GitNexus, then Read the real lines. |
| "GitNexus confirmed the relationship, no need to open the file." | The graph proves structure, not semantics — guards, flags, and edge cases live in the body. Read the `file:line` before citing it. |
| "Re-checking everything is too slow for this deliverable." | Only load-bearing claims need the check — but those get it without exception. One wrong premise invalidates the whole analysis (§1's 2026-06-25 incident). |

---

## §20 — Findings reach a decided end-state

| Rationalization | Reality |
|---|---|
| "It's just Minor — take it or leave it." | Minor still needs an end-state. Actionable + in-scope → fold now; not-doing → record why. "Leave it" the user must remember to re-raise is a silent leftover. |
| "I'll fold/fix it at apply." | An in-scope fix is folded NOW; a probe-settleable fact is verified NOW (§1). "At apply" is only for genuine runtime-only behavior. |
| "The user can ask if they want the Minors." | Putting the recall burden on the user IS the bug. Drive each finding to an end-state; ask only genuine decisions. |
| "Capping decisions avoids fatigue, so I'll drop the rest." | Cap the *questions*, not the *coverage*. Resolve the small stuff (fold/record) instead of dropping it. |
