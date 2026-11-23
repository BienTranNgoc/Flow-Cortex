UPDATE workflow_step
SET config = (config - 'referenceImage') || '{"referenceImages":"all"}'::jsonb
WHERE id IN (
    '9aa35334-b672-4b6c-93e8-1a4c2cc14317',
    '9aa35334-b672-4b6c-93e8-1a4c2cc14315',
    '9aa35334-b672-4b6c-93e8-1a4c2cc14316'
)
  AND workflow_id = '9aa35334-b672-4b6c-93e8-1a4c2cc14301';
