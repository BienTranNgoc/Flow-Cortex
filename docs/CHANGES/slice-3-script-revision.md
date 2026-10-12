# Slice 3 — Product Video script revision & approval checkpoint

The durable change record for **Slice 3** ("Script revision and approval") in `Flow-Cortex`,
between the close of Slice 2 and the close of this slice. It stands in for an OpenSpec change
because the `openspec` CLI is not installed in this workspace (`0_context §2`); its
`Requirements` and `Test gates` sections lift into `openspec/changes/<id>/` when a CLI is
available.

## 1. Why

Slice 2 produced the script and paused the run at a checkpoint, but the checkpoint was a **single
mutable payload**: the reviewer edited a textarea and posted the whole text back, with no record of
what they approved and no way for the server to tell an approval of text-A from an approval of
text-B. Three product invariants in `0_context §6` fail against that shape:

- **The reviewer must approve exactly what they saw.** Two tabs, or a tab left open while the same
  user edits from a phone, both read the same payload; whichever clicks last silently overwrites.
- **An approval must be reproducible and auditable.** Without a stored revision, "which script did
  this video use?" has no answer after the fact.
- **TTS must never be dispatched from unapproved text.** Slice 2 already wired step 3 to
  `{step:2.script}`, but nothing made the approval the *only* path into that value.

## 2. Requirements

| ID | Requirement | Status | Evidence |
|---|---|---|---|
| `R-REV-1` | The checkpoint shall store the provider's script as **revision 1** of an append-only `script_revision` row and move the task's `current_revision_id` onto it, so the first thing the reviewer sees is already a revision. | done | `ReviewCheckpointTransactions.pause`; `ScriptRevisionApprovalIT::aCheckpointArrivesWithTheFirstScriptRevisionAndNoApprovedRevision` |
| `R-REV-2` | An edit shall **append** a revision and advance the pointer; it shall never overwrite an existing one. | done | `ReviewTaskServiceImpl.saveRevision`; `ScriptRevisionApprovalIT::editingTheScriptStoresANewRevisionAndMovesTheCurrentPointer` |
| `R-REV-3` | A concurrent edit from a stale tab shall be rejected with **409**, not a 500 and not a silent overwrite. Two guards: the task's `@Version`, and the `UNIQUE (review_task_id, revision_number)` clash. | done | `ReviewTask.attachRevision` + `saveAndFlush` → `DataIntegrityViolationException` → `ConflictException`; `ScriptRevisionApprovalIT::aStaleExpectedVersionIsRejectedWithConflict` |
| `R-REV-4` | Approval shall name the **revision id** it read plus the task `version` it read. An approval of a revision that is no longer current shall be a 409. | done | `ReviewTask.approveRevision`, `ReviewTaskServiceImpl.approveTask`; `ScriptRevisionApprovalIT::approvingAnEarlierRevisionOnceANewerOneExistsIsAConflict` |
| `R-REV-5` | A second approval of the same revision shall be a 409, so only one of two tabs wins. | done | `ReviewTask.approve` requires `status == PENDING`; `ScriptRevisionApprovalIT::aSecondApprovalOfTheSameRevisionIsAConflictSoOnlyOneTabWins` |
| `R-REV-6` | A task that keeps no revisions shall reject a revision-style approval (and vice versa) with 409, so the two checkpoint shapes cannot be confused. | done | `ReviewTaskServiceImpl.approveTask`; `ReviewTaskServiceImplTest` mixed-shape cases; `ScriptRevisionApprovalIT::approvingWithoutARevisionOnAScriptTaskIsAConflict` |
| `R-REV-7` | **No speech job may be dispatched before an approval lands.** The TTS step stays `pending` while the run is `waiting_approval`. | done | `ScriptRevisionApprovalIT::noSpeechJobIsDispatchedUntilARevisionIsApproved` |
| `R-REV-8` | The speech job shall carry the **approved revision's** content and nothing else — not the original, not the latest. | done | `ReviewCheckpointTransactions.stepOutput`; `ScriptRevisionApprovalIT::theApprovedRevisionIsTheScriptTheSpeechJobCarries` |
| `R-REV-9` | Reading, editing and approving a revision shall be owner-scoped: a stranger gets **404**, never 403, so task ids stay unprobeable. | done | `ReviewTaskServiceImpl.ownedTask`; `ScriptRevisionApprovalIT::anotherUserCannotReadEditOrApproveARevision` |

