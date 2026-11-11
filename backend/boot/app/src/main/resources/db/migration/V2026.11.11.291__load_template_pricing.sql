-- Price mapping for the load_template step type: free, like the other steps that call no provider.
--
-- Rollback (revert the code too: the step registry stops startup when a registered type has no
-- price). Run in one transaction, and include the history delete.
--   BEGIN;
--   DELETE FROM capability_pricing WHERE capability = 'load_template';
--   DELETE FROM flyway_schema_history WHERE version = '2026.11.11.291';
--   COMMIT;

INSERT INTO capability_pricing (capability, estimator_kind) VALUES ('load_template', 'free');
