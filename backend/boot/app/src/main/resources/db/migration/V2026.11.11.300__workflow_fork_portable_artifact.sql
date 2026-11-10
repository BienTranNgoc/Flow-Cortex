-- Workflow forking and the portable artifact: a per-workflow fork switch, a fork counter and
-- source link, and the review ledger of the two-step import.
--
-- Order: this migration runs before the deploy that reads the columns. It is additive and
-- two-way: the previous image ignores the new columns and the new table, so rollback is
-- "redeploy the previous image". Every existing workflow gets forking off.
--
-- Proof query after the upgrade (must return 0):
--   SELECT count(*) FROM workflow WHERE allow_fork;
--
-- Locks: ADD COLUMN with a constant default skips the table rewrite, but ADD COLUMN fork_count
-- with a CHECK takes ACCESS EXCLUSIVE and scans every row once to validate it, and the foreign
-- key takes SHARE ROW EXCLUSIVE on workflow. Each queues behind any open writer and then blocks
-- every writer behind it, so read the workflow row count before the deploy. The lock_timeout makes the migration fail fast (the deploy retries) instead
-- of stalling workflow writes.

SET LOCAL lock_timeout = '10s';

ALTER TABLE workflow ADD COLUMN allow_fork boolean NOT NULL DEFAULT false;
ALTER TABLE workflow ADD COLUMN fork_count integer NOT NULL DEFAULT 0
    CONSTRAINT ck_workflow_fork_count CHECK (fork_count >= 0);
ALTER TABLE workflow ADD COLUMN forked_from_id uuid
    CONSTRAINT fk_workflow_forked_from REFERENCES workflow (id);

-- workflow_id carries no foreign key on purpose: an imported row is the single-use ledger of its
-- token and keeps answering a replay for as long as it exists. An imported row holds no file and
-- no destination values (artifact and review are cleared), and only unimported rows are purged.
CREATE TABLE workflow_import_review (
    token          uuid         PRIMARY KEY,
    user_id        uuid         NOT NULL,
    artifact       jsonb,
    review         jsonb,
    outbound       boolean      NOT NULL,
    artifact_bytes integer      NOT NULL CHECK (artifact_bytes > 0),
    created_at     timestamptz  NOT NULL,
    expires_at     timestamptz  NOT NULL,
    workflow_id    uuid,
    imported_at    timestamptz,
    CONSTRAINT ck_workflow_import_review_imported CHECK ((workflow_id IS NULL) = (imported_at IS NULL)),
    CONSTRAINT ck_workflow_import_review_cleared CHECK (workflow_id IS NULL OR (artifact IS NULL AND review IS NULL)),
    CONSTRAINT ck_workflow_import_review_open CHECK (workflow_id IS NOT NULL OR (artifact IS NOT NULL AND review IS NOT NULL))
);

CREATE INDEX idx_workflow_import_review_open ON workflow_import_review (user_id, expires_at)
    WHERE workflow_id IS NULL;
