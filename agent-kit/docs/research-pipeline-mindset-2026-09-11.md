# Pipeline mindset research — reconciliation ledger (2026-09-11)

Three /research passes: (1) BA/QC acceptance readback [pending], (2) tech-lead planning + spec writing, (3) senior-dev implement + review.
Each row: external finding (source, year) → what the kit already has (file:line, measured this session) → verdict.

## Report 2 — tech-lead planning + spec

| # | Finding | Kit today | Verdict |
|---|---|---|---|
| P1 | No-Spec / Light-Spec lane (Anthropic best-practices; de Smet 2026; Nearform 2026) | `explore/SKILL.md:57-68` Triage Gate trivial lane (≤2 files, ≤40 lines, no shape/export/rule); `/chore`; autopilot Light/Verified/Full | HAVE. Middle "Light Spec" lane (1-page proposal, no design.md) only implicit via `proposal:194` size-to-content. Adapt: name it. |
| P2 | Implementer self-report untrusted — 23–27% of agent failures are inaccurate self-reporting (arXiv 2605.29442); LLM judge AUROC ≤0.65 vs structural 0.83–0.95 (arXiv 2606.09863) | `agents/osf-apply.md:108-115` REPORT INTEGRITY + `## Not verified`; `apply/SKILL.md:122` gate (1)-(5); `settings.json:216` subagent-tool-census enforce hook | HAVE. Numbers back the gate-over-instruction stance. No change. |
| P3 | Critic checklist concrete: Shape Up rabbit holes ("new technical work never done?", "assuming a design exists we couldn't invent?"), genuine alternatives on one rubric (Ousterhout design-it-twice) | `agents/osf-critic.md:93-104` has assumptions/scope gaps/sequencing; no rabbit-hole pair, no alternatives-rubric check | ADAPT: +3 lines in critic Review dimensions. |
| P4 | Lint unbounded quality adjectives in SHALL (INCOSE R7; EARS misuse lit.) | `proposal/SKILL.md:212` vague-adjective-ban is prose; `scripts/check-req-traceability.mjs` exists, no word lint | ADAPT: mechanize — regex in check-req-traceability.mjs. |
| P5 | Tag task groups structure vs behavior (Beck, Tidy First 2023); lighter verify for pure-structure slices | none (grep 0 in osf-apply/proposal); agents never commit (`osf-apply.md:440`) so commit-split is N/A | ADAPT (medium): `[structure]`/`[behavior]` tag per tasks.md group; scoped verify for `[structure]` = no test assertion changed + impact d=1 in diff. |
| P6 | Spec re-opened mid-implementation (Brooker 2026; Böckeler spec-anchored 2025) | `osf-apply.md:59-63` deviation-loop writes deviations to a pipeline-read path; `/proposal` Phase 0 update mode | HAVE mechanism. Adapt: apply gate — "deviations non-empty → /proposal update BEFORE next slice". |
| P7 | PR/FAQ "who is this for, what changes for them" before R-xxx (Amazon Working Backwards) | `explore:220` ELI5 restatement; `proposal:201` Out of Scope mandatory; no user/before-after gate | MERGE with BA/QC acceptance readback (report 1 pending). |
| P8 | Non-goals section (Google design docs 2020) | `proposal:201` Out of Scope mandatory + `[CUT]` audit `:165` | HAVE. |
| P9 | Premortem "assume it failed" (Klein 2007 — primary says +30%, not "doubles") | explore pre-mortem; `proposal:197` pre-mortem incidents carry over | HAVE (author side); critic = outsider. OK. |
| P10 | Vertical-slice test per task group (INVEST) | `proposal:196` slice fidelity; no "independently demoable" test | ADAPT: one sentence in Artifact guidelines. |
| P11 | Decision-level "superseded" (Nygard ADR 2011) | archive at change level | REJECT — design.md per change is small; supersession = new change. |
| P12 | Track time-to-first-commit vs escaped defects (de Smet 2026) | not tracked | OPTIONAL. |

## Report 3 — senior-dev implement + review

