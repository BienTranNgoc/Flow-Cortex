-- connector.call: one HTTP request through a connector the run's owner saved. The member pays the
-- third party and the platform does nothing billable, so the step is free (Xood prices its
-- http-call capability the same way: costModel estimatorKind 'free', capability-record-meta.ts:403).
-- A free mapping names no rate card, unit or provider and lists no quantity parameter.
--
-- Rollback (revert the code too; with the row gone and the executor still registered, startup
-- fails with "no mapping" for connector.call). Run in one transaction, including the history delete,
-- so a later roll-forward does not skip this version:
--   BEGIN;
--   DELETE FROM capability_pricing WHERE capability = 'connector.call';
--   DELETE FROM flyway_schema_history WHERE version = '2026.11.11.060';
--   COMMIT;

INSERT INTO capability_pricing (capability, estimator_kind) VALUES ('connector.call', 'free');