## 3. Design choices

### 3.1 A new table rather than columns on `review_task`

`script_revision` is append-only, so it is a table and not three more columns on `review_task`. The
alternative — overwriting `reviewed_payload` — is exactly the shape Slice 3 exists to remove.
`UNIQUE (review_task_id, revision_number)` makes a concurrent writer's clash a **constraint
violation at flush**, which the service converts to the same 409 the version guard produces, so
both races answer identically instead of one being a 500.

### 3.2 Approval names a revision, it does not carry text

`POST /api/v1/review-tasks/{id}/approve` gained `revisionId` + `expectedVersion`. The server reads
the content **from the stored revision** rather than from the request body, so a client cannot
approve text it never stored and the audit trail needs no duplicate of the content in the log.

The legacy `reviewedPayload` path is kept for tasks with no revisions, so existing demo workflows
and their tests are untouched.

### 3.3 Step 2's output stays the provider result shape

`generate_speech` reads `{step:2.script}` and `InputResolver` parses step output as JSON, so the
checkpoint step's output must stay `{"script": "..."}` even though `review_task` now stores the
bare script text. `ReviewCheckpointTransactions` unwraps on `pause` and re-wraps on `resume` —
one shape at each boundary, and neither the storage nor the wire has to carry the other's envelope.

### 3.4 `advance` runs in a transaction it owns

A Product Video run stalled after step 1: the provider result closed the step in
`RunStepTransactions.complete` (its own `REQUIRES_NEW`), and `RunAdvancer.advance` then read the
step ladder from a persistence context that still held the step at its **pre-transition** status —
`Step 1 already RUNNING`. The run then sat at `step1=completed, step2=pending` until the reaper
swept it.

`@Transactional(REQUIRES_NEW)` on `advance` does **not** fix this: `advance` is called from
`applyResult` and `runInline` — self-invocations that skip the proxy, the exact hazard
`RunStepTransactions`' own Javadoc warns about. The fix follows that same rule from the other side:
`advance` runs its body inside `RunStepTransactions.inNewTransaction`, so it always gets a
persistence context with nothing already loaded. A fresh `@Modifying(clearAutomatically = true)`
hint on the repository was tried first and reverted — it clears the *bulk update's* context, not the
caller's, and it does not touch the first-level cache the stale read comes from.

## 4. Files touched

