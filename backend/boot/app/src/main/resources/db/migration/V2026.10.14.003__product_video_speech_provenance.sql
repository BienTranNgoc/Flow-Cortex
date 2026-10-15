-- The approval output now contains both the approved script and its immutable revision ID.
-- Passing the whole object to generate_speech preserves that provenance on the provider job.
UPDATE workflow_step
SET input_ref = NULL
WHERE workflow_id = '9aa35334-b672-4b6c-93e8-1a4c2cc14301'
  AND step_order = 3
  AND type = 'generate_speech';
