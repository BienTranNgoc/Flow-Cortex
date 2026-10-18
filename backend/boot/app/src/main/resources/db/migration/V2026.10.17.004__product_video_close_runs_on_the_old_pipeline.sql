-- Product Video runs started on an earlier pipeline (fewer than the seven steps the workflow now
-- has) cannot finish: their render step still expects one speech track, while the renderer now
-- composes two generated clips with it. They are closed here, before any of them spends a
-- speech or video call, with a message telling the member to start again. Finished runs are
-- left as they are.
WITH stale AS (
    SELECT run.id
    FROM workflow_run run
    WHERE run.workflow_id = '9aa35334-b672-4b6c-93e8-1a4c2cc14301'
      AND run.status IN ('running', 'waiting_approval')
      AND NOT EXISTS (
          SELECT 1 FROM run_step step WHERE step.run_id = run.id AND step.step_order = 7
      )
),
closed_steps AS (
    UPDATE run_step
    SET status        = 'error',
        error_code    = 'workflow_changed',
        error_message = 'Product Video moved to a new pipeline while this run was in progress. Start a new run.',
        finished_at   = now(),
        lease_until   = NULL
    WHERE run_id IN (SELECT id FROM stale)
      AND status = 'running'
)
UPDATE workflow_run
SET status        = 'error',
    error_message = 'Product Video moved to a new pipeline while this run was in progress. Start a new run.',
    finished_at   = now()
WHERE id IN (SELECT id FROM stale);