```
A  backend/boot/app/src/main/resources/db/migration/V2026.10.11.002__product_video_script_revision.sql
A  backend/modules/workflow/src/main/java/com/flowcortex/workflow/domain/ScriptRevision.java
A  backend/modules/workflow/src/main/java/com/flowcortex/workflow/infrastructure/ScriptRevisionRepository.java
A  backend/modules/workflow/src/main/java/com/flowcortex/workflow/api/ScriptRevisionView.java
A  backend/modules/workflow/src/main/java/com/flowcortex/workflow/web/ScriptRevisionResponse.java
A  backend/modules/workflow/src/main/java/com/flowcortex/workflow/web/CreateScriptRevisionRequest.java
M  backend/modules/workflow/src/main/java/com/flowcortex/workflow/domain/ReviewTask.java
M  backend/modules/workflow/src/main/java/com/flowcortex/workflow/api/ReviewTaskService.java
M  backend/modules/workflow/src/main/java/com/flowcortex/workflow/api/ReviewTaskView.java
M  backend/modules/workflow/src/main/java/com/flowcortex/workflow/application/ReviewTaskServiceImpl.java
M  backend/modules/workflow/src/main/java/com/flowcortex/workflow/application/ReviewCheckpointTransactions.java
M  backend/modules/workflow/src/main/java/com/flowcortex/workflow/application/RunAdvancer.java
M  backend/modules/workflow/src/main/java/com/flowcortex/workflow/application/RunStepTransactions.java
M  backend/modules/workflow/src/main/java/com/flowcortex/workflow/web/ApproveReviewTaskRequest.java
M  backend/modules/workflow/src/main/java/com/flowcortex/workflow/web/ReviewTaskResponse.java
M  backend/modules/workflow/src/main/java/com/flowcortex/workflow/web/ReviewTasksController.java
M  backend/modules/workflow/src/test/java/com/flowcortex/workflow/application/ReviewTaskServiceImplTest.java
A  backend/boot/app/src/test/java/com/flowcortex/app/workflow/ScriptRevisionApprovalIT.java
M  docs/openapi.yaml                                          (regenerated)
M  packages/api-client/src/generated/flowCortexApi.ts          (orval output)
A  frontend/src/features/workflow/ScriptEditor.tsx
A  frontend/src/features/workflow/lostRace.ts
M  frontend/src/features/workflow/ApprovalPanel.tsx
M  frontend/src/features/workflow/useWorkflows.ts
M  frontend/src/features/workflow/WorkflowPages.css
M  frontend/src/features/workflow/RunProgressPage.test.tsx
M  docs/ERD.md
A  docs/CHANGES/slice-3-script-revision.md
```

## 5. Test gates

| Gate | Command | Outcome (recorded 2026-10-11) |
|---|---|---|
| Slice 3 IT gate | `./gradlew :boot:app:test --tests 'com.flowcortex.app.workflow.ScriptRevisionApprovalIT'` | **9/9** |
| Workflow backend | `./gradlew :modules:workflow:test` | **pass** |
| OpenAPI snapshot | `./gradlew :boot:app:test --tests 'com.flowcortex.app.openapi.OpenApiSnapshotTest'` | **pass** (`docs/openapi.yaml` regenerated) |
| API client regen | `npm run gen:api` | clean; `getRevisions`, `saveRevision`, `ScriptRevisionResponse` |
| Frontend tests | `npm test` | **116/116** |
| Frontend type | `npm run typecheck` | clean |
| Frontend lint | `npm run lint` | clean |

The 9 IT cases are the three gate conditions from the plan plus the invariants around them: the
first revision exists and no revision is approved yet; an edit appends and moves the pointer; a
stale `expectedVersion` is a 409; an older revision cannot be approved once a newer one exists; a
second approval of the same revision loses; an approval without a revision on a script task is a
409; nothing is dispatched before approval; the speech job carries the approved revision and not
the original; a stranger gets 404 on read, edit and approve alike.

## 6. What did not change (intentional)

- **No `reviewedPayload` removal.** Tasks that keep no revisions still approve edited text; the
  two checkpoint shapes share one endpoint and one component.
- **No revision editing after approval.** The task leaves `pending` on the decision; there is no
  "reopen" path, so a revision is never mutated after the fact.
- **No revision diffing in the UI.** The panel lists revisions newest-last with a one-line preview
  and an "Approved" mark. A side-by-side diff is a UX call for Slice 5.
- **No reaper change.** The reaper already re-advances a stalled run; it was the symptom that
  surfaced the stale-read bug, not the defect.
- **No worker change.** The provider script contract is unchanged — revision 1 is the same string
  the provider returned.
- **No approval of an unsaved draft.** The approval action stays blocked while the editor is dirty,
  saving, or waiting for the task pointer to catch up with the latest revision.

## 7. Open follow-ups

- Slice 4 replaces the `generate_speech` placeholder with a real `audio.speech.create` call; the
  approved revision's content is already what reaches that step.
- The `originalPayload` on a script task still duplicates revision 1's content. It is kept because
  the approval panel renders it for tasks with no revisions and because dropping it would be a
  migration on data nothing else reads yet.
- When an OpenSpec CLI is available: lift §2 into `proposal.md` + spec deltas, and fold
  `R-REV-4`/`R-REV-5` into the review-checkpoint capability so drift-check covers them.
