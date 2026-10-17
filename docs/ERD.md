# Entity–Relationship Diagram — Flow-Cortex

Source of truth for the schema is the Flyway migration set under
`backend/boot/app/src/main/resources/db/migration/` (ADR-0004: migration-first,
Hibernate `ddl-auto: validate`). This diagram mirrors those files and is updated
in the same PR as any migration that changes it. Column notes list only what a
reader cannot infer from the name.

## Current schema (`V2026.09.22.001` – `V2026.10.17.004`)

```mermaid
erDiagram
  app_user ||--o{ workflow : "owner_id (no FK)"
  app_user ||--o{ workflow_run : "owner_id (no FK)"
  workflow ||--|{ workflow_step : "workflow_id"
  workflow ||--o{ workflow_run : "workflow_id"
  workflow_run ||--|{ run_step : "run_id"
  workflow_run ||--o{ media_asset_binding : "run_id"
  workflow_run ||--o| product_video_input : "run_id"
  workflow_run ||--o{ review_task : "run_id"
  run_step ||--o| review_task : "run_step_id, UNIQUE — one checkpoint per step"
  review_task ||--o{ script_revision : "review_task_id"
  media_asset ||--o{ media_asset_binding : "asset_id"

  app_user {
    uuid id PK
    uuid owner_id "administering ADMIN; an ADMIN is self-owned"
    varchar email UK "global uniqueness — login has no scope input"
    varchar password_hash
    varchar name
    varchar role "ADMIN | MEMBER"
    varchar status "ACTIVE | SUSPENDED"
    timestamptz created_at
    timestamptz updated_at
  }
  workflow {
    uuid id PK
    uuid owner_id
    varchar name
    boolean is_shared "true = listed to every authenticated user"
    timestamptz created_at
    timestamptz updated_at
  }
  workflow_step {
    uuid id PK
    uuid workflow_id FK
    smallint step_order "UNIQUE (workflow_id, step_order), contiguous from 1"
    varchar name
    varchar type "inline, provider remote, media remote, or APPROVAL_CHECKPOINT"
    jsonb config
    varchar input_ref "{step:K.field}; NULL = previous step's output"
  }
  workflow_run {
    uuid id PK
    uuid workflow_id FK
    uuid owner_id "runs are always owner-scoped, even for shared workflows"
    varchar status "running | completed | error"
    text input
    text error_message
    timestamptz started_at
    timestamptz finished_at
    timestamptz last_swept_at "reaper's stalled-run sweep; NULL = never (NULLS FIRST)"
  }
  run_step {
    uuid id PK
    uuid run_id FK
    smallint step_order "UNIQUE (run_id, step_order)"
    varchar type
    varchar status "pending | running | completed | error — claim = UPDATE ... WHERE status = 'pending'"
    text input "resolved input"
    text output
    text error_message
    timestamptz started_at "claim time"
    timestamptz finished_at
    timestamptz queue_deadline "set at claim — unpicked past it = queue_timeout"
    timestamptz picked_up_at "first pickup; NULL = still queued"
    timestamptz lease_until "extended by each heartbeat — lapsed = lease_expired"
    timestamptz run_deadline "set by each delivery's started — passed = run_timeout"
    smallint progress_percent "CHECK 0–100"
    smallint attempt "DEFAULT 1; API-owned fencing token every result CAS checks, constant 1 here"
    smallint delivery_count "DEFAULT 0; broker redeliveries, display only"
    varchar error_code "queue_timeout | lease_expired | run_timeout | media_disabled | input_rejected | probe_failed | contract_violation"
    jsonb outputs "MediaOutput[] {name, bucket, objectKey, mime, bytes, meta} — object keys runs/{runId}/steps/{order}/{name}"
  }
  media_asset {
    uuid id PK "upload id for uploaded inputs"
    uuid owner_id "application-enforced owner scope"
    varchar kind "image | audio | video | binary"
    varchar bucket
    varchar object_key UK
    varchar mime_type
    bigint bytes
    varchar checksum "object-store SHA-256 when available"
    varchar status "ready"
    timestamptz expires_at "NULL until a retention policy is assigned"
    uuid source_run_step_id "output provenance; NULL for uploads"
    smallint source_attempt "attempt fence; NULL for uploads"
    timestamptz created_at
  }
  media_asset_binding {
    uuid run_id PK,FK
    varchar binding_key PK "image_1 .. image_10"
    uuid asset_id FK
    smallint position "UNIQUE (run_id, position), preserves request order"
    timestamptz created_at
  }
  product_video_input {
    uuid run_id PK,FK
    varchar product_name
    varchar description
    varchar cta
    varchar price "optional confirmed claim"
    varchar promotion "optional confirmed claim"
    varchar aspect_ratio "9:16 in MVP"
    timestamptz created_at
  }
  review_task {
    uuid id PK
    uuid run_id FK "ON DELETE CASCADE"
    uuid run_step_id FK,UK "ON DELETE CASCADE — a step pauses at most once"
    varchar review_type "APPROVAL_CHECKPOINT"
    varchar status "pending | approved | rejected"
    text original_payload "the checkpoint's input, unwrapped from the provider result"
    text reviewed_payload "plain checkpoints only; a script task approves a revision instead"
    uuid reviewed_by
    timestamptz reviewed_at
    timestamptz created_at
    bigint version "JPA @Version — the decision's optimistic lock"
    uuid current_revision_id FK "the revision on screen; NULL = task keeps no revisions"
    uuid approved_revision_id FK "the revision that was approved; NULL until a decision"
  }
  script_revision {
    uuid id PK
    uuid run_id FK "ON DELETE CASCADE"
    uuid review_task_id FK "ON DELETE CASCADE"
    integer revision_number "UNIQUE (review_task_id, revision_number), CHECK > 0"
    text content "CHECK length(btrim(content)) > 0 — revisions are never blank"
    uuid created_by "NULL for revision 1, which the provider wrote"
    timestamptz created_at
  }
  event_publication {
    uuid id PK "Spring Modulith event publication registry (transactional outbox)"
    text listener_id
    text event_type
    text serialized_event "hash index — idempotency lookup"
    timestamptz publication_date
    timestamptz completion_date "index — recovery query on restart"
    text status
    integer completion_attempts
    timestamptz last_resubmission_date
  }
```

