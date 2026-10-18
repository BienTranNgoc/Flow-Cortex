-- The storyboard inserted at step 3 must not become the implicit input of speech generation.
-- Speech always consumes the approved script/revision emitted by the approval checkpoint.
UPDATE workflow_step
SET input_ref = '{step:2}'
WHERE id = '9aa35334-b672-4b6c-93e8-1a4c2cc14313'
  AND workflow_id = '9aa35334-b672-4b6c-93e8-1a4c2cc14301'
  AND type = 'generate_speech';
