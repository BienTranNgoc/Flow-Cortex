-- Indexes of the discovery queries, built CONCURRENTLY so workflow and workflow_bookmark writes are not
-- blocked while a table of any size is scanned. Flyway runs a script made only of CONCURRENTLY statements
-- outside a transaction, so keep this file to those statements. A build that fails leaves an INVALID
-- index; the DROP ... IF EXISTS lines clear it so the migration can simply be retried.
--
-- The GIN index is over workflow_search_fold(name), created by the previous migration.

DROP INDEX CONCURRENTLY IF EXISTS idx_workflow_name_fold_trgm;
CREATE INDEX CONCURRENTLY idx_workflow_name_fold_trgm ON workflow USING gin (workflow_search_fold(name) gin_trgm_ops);

DROP INDEX CONCURRENTLY IF EXISTS idx_workflow_catalogue;
CREATE INDEX CONCURRENTLY idx_workflow_catalogue ON workflow (created_at DESC, id DESC)
    WHERE deleted_at IS NULL AND status = 'published' AND visibility = 'public';

DROP INDEX CONCURRENTLY IF EXISTS idx_workflow_bookmark_created;
CREATE INDEX CONCURRENTLY idx_workflow_bookmark_created ON workflow_bookmark (created_at DESC);
