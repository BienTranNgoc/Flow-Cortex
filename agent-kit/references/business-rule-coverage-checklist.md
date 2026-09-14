# Business-Rule Coverage Checklist — generate the cases, don't recall them

Case-generating reference for the osf kit. Written 2026-08-23 after an audit of the kit found
that all three of its auto-pulled lenses (security, concurrency, rollout) cover *technical*
boundaries, while every business-logic case rested on one recall-based line — "edge cases are
named out loud" — and on a `/proposal` audit that by its own wording only catches cases
"mentioned in conversation". A domain rule nobody said out loud was therefore structurally
unreachable by the entire pipeline.
<!-- CUSTOM:business-rule-lens 2026-08-23 (Master) — re-apply if the kit is reinstalled -->

> **When the osf kit pulls this in:**
> - **`/explore` / `/feat`** — the Stress-test step walks §1 to *generate* rules, not to confirm
>   the ones already mentioned. §2 runs on every feature; §3–§4 only when their gate fires.
> - **`/proposal`** — rules become numbered requirements (`R-xxx`) and the Debt & Coverage Audit
>   gains a `[RULE]` tag that, like `[RACE]`, does **not** wait for the case to have been raised.
>   See §7 (build-lens baseline).
> - **`/fix`** — §1 Question cards and §3's invalid cells drive the class-of-bug sweep: the same
>   root cause almost always has siblings.
> - **`/review`** — flags a stated rule with no enforcing code path, and a status column whose
>   invalid transitions are unhandled.

