-- What the member chose when starting the run, written once at start: {"voice":"female"|"male"}.
-- Inline steps read it (the voice of the dub). NULL = a run started before the column existed.
-- Nullable with no default: a metadata-only change, no backfill.
ALTER TABLE workflow_run ADD COLUMN options jsonb;
