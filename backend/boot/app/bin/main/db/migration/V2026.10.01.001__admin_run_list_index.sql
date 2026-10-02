-- The admin Runs page lists every user's runs newest first (ORDER BY started_at DESC, id DESC,
-- LIMIT n); this index serves that order without sorting the whole table.
CREATE INDEX idx_workflow_run_started_at ON workflow_run (started_at DESC, id DESC);
