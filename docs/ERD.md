# Entity–Relationship Diagram — Flow-Cortex

Source of truth for the schema is the Flyway migration set under
`backend/boot/app/src/main/resources/db/migration/` (ADR-0004: migration-first,
Hibernate `ddl-auto: validate`). This diagram mirrors those files and is updated
in the same PR as any migration that changes it. Column notes list only what a
reader cannot infer from the name.

## Current schema (`V2026.09.22.001` – `V2026.11.11.280`)

```mermaid
erDiagram
  app_user ||--o{ workflow : "owner_id (no FK)"
  app_user ||--o{ workflow_run : "owner_id (no FK)"
  app_user ||--o{ step_up_proof : "user_id, ON DELETE CASCADE"
  app_user ||--o{ personal_access_token : "user_id, ON DELETE CASCADE"
  workflow ||--|{ workflow_step : "workflow_id"
  workflow ||--o{ workflow_run : "workflow_id"
  workflow_run ||--|{ run_step : "run_id"
  workflow_run ||--o{ media_asset_binding : "run_id"
  workflow_run ||--o| product_video_input : "run_id"
  workflow_run ||--o{ review_task : "run_id"
  run_step ||--o| review_task : "run_step_id, UNIQUE — one checkpoint per step"
  review_task ||--o{ script_revision : "review_task_id"
  media_asset ||--o{ media_asset_binding : "asset_id"
  workflow ||--o{ workflow_bookmark : "workflow_id"
  workflow ||--o{ workflow_review : "workflow_id"
  workflow_review ||--o{ workflow_review_image : "review_id, ON DELETE CASCADE"
  workflow_review ||--o{ workflow_review_helpful : "review_id, ON DELETE CASCADE"
  workflow_review ||--o{ workflow_review_report : "review_id, ON DELETE CASCADE"
  media_asset ||--o| space_asset : "media_asset_id, UNIQUE (no FK)"

  app_user {
    uuid id PK
    uuid owner_id "administering ADMIN; an ADMIN is self-owned"
    varchar email UK "global uniqueness — login has no scope input"
    varchar password_hash
    varchar name
    varchar role "ADMIN | MEMBER"
    varchar status "ACTIVE | SUSPENDED"
    timestamptz username_changed_at "NULL until the first username change; the 30-day cooldown reads it"
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
    varchar type "inline, provider remote, media remote, APPROVAL_CHECKPOINT or qa.review"
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
    jsonb config "copied from workflow_step when the run starts; dispatch reads only this copy"
    varchar input_ref "copied from workflow_step when the run starts"
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
    varchar error_code "queue_timeout | lease_expired | run_timeout | media_disabled | input_rejected | probe_failed | contract_violation | speech_too_long | workflow_changed"
    jsonb outputs "MediaOutput[] {name, bucket, objectKey, mime, bytes, meta} — object keys runs/{runId}/steps/{order}/{name}"
  }
  media_asset {
    uuid id PK "upload id for uploaded inputs"
    uuid owner_id "application-enforced owner scope"
    uuid space_id "personal space of the owner, NOT NULL, no FK"
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
  space_asset {
    uuid id PK
    uuid space_id "owning space, no FK"
    uuid owner_id "no FK"
    uuid media_asset_id UK "the stored file; an upload asset points at the run's copy, no FK"
    varchar source "run | upload"
    varchar kind "video | image | audio"
    text title "1-200 code points"
    text title_base "run titles: the name before the (N) and index suffixes"
    integer title_version "N of the (N) suffix, 1 = none"
    varchar bucket "UNIQUE with object_key"
    varchar object_key
    varchar mime_type
    bigint bytes
    uuid run_id "producing run, or the run that bound the upload"
    uuid run_step_id "NOT NULL exactly when source = run"
    uuid workflow_id
    text workflow_name "copied at registration"
    timestamptz created_at
  }
  space_asset_registration {
    uuid id PK
    varchar source_type "run_outputs | run_inputs, UNIQUE with source_id"
    uuid source_id "run id"
    uuid owner_id
    varchar status "pending | done | skipped | stuck"
    integer attempts "stuck at 288"
    varchar last_error_code
    timestamptz next_attempt_at "partial index WHERE status = pending"
    timestamptz lease_until "5 minutes from claim"
    uuid lease_token "fences finish and retry"
    timestamptz created_at
    timestamptz updated_at "finished rows deleted after 30 days"
  }
  trash_entry {
    uuid id PK
    varchar item_type "lowercase key, UNIQUE with item_id; the owning module's provider type"
    varchar item_id "the trashed row's id in its own module, no FK"
    uuid owner_id "the account that sees and acts on the entry, no FK"
    uuid deleted_by "no FK"
    text name "1-200 characters; NULL = untitled"
    text name_key "folded search key written by Java; empty when untitled"
    varchar location_code "message code of where the item lived"
    timestamptz deleted_at "expiry = deleted_at + retention, never stored"
    timestamptz reminded_at "NULL until the expiry reminder claims it; cleared when the item is deleted again"
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
    varchar review_type "APPROVAL_CHECKPOINT | QA_REVIEW"
    varchar status "pending | approved | rejected | expired"
    text original_payload "the checkpoint's input, unwrapped from the provider result"
    text reviewed_payload "plain checkpoints only; a script task approves a revision instead"
    uuid reviewed_by
    timestamptz reviewed_at
    timestamptz created_at
    bigint version "JPA @Version — the decision's optimistic lock"
    uuid current_revision_id FK "the revision on screen; NULL = task keeps no revisions"
    uuid approved_revision_id FK "the revision that was approved; NULL until a decision"
    timestamptz expires_at "time limit; a pending task past it is ended expired by the sweep"
    jsonb questions "QA_REVIEW only: the questions shown"
    text questions_state "generating | ready; NOT NULL exactly when review_type = QA_REVIEW"
    jsonb answers "QA_REVIEW only: the answers given"
    text decision_key "Idempotency-Key of the decision that ended the task, 1-80 chars"
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
  step_up_proof {
    uuid id PK
    uuid user_id FK "ON DELETE CASCADE"
    varchar purpose "closed list in code (StepUpPurpose), no CHECK"
    varchar token_hash UK "SHA-256 hex of the proof; the proof itself is never stored"
    timestamptz created_at
    timestamptz expires_at "index — retention scan"
    timestamptz consumed_at "set once by a conditional UPDATE; NULL = unspent"
  }
  personal_access_token {
    uuid id PK
    uuid user_id FK "ON DELETE CASCADE"
    varchar name "1-128 characters, chosen by the owner"
    varchar token_hash UK "SHA-256 hex of the fc_pat_ token; the token itself is never stored"
    timestamptz created_at
    timestamptz expires_at "1-365 days after creation"
    timestamptz last_used_at "NULL until first use; written at most once a minute"
    timestamptz revoked_at "NULL = not revoked; soft, the retention job deletes the row"
  }
  workflow_bookmark {
    uuid user_id PK "no FK — the user table belongs to another module"
    uuid workflow_id PK,FK
    timestamptz created_at "kept when the workflow leaves the member's sight, so it returns with its date"
  }
  workflow_review {
    uuid id PK
    uuid workflow_id FK "UNIQUE (workflow_id, user_id) — one review per member"
    uuid user_id "no FK"
    smallint rating "CHECK 1..5"
    varchar body "up to 5000, NULL when blank"
    integer helpful_count "CHECK >= 0; moved only by additive updates beside a mark insert or delete"
    timestamptz created_at
    timestamptz updated_at
  }
  workflow_review_image {
    uuid id PK
    uuid review_id FK
    smallint position "1..5, UNIQUE (review_id, position) DEFERRABLE INITIALLY DEFERRED"
    varchar bucket
    varchar object_key "workflow-review/<workflowId>/<userId>/<batch>/..."
    varchar content_type
    bigint size_bytes
    timestamptz created_at
  }
  workflow_review_helpful {
    uuid review_id PK,FK
    uuid user_id PK "no FK"
    timestamptz created_at
  }
  workflow_review_report {
    uuid review_id PK,FK
    uuid reporter_id PK "no FK; one report per reporter"
    varchar reason "spam | scam | harmful | harassment | misinformation | copyright | nsfw | other"
    varchar details "up to 1000"
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
  drive_item {
    uuid id PK
    uuid space_id "personal space of the owner, no FK; INDEX (space_id, created_at DESC, id DESC)"
    uuid owner_id "no FK"
    varchar name "1-160 chars, sent by the client"
    varchar content_type "text | image | video | audio"
    varchar semantic_role "role vocabulary checked by the application"
    text text_value "text items only"
    varchar source "text | upload | run_output — CHECK ck_drive_item_payload ties source to the columns that must be set or empty"
    varchar bucket "media items"
    varchar object_key "upload: drive/{spaceId}/{itemId}; run_output: the run's own output key"
    varchar file_name
    varchar mime_type
    bigint size_bytes
    uuid run_id "run_output only, no FK"
    smallint step_order "run_output only"
    timestamptz created_at
    timestamptz updated_at
  }
  memory_result {
    uuid id PK
    uuid space_id "UNIQUE (space_id, result_key)"
    uuid owner_id "no FK"
    uuid run_id "INDEX; no FK"
    uuid run_step_id "no FK"
    smallint step_order
    varchar result_key "run_step_id for text, run_step_id/outputName for media — stable across attempts"
    varchar kind "text | image | video | audio"
    varchar title
    varchar preview_text "text results, first 500 chars"
    varchar bucket "media results"
    varchar object_key "media results"
    varchar file_name
    varchar mime_type
    bigint size_bytes "text: UTF-8 byte length"
    timestamptz result_at "run finished_at"
    boolean pinned "member-owned, survives re-population"
    boolean hidden "member-owned, survives re-population"
    timestamptz created_at
    timestamptz updated_at
  }
  drive_backfill {
    uuid space_id PK
    uuid owner_id
    timestamptz migrated_at "NULL until an apply inserted an entry for the space"
    jsonb counts "{runs, results} inserted by applies"
    timestamptz reconciled_at
    boolean ok
    timestamptz updated_at
  }
```