| # | Finding | Kit today | Verdict |
|---|---|---|---|
| R1 | Reviewer calibration: precision > recall, flag only correctness/requirement gaps, Nit labeling (Anthropic best-practices; Google eng-practices; Google ML-review 2024 = 7.5% applied) | `review/SKILL.md:139` precision-over-recall; `:123-131` Verify Before Filing (CRITICAL/WARNING only); `:209` SUGGESTION fold-by-default | ADAPT + DECISION: keep "every finding reaches end-state" (Master 09-03) but add a raise-gate for SUGGESTION (observable benefit + in scope) — conflicts with "SUGGESTION dropped only by Master's note". Master decides. |
| R2 | Test tampering as its own category (Beck 2025; METR reward hacking 1–2%) | `osf-apply.md:94` rationalization guard; `osf-verify.md:88` diff the tests; `review/references/review-dimensions.md` has NO such dimension (grep 0) | ADAPT: add dimension — `.skip/.only`, deleted test, loosened assertion in diff = CRITICAL. |
| R3 | Organizing question first: "does it do what was intended, and is that right for users?" (Google; Osmani 2025 "first human to see this code") | `review-dimensions.md` order 1 Impact Gaps … 11 Business-Rule Coverage; intent only in Gather Context `:96` | ADAPT: dimension 0 = spec-intent match; brief tells reviewer to reconstruct intent from spec+diff, not assume implementer understood. |
| R4 | Compliance gap (arXiv 2605.01771: 0% compliance, text-undetectable), MANTRA (2605.06334: 76% failures = missing required call), omission decay (2604.20911) | `settings.json:216` enforce hook + apply gate (4b) | HAVE — right shape. Stop trying to re-word briefs for this class; record in working-agreements. |
| R5 | Agent env hides test failures (Böckeler 2025) | memory: 14 BE suites skipped silently without DB; gate (3) checks exit code only | ADAPT: gate (3) asserts `ran ≥ 1, skipped = 0` for touched files. |
| R6 | Fresh-context reviewer must not know authorship (arXiv 2603.12123) | review gets diff + spec; handoff contract is outbound only | HAVE; confirm review brief never pastes osf-apply's report. |
| R7 | Human escalation for one-way-door in unattended runs (Monperrus 2606.13175; 2605.17548) | autopilot critique-if-one-way-door, but discuss-chain unattended self-resolves except RETHINK | DECISION: one-way door in unattended = human stop, not ★Recommend. |
| R8 | Seam-first before editing untested code (Feathers 2004) | none | fold into P5. |
| R9 | Anthropic postmortem 2026-04-23: passed every gate; fix = wider real-build exposure | QC re-tests from scratch on container serving latest code | HAVE. |

Caveats: rows P2, R4, R7 rest on 2026 arXiv preprints (large N, not peer-reviewed). Premortem "doubles" claim is wrong — primary HBR text says +30%.

## Report 1 — BA/QC acceptance readback (58 sources)

