-- Both generated clips start from the member's first product photo (image_1, bound under the
-- run's inputs when it starts), so the ad shows the real product instead of an imagined one.
-- Runs already started keep the config they were started with: their clip steps hold no stored
-- inputs to read the photo from, so they go on without it.
UPDATE workflow_step
SET config = config || '{"referenceImage":"image_1"}'::jsonb
WHERE id IN ('9aa35334-b672-4b6c-93e8-1a4c2cc14315', '9aa35334-b672-4b6c-93e8-1a4c2cc14316')
  AND workflow_id = '9aa35334-b672-4b6c-93e8-1a4c2cc14301';
