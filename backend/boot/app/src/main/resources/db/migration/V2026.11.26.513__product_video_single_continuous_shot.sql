-- Upgrade Product Video (showroom-v1) from two separate 5s clips to one continuous 10s shot
-- (6 steps total: script -> approval -> storyboard -> speech -> 10s video -> render).
-- Historical runs keep their own run_step snapshots; mc-v1 is untouched.

DELETE FROM workflow_step
WHERE id = '9aa35334-b672-4b6c-93e8-1a4c2cc14316'
  AND workflow_id = '9aa35334-b672-4b6c-93e8-1a4c2cc14301';

UPDATE workflow_step
SET config = '{
      "profile": "vertical_ad_clip_v1",
      "payloadFields": {"prompt": "scene1Prompt", "sourceRevisionId": "sourceRevisionId"},
      "payloadDefaults": {"durationSeconds": 10},
      "referenceImages": "all"
    }'::jsonb,
    input_ref = '{step:3}'
WHERE id = '9aa35334-b672-4b6c-93e8-1a4c2cc14315'
  AND workflow_id = '9aa35334-b672-4b6c-93e8-1a4c2cc14301';

UPDATE workflow_step
SET step_order = 6,
    config = '{
      "profile": "vertical_ad_v1",
      "outputInputs": [
        {"stepOrder": 4, "outputName": "speech.wav", "inputKey": "speech"},
        {"stepOrder": 5, "outputName": "clip.mp4", "inputKey": "generated_video_1"}
      ]
    }'::jsonb
WHERE id = '9aa35334-b672-4b6c-93e8-1a4c2cc14314'
  AND workflow_id = '9aa35334-b672-4b6c-93e8-1a4c2cc14301';