| # | Finding | Kit today | Verdict |
|---|---|---|---|
| Q1 | The proposed step IS perspective-based reading (ISTQB CTFL 2018 v3.1 §3.2.4; Basili 1996 NASA: +30% detection, no meeting needed; weak when the reader already knows the doc) | none — `/verify` is checking (Bach & Bolton 2013), no validation step before code | ADOPT. Reader = fresh subagent, never the proposal author; must PRODUCE the journey rows (Parnas & Weiss 1985 active review), not tick a list. |
| Q2 | Placement: on the requirements, before design/critique (Basili; ISTQB v3.1; Anthropic evals 2026 "eval tasks stress-test whether requirements are concrete enough"); counter-argument "after critique" loses, keep a deterministic re-map before apply (spec-kit `/analyze` precedent) | chain has no slot; OpenSpec has no artifact for it | ADOPT: `/proposal` step 8 (after Debt & Coverage Audit, before `✅ Spec created`), output `content/acceptance.md`; `/discuss-chain` Phase A brief carries the rows; `/apply` re-maps J↔R-xxx before slice 1; `qc-hunt` reads rows as `expected`. |
| Q3 | Operator: humans closest to users define success (Anthropic 2026; OpenAI harness 2026); LLM-assisted humans detect LESS on smells (Broccia 2026) → stage it; persona labels do nothing (Zheng 2023); default LLM users are too cooperative (PPol 2026, τ-Trait 2026, PayPal 2026) | `/chat` has 3 segments + 111-session corpus (memory) | ADOPT: product-level `journeys.md` (segment × 3 jobs + seller vocabulary) derived from the corpus ONCE by Master; per change a fresh reader with behavioural priors (vague opener, no product words, taps first card, satisfices — Krug); Master's ≤15 min go to rows + Question cards, never to ticking an LLM list. |
| Q4 | Unattended rule: GAP on a basic job = CRITICAL halt (spec-kit CRITICAL blocks implement; Wynne red cards = not ready); ★Recommend may not self-resolve a validation question (Boehm V&V; Pombal 2026 self-preference up to 50%) | discuss-chain unattended self-resolves except RETHINK | ADOPT. Same family as the one-way-door decision (R7). |
| Q5 | Severity by outcome, not mechanism (Atlassian severity = impact; Vaughan normalization of deviance; OpenSpec verify's "prefer lower when uncertain" institutionalises drift) | memory `qc-score-user-outcome-not-mechanics` (Master 09-04) is a rule in memory only | ADOPT: one line in the qc-hunt brief + in acceptance.md footer: "any basic job failing in ≤3 turns = SEV1 regardless of mechanism count". |
| Q6 | Format: Markdown table (job story + Given/When/Then in seller words + R-xxx + Verify-how + Status); Gherkin only as export (Adzic 2020 drift; Playwright test-agents markdown plan → tests) | `← (verify:)` lines authored by spec author | ADOPT format; rows also fix turn-1 behaviour (Laban 2025: 39% multi-turn drop, no recovery). |
| Q7 | Backward trace: R-xxx reached by no journey = "functionality nobody asked for" (Beck 2025) | none | ADOPT as Q3 of the reader checklist. |
| Q8 | Absorbs P7 (PR/FAQ "who is this for") — job story row IS that gate | — | MERGED. |

Caveats: no study measures LLM journey-reading of specs; "3 jobs" is a Top-Tasks heuristic; JATR read via secondary sources; Bossavit: do not cite the 1:10:100 defect-cost chart, cite our own incident cost (3 QC rounds, owner escalation).

## Consolidated change list (ranked by evidence)

1. NEW acceptance readback: `proposal/SKILL.md` step 8 (spawn fresh reader, Q1–Q12, `content/acceptance.md`, GAP → Question card / unattended halt); `discuss-chain` Phase A brief includes rows; `apply/SKILL.md` MACRO LANDSCAPE step re-maps J↔R; `qc-hunt` brief reads rows + SEV1-by-outcome line; product-level `journeys.md` for /chat from corpus (Master).
2. `review/references/review-dimensions.md`: dimension 0 "spec-intent match, reconstruct intent from spec+diff"; new dimension "test tampering" (skip/only/deleted/loosened = CRITICAL).
3. `apply/SKILL.md:122` gate (3): assert ran ≥ 1 and skipped = 0 on touched test files; gate: deviations non-empty → `/proposal` update before next slice.
4. `scripts/check-req-traceability.mjs`: unbounded-adjective regex on SHALL clauses (INCOSE R7) — mechanises `proposal:212`.
5. `agents/osf-critic.md` Review dimensions: +2 Shape Up rabbit-hole questions, +1 alternatives-on-one-rubric check.
6. `proposal/SKILL.md`: name the Light-Spec lane; vertical-slice sentence per task group; `[structure]`/`[behavior]` tag per group with lighter scoped verify for `[structure]`.
7. `docs/working-agreements.md`: record "compliance-gap class → gates, never re-wording" with the three 2026 preprints.

Decisions for Master: (a) SUGGESTION raise-gate vs "all SUGGESTIONs to end-state"; (b) one-way door in unattended = human stop; (c) GAP on a basic job in unattended = human stop (same family as b).