Indexes: `idx_app_user_owner_id`, `idx_workflow_owner_id`, `idx_workflow_run_owner_id`,
`idx_workflow_run_workflow_id`, `idx_workflow_run_started_at` (`started_at DESC, id DESC` — the admin Runs
list, newest first), `idx_run_step_run_id`, `idx_run_step_running` (partial, `WHERE status = 'running'` —
the reaper scan), `idx_review_task_run_id`, `idx_review_task_pending_expiry` (partial, `WHERE status = 'pending'` — the
expiry sweep), `idx_script_revision_run`,
`idx_step_up_proof_expires_at`, `idx_pat_user_live` (partial, `WHERE revoked_at IS NULL`), `idx_event_publication_serialized_event` (hash),
`idx_event_publication_completion_date`,
`idx_workflow_bookmark_page` (`user_id, created_at DESC, workflow_id DESC` — the saved list),
`idx_workflow_review_newest` (`workflow_id, created_at DESC, id DESC`), `idx_workflow_review_helpful`
(`workflow_id, helpful_count DESC, created_at DESC, id DESC`).

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

## Profile settings and images (`V2026.11.11.090` – `V2026.11.11.091`)

```mermaid
erDiagram
  app_user ||--o{ username_hold : "user_id, ON DELETE CASCADE"
  app_user ||--o| account_preference : "user_id, ON DELETE CASCADE"
  app_user ||--o{ profile_image : "user_id (no FK)"

  username_hold {
    varchar username PK "one row per released name"
    uuid user_id FK "former owner; may take the name back"
    timestamptz expires_at "hold ends; an expired row is overwritten by the next hold"
    timestamptz created_at
  }
  account_preference {
    uuid user_id PK, FK
    varchar locale "vi | en | NULL = follow the device"
    jsonb ui "closed key set validated in Java"
    jsonb privacy "closed key set validated in Java"
    timestamptz updated_at
  }
  profile_image {
    uuid id PK
    uuid user_id "no FK: owned by the profile module"
    varchar slot "avatar | cover"
    varchar role "original (history) | current (shown)"
    varchar object_key UK
    varchar mime_type
    integer width
    integer height
    bigint bytes
    timestamptz created_at
  }
```

