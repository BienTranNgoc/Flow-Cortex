-- Additive: creates first_result_report (one row per account, the once-only first-result report)
-- and backfills it for accounts that already have a completed run. The backfill inserts only into
-- the new table, touches no existing row, and is idempotent. No FK: modules reach each other
-- only through public ports.
CREATE TABLE first_result_report (
    user_id     uuid        PRIMARY KEY,
    run_id      uuid        NOT NULL,
    workflow_id uuid        NOT NULL,
    source      varchar(10) NOT NULL,
    result_at   timestamptz NOT NULL,
    reported_at timestamptz NOT NULL,
    CONSTRAINT first_result_report_source_chk CHECK (source IN ('claim', 'backfill'))
);

INSERT INTO first_result_report (user_id, run_id, workflow_id, source, result_at, reported_at)
SELECT DISTINCT ON (r.owner_id) r.owner_id, r.id, r.workflow_id, 'backfill', COALESCE(r.finished_at, r.started_at), now()
  FROM workflow_run r
 WHERE r.status = 'completed'
 ORDER BY r.owner_id, r.started_at, r.id
ON CONFLICT (user_id) DO NOTHING;
