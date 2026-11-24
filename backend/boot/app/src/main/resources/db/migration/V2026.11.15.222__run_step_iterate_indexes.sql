-- Partial unique indexes over the step tree, built CONCURRENTLY so run_step writes are not blocked
-- while a table of any size is scanned. Flyway runs a script made only of CONCURRENTLY statements
-- outside a transaction, so keep this file to those statements. A build that fails leaves an
-- INVALID index; the DROP ... IF EXISTS lines clear it so the migration can simply be retried.
--
-- uq_run_step_top_order replaces uq_run_step_order for top-level steps (children share their
-- parent's step_order); it is built before that constraint is dropped, so top-level uniqueness
-- never lapses.

DROP INDEX CONCURRENTLY IF EXISTS uq_run_step_top_order;
CREATE UNIQUE INDEX CONCURRENTLY uq_run_step_top_order ON run_step (run_id, step_order)
    WHERE parent_step_id IS NULL;

DROP INDEX CONCURRENTLY IF EXISTS uq_run_step_child_item;
CREATE UNIQUE INDEX CONCURRENTLY uq_run_step_child_item ON run_step (parent_step_id, parent_attempt, item_index)
    WHERE parent_step_id IS NOT NULL;