Indexes: `uq_profile_image_current` (unique, partial `WHERE role = 'current'` — one shown image per member
and slot, which also makes concurrent saves exactly-once), `idx_profile_image_history` (partial
`WHERE role = 'original'`, newest first).

`username_hold` and `account_preference` live in the user module and cascade with `app_user`.
`profile_image` belongs to the profile module and carries no foreign key to `app_user`, like the other
cross-module tables: the module boundary is kept at the schema level, and a deleted account leaves its
rows behind.

## MCP authorization server and token call guards (`V2026.11.15.225` – `V2026.11.15.226`)

```mermaid
erDiagram
  oauth_client ||--o{ oauth_authorization_code : "client_id, ON DELETE CASCADE"
  oauth_client ||--o{ oauth_issued_token : "client_id, ON DELETE CASCADE"
  app_user ||--o{ oauth_authorization_code : "user_id, ON DELETE CASCADE"
  personal_access_token ||--o| oauth_issued_token : "token_id, ON DELETE CASCADE"

  oauth_client {
    varchar client_id PK "mcp_ + 24 hex"
    varchar client_secret_hash "SHA-256 hex; NULL for a public client (auth_method none)"
    varchar client_name "1-128 characters, shown on the consent page"
    jsonb redirect_uris "1-5 URIs validated in Java"
    varchar auth_method "client_secret_post | client_secret_basic | none"
    timestamptz created_at "index — unused-client purge"
  }
  oauth_authorization_code {
    varchar code_hash PK "SHA-256 hex of the code; the code itself is never stored"
    varchar client_id FK "ON DELETE CASCADE"
    uuid user_id FK "ON DELETE CASCADE"
    varchar redirect_uri "the exact URI the consent accepted"
    varchar code_challenge "PKCE S256"
    varchar scope
    timestamptz created_at
    timestamptz expires_at "5 minutes after consent; index — retention scan"
    timestamptz redeemed_at "set once by a conditional UPDATE; NULL = unspent"
    uuid issued_token_id "no FK: the token row may be purged before the code row"
  }
  oauth_issued_token {
    uuid token_id PK, FK "personal_access_token, ON DELETE CASCADE"
    varchar client_id FK "ON DELETE CASCADE; a client with a row here is never purged"
    timestamptz issued_at
  }
  api_token_call {
    uuid id PK
    uuid user_id "no FK: rows outlive tokens and accounts until retention"
    uuid token_id
    varchar method
    varchar route "normalized pattern, never the query or body"
    varchar outcome "ok | error | blocked | replayed"
    smallint http_status
    varchar error_code
    integer duration_ms
    timestamptz occurred_at "index — daily cap count and 90-day purge"
  }
  api_token_idempotency {
    uuid user_id PK "no FK"
    varchar idem_key PK "1-80 characters from the Idempotency-Key header"
    varchar fingerprint "SHA-256 hex of method, path, query and body"
    varchar state "running | done"
    timestamptz reserved_at "a running reservation older than 5 minutes is taken over"
    smallint http_status
    varchar content_type
    bytea response_body "the stored answer, at most 256 KiB"
    timestamptz expires_at "24 hours after the reservation; index — retention scan"
  }
```

