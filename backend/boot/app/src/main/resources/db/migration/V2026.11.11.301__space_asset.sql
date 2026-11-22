-- Library assets and the queue that registers them. Both tables are owned by the asset module and
-- carry no foreign keys across modules: owners, spaces, runs and media objects are referenced by id
-- and checked in the application, as the other module tables do.

CREATE TABLE space_asset (
    id              uuid          PRIMARY KEY,
    space_id        uuid          NOT NULL,
    owner_id        uuid          NOT NULL,
    media_asset_id  uuid          NOT NULL,
    source          varchar(16)   NOT NULL CHECK (source IN ('run', 'upload')),
    kind            varchar(16)   NOT NULL CHECK (kind IN ('video', 'image', 'audio')),
    title           text          NOT NULL CHECK (char_length(title) BETWEEN 1 AND 200),
    title_base      text          NOT NULL,
    title_version   integer       NOT NULL CHECK (title_version >= 1),
    bucket          varchar(255)  NOT NULL,
    object_key      varchar(1024) NOT NULL,
    mime_type       varchar(255)  NOT NULL,
    bytes           bigint        NOT NULL CHECK (bytes >= 0),
    run_id          uuid          NOT NULL,
    run_step_id     uuid,
    workflow_id     uuid,
    workflow_name   text,
    created_at      timestamptz   NOT NULL,
    CONSTRAINT uq_space_asset_media_asset UNIQUE (media_asset_id),
    CONSTRAINT uq_space_asset_object UNIQUE (bucket, object_key),
    CONSTRAINT ck_space_asset_step CHECK ((source = 'run') = (run_step_id IS NOT NULL))
);
CREATE INDEX idx_space_asset_title_base ON space_asset (space_id, title_base) WHERE source = 'run';
CREATE INDEX idx_space_asset_run_id ON space_asset (run_id);

CREATE TABLE space_asset_registration (
    id               uuid        PRIMARY KEY,
    source_type      varchar(16) NOT NULL CHECK (source_type IN ('run_outputs', 'run_inputs')),
    source_id        uuid        NOT NULL,
    owner_id         uuid        NOT NULL,
    status           varchar(16) NOT NULL CHECK (status IN ('pending', 'done', 'skipped', 'stuck')),
    attempts         integer     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error_code  varchar(64),
    next_attempt_at  timestamptz,
    lease_until      timestamptz,
    lease_token      uuid,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    CONSTRAINT uq_space_asset_registration_source UNIQUE (source_type, source_id)
);
CREATE INDEX idx_space_asset_registration_due ON space_asset_registration (next_attempt_at) WHERE status = 'pending';
CREATE INDEX idx_space_asset_registration_finished ON space_asset_registration (updated_at) WHERE status IN ('done', 'skipped');
