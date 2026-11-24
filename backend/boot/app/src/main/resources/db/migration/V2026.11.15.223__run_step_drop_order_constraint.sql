-- Drops the (run_id, step_order) key that V2026.11.15.222's partial index replaces: children share
-- their parent's step_order, so it can no longer hold for every row. Nothing refers to it by name.
-- Rollback: see the header of V2026.11.15.220.
--
-- Locks: DROP CONSTRAINT takes ACCESS EXCLUSIVE on run_step (no scan), which queues behind an open
-- writer and then blocks every writer behind it; lock_timeout makes the migration fail fast (the
-- deploy retries) instead of stalling run writes.

SET LOCAL lock_timeout = '10s';

ALTER TABLE run_step DROP CONSTRAINT uq_run_step_order;
