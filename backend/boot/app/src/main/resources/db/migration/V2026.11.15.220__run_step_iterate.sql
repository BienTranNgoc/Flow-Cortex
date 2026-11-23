-- Step tree: a run step may be an item of another step of the same run (a child of an iterate step).
--
-- Order: all five scripts of this change (.220 to .224) run before the deploy that registers the
-- iterate step type, since startup refuses a registered type without a pricing row. The scripts are
-- additive: the previous image ignores the new columns and its inserts leave them null, which
-- satisfies the check and the top-level partial index exactly as the old constraint did, so existing
-- rows need no backfill. Redeploying the previous image is a safe rollback only while no iterate run
-- exists (no row with parent_step_id set and no iterate step): once a child row exists the previous
-- image reads it as a top-level step (its step list is not filtered by parent and its open-step count
-- includes children), so it claims a child as the run's next step or never completes the run. See
-- Rollback below.
--
-- This script is metadata only: the foreign key and the check are added NOT VALID, so no row is
-- scanned while the table lock is held. V2026.11.15.221 validates them (a lock that does not block
-- writers), V2026.11.15.222 builds the partial unique indexes without blocking writers, and
-- V2026.11.15.223 drops the old (run_id, step_order) key once the partial one stands in for it.
--
-- Rollback. Check first: SELECT count(*) FROM run_step WHERE parent_step_id IS NOT NULL OR type = 'iterate';
-- When it is 0, redeploy the previous image, then run the structural statements below. When it is not
-- 0, the previous image must never read those rows: stop all traffic and every worker of the current
-- image, run this block in one transaction, and only then start the previous image and run the
-- structural statements:
--   BEGIN;
--   UPDATE workflow_run SET status = 'error', finished_at = now(), error_message = 'Stopped by a rollback'
--     WHERE status IN ('running', 'waiting_approval', 'cancelling')
--       AND id IN (SELECT run_id FROM run_step WHERE type = 'iterate' AND status IN ('pending', 'running'));
--   UPDATE run_step SET status = 'error', error_code = 'iterate_rolled_back', error_message = 'Stopped by a rollback',
--     finished_at = now() WHERE type = 'iterate' AND status IN ('pending', 'running');
--   DELETE FROM run_step WHERE parent_step_id IS NOT NULL;
--   COMMIT;
-- Finished iterate steps keep their gathered output; debits already written for deleted items stay in
-- credit_ledger and are not refunded by this block.
-- Structural statements, once the previous image runs (and delete the flyway_schema_history rows of
-- .220 to .224 so a later roll-forward re-applies them):
--   ALTER TABLE run_step ADD CONSTRAINT uq_run_step_order UNIQUE (run_id, step_order);
--   DROP INDEX uq_run_step_child_item;
--   DROP INDEX uq_run_step_top_order;
--   ALTER TABLE run_step DROP CONSTRAINT run_step_parent_all_or_none,
--     DROP CONSTRAINT run_step_parent_fk,
--     DROP COLUMN item_index, DROP COLUMN parent_attempt, DROP COLUMN parent_step_id;
--   DELETE FROM capability_pricing WHERE capability = 'iterate';
--
-- Locks: the column adds and constraint adds take ACCESS EXCLUSIVE on run_step, which queues behind an
-- open writer and then blocks every writer behind it; lock_timeout makes the migration fail fast
-- (the deploy retries) instead of stalling run writes.

SET LOCAL lock_timeout = '10s';

ALTER TABLE run_step
    ADD COLUMN parent_step_id uuid,
    ADD COLUMN parent_attempt smallint,
    ADD COLUMN item_index     smallint,
    ADD CONSTRAINT run_step_parent_fk FOREIGN KEY (parent_step_id) REFERENCES run_step (id) NOT VALID,
    ADD CONSTRAINT run_step_parent_all_or_none CHECK (
        (parent_step_id IS NULL AND parent_attempt IS NULL AND item_index IS NULL)
        OR (parent_step_id IS NOT NULL AND parent_attempt IS NOT NULL AND item_index IS NOT NULL
            AND parent_attempt >= 1 AND item_index BETWEEN 0 AND 49)) NOT VALID;
