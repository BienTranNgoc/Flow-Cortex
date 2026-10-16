-- Add AI clip generation between approved speech and final composition.
UPDATE workflow_step
SET step_order = 5
WHERE id = '9aa35334-b672-4b6c-93e8-1a4c2cc14314'
  AND workflow_id = '9aa35334-b672-4b6c-93e8-1a4c2cc14301';

INSERT INTO workflow_step (id, workflow_id, step_order, name, type, config, input_ref)
VALUES (
    '9aa35334-b672-4b6c-93e8-1a4c2cc14315',
    '9aa35334-b672-4b6c-93e8-1a4c2cc14301',
    4,
    'generate_product_video_clip',
    'generate_video',
    '{"profile":"vertical_ad_clip_v1","payloadFields":{"prompt":"script","sourceRevisionId":"sourceRevisionId"}}'::jsonb,
    '{step:2}'
) ON CONFLICT (id) DO NOTHING;

UPDATE workflow_step
SET config = '{
  "profile":"vertical_ad_v1",
  "outputInputs":[
    {"stepOrder":3,"outputName":"speech.wav","inputKey":"speech"},
    {"stepOrder":4,"outputName":"clip.mp4","inputKey":"generated_video"}
  ]
}'::jsonb
WHERE id = '9aa35334-b672-4b6c-93e8-1a4c2cc14314'
  AND workflow_id = '9aa35334-b672-4b6c-93e8-1a4c2cc14301';