Indexes: `idx_oauth_client_created_at`, `idx_oauth_code_expires_at`, `idx_oauth_issued_token_client`,
`idx_api_token_call_user_time` (`user_id, occurred_at`), `idx_api_token_call_occurred_at`,
`idx_api_token_idempotency_expires_at`.

`api_token_call` and `api_token_idempotency` carry no foreign key on purpose: the audit trail and the
stored answers outlive the token and the account until the retention job deletes them.
`user.mcp-oauth-retention` deletes spent codes, unused clients, old call rows and ended reservations.

## Runtime settings (`V2026.11.11.180`, `V2026.11.11.320`)

```mermaid
erDiagram
  app_setting {
    varchar key PK "declared in code; a row exists only while an admin value is stored"
    text value "canonical text; the type lives in the key's declaration"
    uuid updated_by "no FK — deleting an account keeps the history"
    timestamptz updated_at
  }
  app_setting_change {
    uuid id PK
    varchar key "no FK; index idx_app_setting_change_key_at (key, changed_at DESC, id DESC)"
    text previous_value "NULL = no stored value before"
    text new_value "NULL = the stored value was reset"
    uuid changed_by "no FK"
    timestamptz changed_at
    varchar reason "NULL; varchar(2000), trimmed, NULL when blank — written by the payment console only"
  }
```