Indexes: `idx_app_user_owner_id`, `idx_workflow_owner_id`, `idx_workflow_run_owner_id`,
`idx_workflow_run_workflow_id`, `idx_workflow_run_started_at` (`started_at DESC, id DESC` — the admin Runs
list, newest first), `idx_run_step_run_id`, `idx_run_step_running` (partial, `WHERE status = 'running'` —
the reaper scan), `idx_review_task_run_id`, `idx_script_revision_run`,
`idx_event_publication_serialized_event` (hash), `idx_event_publication_completion_date`.

`owner_id` columns carry no foreign key on purpose: the user module owns `app_user`, the workflow module
owns the other tables, and the module boundary (ADR-0003) is kept at the schema level too — ownership is
enforced in the application, not by a cross-module constraint.

`run_step` *is* the media job: its `id` is the `jobId` on the broker, so no separate job table exists, and
no upload table either — an upload is an object at a server-derived key. `input_rejected` = the uploaded
object was missing or over size when the worker checked it. Objects never live in the database: uploads in
the bucket `flowcortex-uploads`, the objects `outputs` references in `flowcortex-media`.

`script_revision` is append-only: an edit inserts a row and moves `review_task.current_revision_id`
forward, never overwrites one. Approval names a revision id and the task `version` it read, so a browser
tab holding older text loses the approval with a 409 instead of approving something it never showed. The
payload `generate_speech` consumes is the approved revision's content, not whatever sits in the task.

## Roadmap tables (each arrives with its own change)

| Table | Purpose | Arrives with |
|---|---|---|
| `provider` | provider catalogue + fallback order per capability | Providers page / first real capability |
| `usage_event` | per-step provider units and cost | first paid provider |
| `audit_log` | admin actions with before/after | first admin write beyond Users |
| `app_setting`, `settings_audit` | DB-backed runtime settings (override > env > default) | Settings page |
| `channel_account` | publish targets with encrypted tokens | Publish step |
