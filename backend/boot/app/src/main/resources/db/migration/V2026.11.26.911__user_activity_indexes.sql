-- A member's activity timeline pages each source newest first by (time, id) under owner scope. Built
-- CONCURRENTLY so workflow, workflow_run and support_request writes are not blocked while a table of any size is
-- scanned. Flyway runs a script made only of CONCURRENTLY statements outside a transaction, so keep this file to those
-- statements. A build that fails leaves an INVALID index; the DROP ... IF EXISTS lines clear it so the migration can
-- simply be retried.

DROP INDEX CONCURRENTLY IF EXISTS idx_workflow_owner_created_page;
CREATE INDEX CONCURRENTLY idx_workflow_owner_created_page ON workflow (owner_id, created_at DESC, id DESC);

-- Not partial: the timeline includes soft-deleted runs, which idx_workflow_run_owner_history (WHERE deleted_at IS NULL)
-- cannot serve.
DROP INDEX CONCURRENTLY IF EXISTS idx_workflow_run_owner_started_page;
CREATE INDEX CONCURRENTLY idx_workflow_run_owner_started_page ON workflow_run (owner_id, started_at DESC, id DESC);

DROP INDEX CONCURRENTLY IF EXISTS idx_support_request_requester_created_page;
CREATE INDEX CONCURRENTLY idx_support_request_requester_created_page
    ON support_request (requester_id, created_at DESC, id DESC);
