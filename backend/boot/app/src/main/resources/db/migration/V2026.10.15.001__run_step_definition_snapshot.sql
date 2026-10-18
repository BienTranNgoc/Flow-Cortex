-- A run keeps the step definitions it started with: dispatch reads config and input_ref from
-- its own run_step, so a later edit or renumbering of workflow_step never changes a run already
-- in flight. Additive only — both columns are nullable, matching workflow_step.
--
-- The backfill copies each existing step's definition by (workflow, step_order). It must run
-- before any migration renumbers workflow_step, which is why this version sorts ahead of them.
-- IF NOT EXISTS keeps the file re-runnable; the UPDATE only fills rows still unset.
ALTER TABLE run_step
    ADD COLUMN IF NOT EXISTS config    jsonb,
    ADD COLUMN IF NOT EXISTS input_ref varchar(255);

UPDATE run_step
SET config    = definition.config,
    input_ref = definition.input_ref
FROM workflow_run run
JOIN workflow_step definition
  ON definition.workflow_id = run.workflow_id
WHERE run.id = run_step.run_id
  AND definition.step_order = run_step.step_order
  AND run_step.config IS NULL
  AND run_step.input_ref IS NULL
  AND (definition.config IS NOT NULL OR definition.input_ref IS NOT NULL);