**Why a method and not a better prompt.** A 2026 coverage audit of three independently designed
LLM benchmarks found they converged on the same narrow slice of the case space — LLM case lists
do not merely miss things, they miss the *same* things, reproducibly, because they gravitate to
the most visible classes ([arXiv 2605.15118](https://arxiv.org/pdf/2605.15118)). Telling a model
to "think about edge cases" therefore regenerates the omission. What breaks the pattern is a
named technique with an explicit trigger and a required artifact — which is what each section
below is.

---

## 1. Rule / Example / Question — the artifact that makes silence visible

*Example Mapping, Matt Wynne / Cucumber, 2015. Run this on every feature, before any plan.*

Three kinds of card. Write them literally — the list is the artifact, not a mental step.

- **Rule** — one business rule, one sentence, in the domain's language, not the code's.
  *"A trial account cannot invite collaborators."* *"Refunds are allowed within 14 days of
  settlement, and only on a settled payment."*
- **Example** — one concrete instance that illustrates **exactly one** rule, with real values.
  *"Trial user Lan clicks Invite → blocked with 'Upgrade to invite teammates', upgrade CTA."*
- **Question** — anything you cannot answer from the code, the spec, or the user's words.

The discipline is in the couplings, and each failure means something specific:

| Symptom | What it actually means |
|---|---|
| A rule with no example | The rule is not understood yet — you cannot make it concrete |
| One example illustrating two rules | The rules are entangled; split them before planning |
| Many examples for one rule | The rule is really several rules, or has hidden conditions |
| No question cards at all | You did not look hard enough — not that the domain is simple |

**Question cards block.** They are the whole point: they convert "nobody thought of this" from
silence into a visible open item. Surface them to the user with concrete options (the kit's
A/B/★C format) — never resolve a business question by picking the plausible answer yourself.

## 2. The unwanted-behaviour sweep — every non-happy trigger is a requirement

*EARS, Mavin et al., RE'09 (2009). Run on every feature. This is the highest-yield section.*

The happy path is one sentence. Everything else is where features are actually missing, and
EARS's **unwanted-behaviour** pattern exists precisely to force those into the open, one line
each:

```
IF <trigger>, THEN the system SHALL <response>
```

Sweep the **trigger** space, not the response space — responses you can think of, triggers are
what you forget. Walk this list out loud for each operation:

- Input the domain rejects (not just malformed — *valid shape, invalid business meaning*)
- A precondition that is absent (no payment method, no verified phone, no active plan)
- The actor is not permitted (→ §4; every denial is a case with a user-facing response)
- An external dependency is **down**, **slow**, or **returns an unexpected shape**
- A limit is reached (quota, plan cap, rate limit, storage, seats)
- The same request arrives **twice**; two different requests arrive **at once** (→ concurrency lens)
- Something arrives **late or out of order** (a callback after a cancel)
- The user **cancels mid-flight**, or abandons and returns later
- The operation **partially succeeds** — 3 of 5 items processed, then a failure

**"Show an error" is not a response.** A complete line names the status/code, what the user
sees, and what they can do next. A dead end with no escape hatch is a support ticket by design.

The other EARS patterns are a cheap completeness check on the rules from §1 — each rule should
fit one of them, and a rule that fits none is usually underspecified:

| Pattern | Shape | Catches |
|---|---|---|
| Ubiquitous | *The system shall …* | Always-true invariants |
| State-driven | *While `<state>`, the system shall …* | Behaviour that depends on lifecycle |
| Event-driven | *When `<trigger>`, the system shall …* | The happy path |
| Optional-feature | *Where `<feature enabled>`, the system shall …* | Flag/plan-gated behaviour |
| Unwanted | *If `<trigger>`, then the system shall …* | **The class this kit was missing** |

*Industry validation that this form is agent-parseable, not just human-readable: AWS Kiro emits
its `requirements.md` in EARS by default (2025), and GitHub spec-kit has an open request to
adopt it for the same reason.*

## 3. State × Event grid — the empty cells are the cases

**Gate: the feature touches an entity with a status / lifecycle field. Otherwise skip.**

Draw the grid, not the arrows. Rows = every state. Columns = every event that can arrive.
Fill **every** cell. The cells nobody would draw an arrow to are the generated cases, and each
needs a decision: **reject** (with a code and message), **ignore** (idempotent no-op),
**no-op but record** (an anomaly worth alerting on), or **allow** (an arrow you had missed).

|  | `submit` | `approve` | `cancel` | `webhook: settled` |
|---|---|---|---|---|
| `draft` | → `pending` | **?** | → `void` | **?** |
| `pending` | **?** | → `active` | → `void` | → `active` |
| `void` | **?** | **?** | **?** | **?** ← *the late webhook that resurrects dead state* |

**Do not write two tables.** If the concurrency lens already fired for this change, its
transition table (`concurrency-state-checklist.md` §5) *is* this grid — extend that one with the
invalid cells. What this section adds is that the sweep runs even when there is no concurrency
concern at all: a single-user, single-writer entity still has invalid transitions.

## 4. Decision table, collapsed — when ≥2 conditions jointly decide

**Gate: two or more conditions independently affect the outcome. Otherwise skip.**

N binary conditions generate 2ⁿ rules. Enumerate them, then **collapse** — this second step is
not optional, it is what keeps the technique usable:

1. Write every combination.
2. Mark a condition `—` (don't care) wherever the action does not depend on it.
3. Merge rules whose action is now identical.

What survives is the informationally distinct case set. Skipping the collapse is why decision
tables get abandoned as unreadable.

**Authorization is this table, not a separate lens.** Rows = actor role, columns = resource
state, cells = permitted operations. Every unlisted cell is **deny** by default, and every deny
is a case that needs a response. The missing cell is the vulnerability — this is OWASP A01
Broken Access Control / API5:2023 BFLA restated as a coverage problem. `security-checklist.md`
checks that ownership/role checks *exist*; this generates *which* checks must exist.

## 5. Two companion trigger-tables (cheap, no gate)

**a) Zero / One / Many / Too many — applied to domain quantities, not input strings.**
The kit's existing "empty / too long / special chars" covers input shape. Extend the same
heuristic to every countable thing in the domain: zero collaborators, one, many, more than the
plan allows. And for every business threshold, the boundary generates three cases —
**limit − 1, limit, limit + 1** — plus the moment the limit itself *changes* under existing data
(a downgrade with 12 seats in use).

**b) Domain triggers — if the feature touches this, ask that:**

| Touches | Ask |
|---|---|
| **Money** | Rounding mode (half-even removes the upward bias half-up accumulates at scale); currency decimal places (JPY has none); multi-currency conversion timing; who absorbs the hanging penny in a split |
| **Dates / recurrence** | DST — spring-forward times that *do not exist*, fall-back times that happen *twice*; anchor-date vs now-based recurrence (Jan 31 monthly); backdating into a closed period; timezone of record vs of viewer |
| **Tenancy** | Does every query filter by tenant; can a shared resource be reached across tenants |
| **Deletion** | Soft or hard; what happens to children; what an already-issued link/reference resolves to afterwards |
| **Fixed-semantic value** (DOB, legal deadline, id, amount, age) | Does it stay constant across UI language, locale, device and execution timezone — a canonical stored form, one explicit locale-independent display format, any derived value (today/age/elapsed) computed in the business's fixed IANA zone — or does it silently shift (a native `<input type=date>`, a bare `toLocaleDateString`, a UTC "today" on a UTC server)? |
| **PII / identity capture** (DOB, phone, name, ID) | Where does the OWNER later VIEW it (masked-by-default + reveal, in an authenticated view — never admin-only) and CORRECT it (self-edit, or an in-product request honoring the legal SLA), with re-verification + an audit record when the value gates access? "Hidden for privacy" or "locked after capture" is not a substitute — hiding a field from OTHERS is a privacy control; hiding it from the OWNER with no view and no correction path is a data-subject-rights violation (GDPR Art.16; VN Luật 91/2025 Đ.4 view / Đ.13 correct; NĐ 356/2025 Đ.5 response ≤2 working days, done ≤10). |

## 6. What NOT to reach for — so this stays short

- **Requirements Smells** (Femmer et al., 2017) — linguistic lint (ambiguous adverbs, vague
  pronouns) on prose that **already exists**. It cannot surface a rule nobody wrote down. It is
  the closest-sounding name to this problem and the biggest trap in it; useful at most as a
  secondary lint on finished spec text, never as a generator.
- **Pairwise / combinatorial testing** (NIST SP 800-142) — reduces an *already enumerated* test
  suite, needs tool support, and belongs at test-writing time. It discovers no rules.
- **Classification trees / cause-effect graphing** — convert into a decision table anyway; §4
  covers the same ground without the graphical tooling.
- **Given/When/Then** — the **output format** for cases §1–§4 produced, never the discovery
  method. Used as a discovery device it produces imperative, UI-coupled scenario bloat (Adzic's
  own retrospective, 2015).

## 7. Build-lens baseline (what `/proposal` requires)

For any change that adds or alters behaviour a domain expert would have an opinion about:

- [ ] The §1 **Rule cards** exist as numbered requirements (`R-xxx`), each in one sentence of
      domain language, each with at least one concrete example in its `#### Scenario:`.
- [ ] Every unresolved §1 **Question card** is either answered by the user, or recorded in
      `Out of Scope` / as an `[AGENT-ASSUMED]` premise with how to verify it. Never silently dropped.
- [ ] The §2 sweep ran, and every `IF <trigger> THEN SHALL <response>` line names a status/code,
      the user-facing message, and the next action available to the user.
- [ ] If the gate fired: the §3 grid is in `design.md` with a decision in **every** cell
      (or an extension of the concurrency lens's table, not a second one).
- [ ] If the gate fired: the §4 collapsed table is in `design.md`, and its authz rows state the
      default-deny cell behaviour.
- [ ] Tasks carry `← (verify: …)` annotations that name the *rule*, not the code path —
      *"trial user cannot invite → 403 + upgrade CTA"*, not *"invite guard added"*.

## 8. Why this file stays short

Checklist length trades directly against compliance. The DO-CONFIRM design Gawande argues for —
few items, killer items only — is backed by the WHO Surgical Safety Checklist RCT across eight
sites: complications 11.0% → 7.0%, in-hospital death 1.5% → 0.8% (Haynes et al., NEJM 2009).
A ninth technique added here would not add coverage; it would cost the eight already present.
If a section stops earning its lines, cut it rather than appending.

## 9. Evidence caveats — do not over-claim when citing this file

- **Do not cite "defects cost 50–200× more when found late."** That multiplier traces to Boehm's
  1981 TRW dataset and has been materially revised — Boehm & Basili's own 2001 update shows a far
  flatter curve for small/CI-CD projects, and a 171-project study (2006–2014) found no support for
  the effort-reduction claim. The *direction* holds; the number does not.
- **"Omission dominates commission" as a defect class is widely asserted, not rigorously proven.**
  No recent quantitative meta-analysis was found. Use it as motivation, never as a cited fact.
- **The NIST 66% / 97% interaction figures are domain-specific** observations from six datasets
  (the most-quoted pair is medical devices), not a universal law.
