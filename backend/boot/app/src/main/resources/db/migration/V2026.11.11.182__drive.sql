-- Drive: the items a member keeps in their personal space, the Memory entries projected from their
-- completed runs, and the one-off backfill ledger.
--
-- No foreign keys to other modules' tables (space, workflow_run, run_step, app_user): the module
-- boundary is kept at the schema level, like the other cross-module tables, so ownership and space
-- scope are enforced by the application.

CREATE TABLE drive_item (
    id             uuid PRIMARY KEY,
    space_id       uuid NOT NULL,
    owner_id       uuid NOT NULL,
    name           varchar(160) NOT NULL,
    content_type   varchar(16)  NOT NULL CHECK (content_type IN ('text','image','video','audio')),
    semantic_role  varchar(40)  NOT NULL,
    text_value     text,
    source         varchar(16)  NOT NULL CHECK (source IN ('text','upload','run_output')),
    bucket         varchar(255),
    object_key     varchar(1024),
    file_name      varchar(255),
    mime_type      varchar(255),
    size_bytes     bigint CHECK (size_bytes >= 0),
    run_id         uuid,
    step_order     smallint,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    CONSTRAINT ck_drive_item_payload CHECK (
        (source = 'text' AND content_type = 'text' AND text_value IS NOT NULL AND object_key IS NULL)
        OR (source = 'upload' AND content_type <> 'text' AND text_value IS NULL AND object_key IS NOT NULL AND run_id IS NULL)
        OR (source = 'run_output' AND content_type <> 'text' AND text_value IS NULL AND object_key IS NOT NULL
            AND run_id IS NOT NULL AND step_order IS NOT NULL))
);
CREATE INDEX idx_drive_item_space_created ON drive_item (space_id, created_at DESC, id DESC);

CREATE TABLE memory_result (
    id            uuid PRIMARY KEY,
    space_id      uuid NOT NULL,
    owner_id      uuid NOT NULL,
    run_id        uuid NOT NULL,
    run_step_id   uuid NOT NULL,
    step_order    smallint NOT NULL,
    result_key    varchar(300) NOT NULL,
    kind          varchar(8) NOT NULL CHECK (kind IN ('text','image','video','audio')),
    title         varchar(200) NOT NULL,
    preview_text  varchar(500),
    bucket        varchar(255),
    object_key    varchar(1024),
    file_name     varchar(255),
    mime_type     varchar(255),
    size_bytes    bigint NOT NULL CHECK (size_bytes >= 0),
    result_at     timestamptz NOT NULL,
    pinned        boolean NOT NULL DEFAULT false,
    hidden        boolean NOT NULL DEFAULT false,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    CONSTRAINT uq_memory_result_key UNIQUE (space_id, result_key)
);
CREATE INDEX idx_memory_result_space_time ON memory_result (space_id, result_at DESC) WHERE NOT hidden;
CREATE INDEX idx_memory_result_space_pinned ON memory_result (space_id, result_at DESC) WHERE pinned AND NOT hidden;
CREATE INDEX idx_memory_result_run ON memory_result (run_id);

CREATE TABLE drive_backfill (
    space_id       uuid PRIMARY KEY,
    owner_id       uuid NOT NULL,
    migrated_at    timestamptz,
    counts         jsonb,
    reconciled_at  timestamptz,
    ok             boolean,
    updated_at     timestamptz NOT NULL
);
