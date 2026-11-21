-- Workflow versions: immutable definition snapshots, per-member pins, and the pointer a
-- non-owner is served.
--
-- Order: this migration runs before the deploy that reads the tables. It is additive and
-- two-way: the previous image ignores the new tables and the new nullable column, so rollback
-- is "redeploy the previous image".
--
-- Proof query after the upgrade (must return 0):
--   SELECT count(*) FROM workflow w
--    WHERE w.deleted_at IS NULL AND w.is_shared AND w.status = 'published'
--      AND w.owner_id <> '00000000-0000-0000-0000-000000000000'
--      AND EXISTS (SELECT 1 FROM workflow_step s WHERE s.workflow_id = w.id)
--      AND w.published_version_id IS NULL;
--
-- Locks: the foreign keys onto workflow and the ADD CONSTRAINT take SHARE ROW EXCLUSIVE on it, which
-- queues behind any open writer and then blocks every writer behind it. The lock_timeout makes the
-- migration fail fast (the deploy retries) instead of stalling workflow writes. The backfill below
-- touches only shared, published, member-owned workflows with steps, which no released code path creates.

SET LOCAL lock_timeout = '10s';

CREATE TABLE workflow_version (
    id                    uuid         PRIMARY KEY,
    workflow_id           uuid         NOT NULL REFERENCES workflow (id),
    version               integer      NOT NULL CHECK (version > 0),
    changelog             varchar(200),
    source                varchar(20)  NOT NULL CHECK (source IN ('manual', 'publish', 'rollback', 'hotfix')),
    restored_from_version integer,
    snapshot              jsonb        NOT NULL,
    published_by          uuid         NOT NULL,
    published_at          timestamptz  NOT NULL,
    released_at           timestamptz,
    CONSTRAINT uq_workflow_version_number UNIQUE (workflow_id, version),
    CONSTRAINT uq_workflow_version_workflow_id UNIQUE (workflow_id, id),
    CONSTRAINT ck_workflow_version_rollback CHECK ((source = 'rollback') = (restored_from_version IS NOT NULL))
);

CREATE TABLE workflow_version_pin (
    user_id        uuid         NOT NULL,
    workflow_id    uuid         NOT NULL,
    pinned_version integer      NOT NULL,
    reason         varchar(200),
    pinned_at      timestamptz  NOT NULL,
    PRIMARY KEY (user_id, workflow_id),
    CONSTRAINT fk_workflow_version_pin_version FOREIGN KEY (workflow_id, pinned_version)
        REFERENCES workflow_version (workflow_id, version)
);

ALTER TABLE workflow ADD COLUMN published_version_id uuid;
ALTER TABLE workflow ADD CONSTRAINT fk_workflow_published_version FOREIGN KEY (id, published_version_id)
    REFERENCES workflow_version (workflow_id, id);

-- backfill:begin
WITH eligible AS (
    SELECT w.id, w.owner_id, w.name
      FROM workflow w
     WHERE w.deleted_at IS NULL
       AND w.is_shared
       AND w.status = 'published'
       AND w.owner_id <> '00000000-0000-0000-0000-000000000000'
       AND w.published_version_id IS NULL
       AND EXISTS (SELECT 1 FROM workflow_step s WHERE s.workflow_id = w.id)
       AND NOT EXISTS (SELECT 1 FROM workflow_version v WHERE v.workflow_id = w.id)
), ins AS (
    INSERT INTO workflow_version (id, workflow_id, version, changelog, source, snapshot, published_by,
                                  published_at, released_at)
    SELECT gen_random_uuid(), e.id, 1, NULL, 'publish',
           jsonb_build_object(
               'name', e.name,
               'steps', (SELECT jsonb_agg(jsonb_build_object(
                                     'id', s.id,
                                     'stepOrder', s.step_order::int,
                                     'name', s.name,
                                     'type', s.type,
                                     'config', s.config,
                                     'inputRef', s.input_ref) ORDER BY s.step_order)
                           FROM workflow_step s WHERE s.workflow_id = e.id)),
           e.owner_id, now(), now()
      FROM eligible e
    RETURNING id, workflow_id
)
UPDATE workflow w SET published_version_id = ins.id FROM ins WHERE w.id = ins.workflow_id;
-- backfill:end
