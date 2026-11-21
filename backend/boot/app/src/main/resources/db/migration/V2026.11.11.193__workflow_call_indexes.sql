-- Partial indexes over the run tree, built CONCURRENTLY so workflow_run writes are not blocked
-- while a table of any size is scanned. Flyway runs a script made only of CONCURRENTLY statements
-- outside a transaction, so keep this file to those statements. A build that fails leaves an
-- INVALID index; the DROP ... IF EXISTS lines clear it so the migration can simply be retried.

DROP INDEX CONCURRENTLY IF EXISTS workflow_run_parent_step_attempt;
CREATE UNIQUE INDEX CONCURRENTLY workflow_run_parent_step_attempt ON workflow_run (parent_step_id, parent_step_attempt)
    WHERE parent_step_id IS NOT NULL;

DROP INDEX CONCURRENTLY IF EXISTS workflow_run_parent_run;
CREATE INDEX CONCURRENTLY workflow_run_parent_run ON workflow_run (parent_run_id)
    WHERE parent_run_id IS NOT NULL;
