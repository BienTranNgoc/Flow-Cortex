-- Run tree: a run may be the child of one step attempt of another run (a workflow.call step).
--
-- Order: this migration runs before the deploy that reads the columns. It is additive and two-way:
-- the previous image ignores the new columns and its inserts take the defaults (no parent,
-- call_depth 0), which satisfy both checks, so rollback is "redeploy the previous image". Existing
-- rows need no backfill.
--
-- This script is metadata only: the foreign keys and checks are added NOT VALID, so no row is
-- scanned while the table lock is held. V2026.11.11.192 validates them (a lock that does not block
-- writers) and V2026.11.11.193 builds the partial indexes without blocking writers.
--
-- Rollback, once no workflow.call step exists (and delete the flyway_schema_history rows of .190,
-- .192 and .193 so a later roll-forward re-applies them):
--   DROP INDEX workflow_run_parent_run;
--   DROP INDEX workflow_run_parent_step_attempt;
--   ALTER TABLE workflow_run DROP CONSTRAINT workflow_run_call_depth,
--     DROP CONSTRAINT workflow_run_parent_all_or_none,
--     DROP CONSTRAINT workflow_run_parent_step_fk,
--     DROP CONSTRAINT workflow_run_parent_run_fk,
--     DROP COLUMN call_depth, DROP COLUMN parent_step_attempt,
--     DROP COLUMN parent_step_id, DROP COLUMN parent_run_id;
--
-- Locks: the column adds and constraint adds take ACCESS EXCLUSIVE / SHARE ROW EXCLUSIVE on
-- workflow_run and on the referenced tables, which queue behind an open writer and then block every
-- writer behind it; lock_timeout makes the migration fail fast (the deploy retries) instead of
-- stalling run writes.

SET LOCAL lock_timeout = '10s';

ALTER TABLE workflow_run
    ADD COLUMN parent_run_id       uuid,
    ADD COLUMN parent_step_id      uuid,
    ADD COLUMN parent_step_attempt smallint,
    ADD COLUMN call_depth          smallint NOT NULL DEFAULT 0,
    ADD CONSTRAINT workflow_run_parent_run_fk FOREIGN KEY (parent_run_id) REFERENCES workflow_run (id) NOT VALID,
    ADD CONSTRAINT workflow_run_parent_step_fk FOREIGN KEY (parent_step_id) REFERENCES run_step (id) NOT VALID,
    ADD CONSTRAINT workflow_run_parent_all_or_none CHECK (
        (parent_run_id IS NULL AND parent_step_id IS NULL AND parent_step_attempt IS NULL)
        OR (parent_run_id IS NOT NULL AND parent_step_id IS NOT NULL AND parent_step_attempt IS NOT NULL)) NOT VALID,
    ADD CONSTRAINT workflow_run_call_depth CHECK (
        call_depth BETWEEN 0 AND 2 AND (call_depth = 0) = (parent_run_id IS NULL)) NOT VALID;
