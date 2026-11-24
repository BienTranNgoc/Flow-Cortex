-- Price coverage for the iterate step type: startup refuses a registered step type that has no
-- capability_pricing row. The step itself costs nothing and takes no quantity parameter; the child
-- steps it fans out are charged by their own rows, at their own claims, to the same owner (as for
-- workflow.call, V2026.11.11.191). The start estimate counts maxItems children, not this row.
--
-- Order: run before the deploy that registers the step type (the same release).
--
-- Rollback (revert the code too; include the history delete so a later roll-forward re-applies it):
--   BEGIN;
--   DELETE FROM capability_pricing WHERE capability = 'iterate';
--   DELETE FROM flyway_schema_history WHERE version = '2026.11.15.224';
--   COMMIT;

INSERT INTO capability_pricing (capability, estimator_kind) VALUES ('iterate', 'free');
