-- Additive: the owner's verdict on a run's result, the short insight written for a table result, and the
-- per-workflow switch that asks for that insight.
--
-- feedback_* hold one verdict per run: 'up' (no issue) or 'down' (one of six issue codes), an optional detail of at
-- most 1,000 characters, and when it was given. insight is {"headline": "...", "highlights": ["..."]}, written once.
-- workflow.generate_insight defaults to false, so no existing workflow asks for an insight.
--
-- The previous image ignores the new columns (its inserts leave them null / false). This script is metadata only: both
-- checks are added NOT VALID so no row is scanned while the table lock is held, and the constant default of
-- generate_insight does not rewrite the table; V2026.11.26.661 validates the checks while writers keep running.
-- Rollback: ALTER TABLE workflow_run DROP CONSTRAINT workflow_run_feedback_shape, DROP CONSTRAINT
-- workflow_run_feedback_detail_length, DROP COLUMN feedback_verdict, DROP COLUMN feedback_issue, DROP COLUMN
-- feedback_detail, DROP COLUMN feedback_at, DROP COLUMN insight; ALTER TABLE workflow DROP COLUMN generate_insight.

SET LOCAL lock_timeout = '10s';

ALTER TABLE workflow_run
    ADD COLUMN feedback_verdict text,
    ADD COLUMN feedback_issue   text,
    ADD COLUMN feedback_detail  text,
    ADD COLUMN feedback_at      timestamptz,
    ADD COLUMN insight          jsonb,
    ADD CONSTRAINT workflow_run_feedback_shape CHECK (
        (feedback_verdict IS NULL AND feedback_issue IS NULL AND feedback_detail IS NULL AND feedback_at IS NULL)
        OR (feedback_verdict = 'up' AND feedback_issue IS NULL AND feedback_at IS NOT NULL)
        OR (feedback_verdict = 'down' AND feedback_at IS NOT NULL AND feedback_issue IS NOT NULL
            AND feedback_issue IN (
            'wrong_request', 'low_quality', 'off_brand', 'technical_error', 'inappropriate', 'other'))) NOT VALID,
    ADD CONSTRAINT workflow_run_feedback_detail_length CHECK (char_length(feedback_detail) <= 1000) NOT VALID;

ALTER TABLE workflow ADD COLUMN generate_insight boolean NOT NULL DEFAULT false;
