-- When the reaper's stalled-run sweep last took this run; NULL = never. The sweep takes runs
-- by this column (NULLs first), so a run that never moves goes to the back instead of holding
-- a batch slot on every pass. Nullable with no default: a metadata-only change, no backfill.
ALTER TABLE workflow_run ADD COLUMN last_swept_at timestamptz;
