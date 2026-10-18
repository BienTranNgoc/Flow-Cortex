-- Product Video approvals recorded before generate_speech required provenance stored only
-- {"script": ...}. The speech step now reads the whole approval output and its job must carry
-- sourceRevisionId, so a run approved before this release would fail at speech without it.
-- The output column is text: rows that are not a JSON object are skipped, never cast blindly.
WITH approval AS MATERIALIZED (
    SELECT step.id,
           task.approved_revision_id,
           CASE WHEN pg_input_is_valid(step.output, 'jsonb') THEN step.output::jsonb END AS output
    FROM run_step step
    JOIN workflow_run run ON run.id = step.run_id
    JOIN review_task task ON task.run_step_id = step.id
    WHERE run.workflow_id = '9aa35334-b672-4b6c-93e8-1a4c2cc14301'
      AND step.step_order = 2
      AND step.status = 'completed'
      AND task.approved_revision_id IS NOT NULL
)
UPDATE run_step
SET output = jsonb_set(approval.output, '{sourceRevisionId}', to_jsonb(approval.approved_revision_id))::text
FROM approval
WHERE run_step.id = approval.id
  AND jsonb_typeof(approval.output) = 'object'
  AND jsonb_typeof(approval.output -> 'script') = 'string'
  AND approval.output -> 'sourceRevisionId' IS NULL;
