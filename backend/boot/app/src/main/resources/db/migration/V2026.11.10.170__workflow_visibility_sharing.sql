-- Workflow visibility tiers (private | unlisted | public), the unlisted share key, link grants,
-- the access log and the visibility audit.
--
-- Order: this migration runs before the deploy that reads it. is_shared is kept and written as
-- (visibility = 'public') by the application until a later contract migration drops it, so
-- rolling back to the previous image keeps a schema that image understands.

ALTER TABLE workflow
    ADD COLUMN visibility varchar(20) NOT NULL DEFAULT 'private',
    ADD COLUMN unlisted_key varchar(32),
    ADD COLUMN unlisted_key_generated_at timestamptz,
    ADD COLUMN unlisted_key_expires_at timestamptz;

-- Backfill before the CHECKs so they validate against the final values.
UPDATE workflow SET visibility = 'public' WHERE is_shared;

ALTER TABLE workflow
    ADD CONSTRAINT ck_workflow_visibility CHECK (visibility IN ('private', 'unlisted', 'public')),
    ADD CONSTRAINT ck_workflow_unlisted_key CHECK ((visibility = 'unlisted') = (unlisted_key IS NOT NULL));

CREATE UNIQUE INDEX uq_workflow_unlisted_key ON workflow (unlisted_key) WHERE unlisted_key IS NOT NULL;

-- user_id, accessed_by and changed_by carry no foreign key: the user table belongs to another module.
CREATE TABLE workflow_link_grant (
    workflow_id uuid        NOT NULL REFERENCES workflow (id) ON DELETE CASCADE,
    user_id     uuid        NOT NULL,
    created_at  timestamptz NOT NULL,
    PRIMARY KEY (workflow_id, user_id)
);
CREATE INDEX idx_workflow_link_grant_page ON workflow_link_grant (workflow_id, created_at DESC, user_id DESC);

CREATE TABLE workflow_access_log (
    id           uuid        PRIMARY KEY,
    workflow_id  uuid        NOT NULL REFERENCES workflow (id) ON DELETE CASCADE,
    accessed_by  uuid,
    network      varchar(64),
    user_agent   varchar(512),
    accessor_key varchar(80) NOT NULL,
    window_start timestamptz NOT NULL,
    created_at   timestamptz NOT NULL,
    CONSTRAINT uq_workflow_access_log_window UNIQUE (workflow_id, accessor_key, window_start)
);
CREATE INDEX idx_workflow_access_log_page ON workflow_access_log (workflow_id, created_at DESC, id DESC);

CREATE TABLE workflow_visibility_audit (
    id              uuid        PRIMARY KEY,
    workflow_id     uuid        NOT NULL REFERENCES workflow (id) ON DELETE CASCADE,
    changed_by      uuid        NOT NULL,
    from_visibility varchar(20) NOT NULL,
    to_visibility   varchar(20) NOT NULL,
    triggered_by    varchar(20) NOT NULL CHECK (triggered_by IN ('owner', 'admin', 'key_regenerated')),
    network         varchar(64),
    user_agent      varchar(512),
    created_at      timestamptz NOT NULL
);
CREATE INDEX idx_workflow_visibility_audit_page ON workflow_visibility_audit (workflow_id, created_at DESC, id DESC);
