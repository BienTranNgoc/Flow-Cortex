-- Turn the approved voice-over into two dedicated visual prompts before video generation.
UPDATE workflow_step SET step_order = 7
WHERE id = '9aa35334-b672-4b6c-93e8-1a4c2cc14314'
  AND workflow_id = '9aa35334-b672-4b6c-93e8-1a4c2cc14301';

UPDATE workflow_step SET step_order = 6
WHERE id = '9aa35334-b672-4b6c-93e8-1a4c2cc14316'
  AND workflow_id = '9aa35334-b672-4b6c-93e8-1a4c2cc14301';

UPDATE workflow_step SET step_order = 5
WHERE id = '9aa35334-b672-4b6c-93e8-1a4c2cc14315'
  AND workflow_id = '9aa35334-b672-4b6c-93e8-1a4c2cc14301';

UPDATE workflow_step SET step_order = 4
WHERE id = '9aa35334-b672-4b6c-93e8-1a4c2cc14313'
  AND workflow_id = '9aa35334-b672-4b6c-93e8-1a4c2cc14301';

INSERT INTO workflow_step (id, workflow_id, step_order, name, type, config, input_ref)
VALUES (
    '9aa35334-b672-4b6c-93e8-1a4c2cc14317',
    '9aa35334-b672-4b6c-93e8-1a4c2cc14301',
    3,
    'generate_product_visual_storyboard',
    'generate_storyboard',
    '{
      "profile":"product_ad_storyboard_v1",
      "payloadFields":{"script":"script","sourceRevisionId":"sourceRevisionId"}
    }'::jsonb,
    '{step:2}'
) ON CONFLICT (id) DO NOTHING;

UPDATE workflow_step
SET config = '{
      "profile":"vertical_ad_clip_v1",
      "payloadFields":{"prompt":"scene1Prompt","sourceRevisionId":"sourceRevisionId"}
    }'::jsonb,
    input_ref = '{step:3}'
WHERE id = '9aa35334-b672-4b6c-93e8-1a4c2cc14315'
  AND workflow_id = '9aa35334-b672-4b6c-93e8-1a4c2cc14301';

UPDATE workflow_step
SET config = '{
      "profile":"vertical_ad_clip_v1",
      "payloadFields":{"prompt":"scene2Prompt","sourceRevisionId":"sourceRevisionId"}
    }'::jsonb,
    input_ref = '{step:3}'
WHERE id = '9aa35334-b672-4b6c-93e8-1a4c2cc14316'
  AND workflow_id = '9aa35334-b672-4b6c-93e8-1a4c2cc14301';

UPDATE workflow_step
SET config = '{
  "profile":"vertical_ad_v1",
  "outputInputs":[
    {"stepOrder":4,"outputName":"speech.wav","inputKey":"speech"},
    {"stepOrder":5,"outputName":"clip.mp4","inputKey":"generated_video_1"},
    {"stepOrder":6,"outputName":"clip.mp4","inputKey":"generated_video_2"}
  ]
}'::jsonb
WHERE id = '9aa35334-b672-4b6c-93e8-1a4c2cc14314'
  AND workflow_id = '9aa35334-b672-4b6c-93e8-1a4c2cc14301';
