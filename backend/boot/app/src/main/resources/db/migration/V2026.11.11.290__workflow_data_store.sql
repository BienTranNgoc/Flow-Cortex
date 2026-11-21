-- Workflow data store: named JSON values a workflow keeps for its steps to read (a template a
-- load_template step fetches by key). One row per (workflow_id, key).
--
-- The key pattern refuses '.' and '..' because a key travels as a URL path segment and a client
-- collapses dot-segments; dots inside a longer key stay allowed. size_bytes is the compact UTF-8
-- length the application measured; the check bounds what is recorded. No cascade: workflows are
-- soft-deleted, never removed.
--
-- Rollback (revert the code too). Run in one transaction, and include the history delete: without
-- it Flyway still counts this version as applied and a later roll-forward skips the migration.
--   BEGIN;
--   DROP TABLE workflow_data_store;
--   DELETE FROM flyway_schema_history WHERE version = '2026.11.11.290';
--   COMMIT;

CREATE TABLE workflow_data_store (
    id          uuid         PRIMARY KEY,
    workflow_id uuid         NOT NULL REFERENCES workflow (id),
    key         text         NOT NULL CHECK (key ~ '^(?!\.{1,2}$)[A-Za-z0-9._-]{1,100}$'),
    data_type   text         NOT NULL DEFAULT 'json' CHECK (data_type ~ '^[a-z0-9_-]{1,32}$'),
    value       jsonb        NOT NULL,
    size_bytes  integer      NOT NULL CHECK (size_bytes BETWEEN 0 AND 524288),
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    CONSTRAINT uq_workflow_data_store_key UNIQUE (workflow_id, key)
);

CREATE INDEX idx_workflow_data_store_updated ON workflow_data_store (workflow_id, updated_at DESC);