`app_setting_change` is append-only history; one row is committed in the transaction that stores or resets
the value, and a write of the value already stored records none.

## Batch runs (`V2026.11.15.310`)

```mermaid
erDiagram
  app_user ||--o{ batch_run : "owner_id (no FK)"
  workflow ||--o{ batch_run : "workflow_id"
  batch_run ||--o| batch_run : "parent_batch_id, ON DELETE SET NULL"
  batch_run ||--|{ batch_run_row : "batch_id, ON DELETE CASCADE"
  workflow_run ||--o| batch_run_row : "run_id, ON DELETE SET NULL"

  batch_run {
    uuid id PK
    uuid owner_id "no FK, like workflow_run.owner_id"
    uuid workflow_id FK
    uuid parent_batch_id FK "the batch a retry was made from; unique where set"
    varchar name "NULL = unnamed; varchar(200)"
    smallint concurrency "1-10"
    boolean acknowledge_cost
    varchar status "running | paused | completed | completed_with_errors | cancelled"
    varchar pause_reason "member | insufficientCredits | unavailable"
    varchar cancel_reason "member | workflowUnavailable | ownerInactive"
    integer total_rows
    timestamptz created_at
    timestamptz finished_at "set once no row is pending, starting or running"
    uuid lease_token "holder of the dispatch lease; every dispatcher write carries it"
    timestamptz lease_until
    timestamptz dispatched_at "last dispatch pass; oldest first, NULL first"
  }
  batch_run_row {
    uuid id PK
    uuid batch_id FK
    integer row_index "unique per batch"
    integer source_row_index "set on a retry batch's rows"
    text input
    varchar status "pending | starting | running | completed | failed | cancelled; starting is internal, shown as pending"
    uuid run_id FK "NULL until the run is recorded, and after the run is removed"
    smallint start_failures
    boolean stop_requested
    varchar error_code
    varchar error_message "varchar(300)"
    timestamptz claimed_at "when the row was claimed for start"
    timestamptz started_at
    timestamptz finished_at
  }
```

Indexes: `idx_batch_run_owner_created` (`owner_id, created_at DESC, id DESC` — the member's list),
`idx_batch_run_open` (partial `WHERE finished_at IS NULL`, `dispatched_at NULLS FIRST` — the dispatcher's
candidates), `uq_batch_run_parent` (unique, partial `WHERE parent_batch_id IS NOT NULL` — one retry per batch),
`idx_batch_run_row_open` (partial `WHERE status IN ('pending','starting','running')`, `batch_id, row_index`),
`idx_batch_run_row_status` (`batch_id, status, row_index` — the grouped counts and the row list).

Row counts are not stored on `batch_run`; they are one grouped read over `idx_batch_run_row_status`.

## Roadmap tables (each arrives with its own change)

| Table | Purpose | Arrives with |
|---|---|---|
| `provider` | provider catalogue + fallback order per capability | Providers page / first real capability |
| `usage_event` | per-step provider units and cost | first paid provider |
| `audit_log` | admin actions with before/after | first admin write beyond Users |
| `channel_account` | publish targets with encrypted tokens | Publish step |
