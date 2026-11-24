-- review_task: a time limit on every review, the stored questions and answers of a QA review,
-- the key of the decision that ended it, an `expired` status, and the free price of `qa.review`.
--
-- Runs before the deploy that reads the new columns (Flyway on boot, one transaction). expires_at
-- is NOT NULL and defaults to 7 days from the insert: the code that inserts a review task sets it,
-- and the default keeps an insert from code that predates the column (an instance still serving
-- during the deploy) valid. The column is added with a constant default (now() is stable, so
-- Postgres stores it once as the value of every existing row and rewrites nothing): no row is
-- touched whatever the table holds, so there is no backfill to batch. A pending row gets 7 days
-- from this migration, so a run already waiting longer is not stopped by the first sweep; a decided
-- row gets the same value, which nothing reads (a decided task is never judged against it). The
-- default then switches to the per-insert clock for rows inserted from now on.
--
-- Rollback (revert the code too; remove every qa.review step from workflows first, startup pricing
-- would otherwise fail). Run in one transaction, and include the history delete: without it Flyway
-- still counts this version as applied and a later roll-forward skips the migration.
--   BEGIN;
--   DELETE FROM capability_pricing WHERE capability = 'qa.review';
--   DROP INDEX idx_review_task_pending_expiry;
--   UPDATE review_task SET status = 'rejected' WHERE status = 'expired';
--   ALTER TABLE review_task DROP CONSTRAINT chk_review_task_questions;
--   ALTER TABLE review_task DROP CONSTRAINT chk_review_task_status;
--   ALTER TABLE review_task ADD CONSTRAINT chk_review_task_status
--       CHECK (status IN ('pending', 'approved', 'rejected'));
--   ALTER TABLE review_task
--       DROP COLUMN decision_key, DROP COLUMN answers, DROP COLUMN questions_state,
--       DROP COLUMN questions, DROP COLUMN expires_at;
--   DELETE FROM flyway_schema_history WHERE version = '2026.11.15.110';
--   COMMIT;
-- Turning `expired` into `rejected` and dropping the columns loses the stored questions, answers and
-- decision keys: recovery of those means a restore.

ALTER TABLE review_task
    ADD COLUMN expires_at       timestamptz NOT NULL DEFAULT now() + interval '7 days',
    ADD COLUMN questions        jsonb       NULL,
    ADD COLUMN questions_state  text        NULL CHECK (questions_state IN ('generating', 'ready')),
    ADD COLUMN answers          jsonb       NULL,
    ADD COLUMN decision_key     text        NULL CHECK (char_length(decision_key) BETWEEN 1 AND 80);

ALTER TABLE review_task ALTER COLUMN expires_at SET DEFAULT clock_timestamp() + interval '7 days';

ALTER TABLE review_task DROP CONSTRAINT chk_review_task_status;
ALTER TABLE review_task ADD CONSTRAINT chk_review_task_status
    CHECK (status IN ('pending', 'approved', 'rejected', 'expired'));

ALTER TABLE review_task ADD CONSTRAINT chk_review_task_questions
    CHECK ((review_type = 'QA_REVIEW') = (questions_state IS NOT NULL));

CREATE INDEX idx_review_task_pending_expiry ON review_task (expires_at) WHERE status = 'pending';

INSERT INTO capability_pricing (capability, estimator_kind) VALUES ('qa.review', 'free');
