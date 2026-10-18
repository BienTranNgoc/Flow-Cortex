-- Generate two distinct five-second scenes and compose them into one ten-second advertisement.
UPDATE workflow_step
SET config = '{
  "profile":"vertical_ad_clip_v1",
  "payloadFields":{"prompt":"script","sourceRevisionId":"sourceRevisionId"},
  "promptInstruction":"Scene 1 of 2, opening hook. Create a premium product hero shot with an immediate visual hook, dynamic camera push-in, realistic motion, clean studio lighting, no text, no logo mutation, vertical 9:16."
}'::jsonb
WHERE id = '9aa35334-b672-4b6c-93e8-1a4c2cc14315'
  AND workflow_id = '9aa35334-b672-4b6c-93e8-1a4c2cc14301';

UPDATE workflow_step
SET step_order = 6
WHERE id = '9aa35334-b672-4b6c-93e8-1a4c2cc14314'
  AND workflow_id = '9aa35334-b672-4b6c-93e8-1a4c2cc14301';

INSERT INTO workflow_step (id, workflow_id, step_order, name, type, config, input_ref)
VALUES (
    '9aa35334-b672-4b6c-93e8-1a4c2cc14316',
    '9aa35334-b672-4b6c-93e8-1a4c2cc14301',
    5,
    'generate_product_video_clip_2',
    'generate_video',
    '{
      "profile":"vertical_ad_clip_v1",
      "payloadFields":{"prompt":"script","sourceRevisionId":"sourceRevisionId"},
      "promptInstruction":"Scene 2 of 2, benefit and closing CTA visual. Show the product being used naturally, then finish on a clean product beauty shot with empty lower-third space for CTA overlay, realistic motion, consistent product appearance, no rendered text, vertical 9:16."
    }'::jsonb,
    '{step:2}'
) ON CONFLICT (id) DO NOTHING;

UPDATE workflow_step
SET config = '{
  "profile":"vertical_ad_v1",
  "outputInputs":[
    {"stepOrder":3,"outputName":"speech.wav","inputKey":"speech"},
    {"stepOrder":4,"outputName":"clip.mp4","inputKey":"generated_video_1"},
    {"stepOrder":5,"outputName":"clip.mp4","inputKey":"generated_video_2"}
  ]
}'::jsonb
WHERE id = '9aa35334-b672-4b6c-93e8-1a4c2cc14314'
  AND workflow_id = '9aa35334-b672-4b6c-93e8-1a4c2cc14301';
