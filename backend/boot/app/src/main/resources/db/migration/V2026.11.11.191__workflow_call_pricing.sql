-- Price coverage for the workflow.call step type: startup refuses a registered step type that has no
-- capability_pricing row. The step itself costs nothing; the steps of the workflow it starts are
-- charged by their own rows, at their own claims, to the same owner. Xood prices its workflow.call
-- node the same way (capability-record-meta.ts:3426, costModel estimatorKind 'free').
--
-- Order: run before the deploy that registers the step type (the same release).
--
-- Rollback (revert the code too; include the history delete so a later roll-forward re-applies it):
--   BEGIN;
--   DELETE FROM capability_pricing WHERE capability = 'workflow.call';
--   DELETE FROM flyway_schema_history WHERE version = '2026.11.11.191';
--   COMMIT;

INSERT INTO capability_pricing (capability, estimator_kind) VALUES ('workflow.call', 'free');
