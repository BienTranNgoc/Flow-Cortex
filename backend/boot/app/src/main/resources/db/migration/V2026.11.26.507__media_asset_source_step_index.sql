-- Registering a run's outputs looks media assets up by the step attempt that produced them. Built
-- CONCURRENTLY so media_asset writes are not blocked while the table is scanned. Flyway runs a
-- script made only of CONCURRENTLY statements outside a transaction, so keep this file to those
-- statements. A build that fails leaves an INVALID index; the DROP ... IF EXISTS line clears it so
-- the migration can simply be retried.

DROP INDEX CONCURRENTLY IF EXISTS idx_media_asset_source_step;
CREATE INDEX CONCURRENTLY idx_media_asset_source_step ON media_asset (source_run_step_id, source_attempt)
    WHERE source_run_step_id IS NOT NULL;
