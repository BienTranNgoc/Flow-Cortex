-- Seed the 10-step Speech-first MC Product Video workflow (mc-v1) with Checkpoint 2 keyframe review.

INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at)
VALUES (
    '9aa35334-b672-4b6c-93e8-1a4c2cc14302',
    '00000000-0000-0000-0000-000000000000',
    'Product Video MC',
    true,
    clock_timestamp(),
    clock_timestamp()
) ON CONFLICT (id) DO NOTHING;

INSERT INTO workflow_step (id, workflow_id, step_order, name, type, config, input_ref)
VALUES
    ('9aa35334-b672-4b6c-93e8-1a4c2cc14321',
     '9aa35334-b672-4b6c-93e8-1a4c2cc14302', 1, 'generate_mc_script', 'generate_script',
     '{"profile":"product_ad_v1"}'::jsonb, NULL),
    ('9aa35334-b672-4b6c-93e8-1a4c2cc14322',
     '9aa35334-b672-4b6c-93e8-1a4c2cc14302', 2, 'approve_mc_script', 'APPROVAL_CHECKPOINT',
     '{"kind":"script_review"}'::jsonb, NULL),
    ('9aa35334-b672-4b6c-93e8-1a4c2cc14323',
     '9aa35334-b672-4b6c-93e8-1a4c2cc14302', 3, 'generate_mc_speech', 'generate_speech',
     '{"profile":"vi_voice_v1"}'::jsonb, '{step:2}'),
    ('9aa35334-b672-4b6c-93e8-1a4c2cc14324',
     '9aa35334-b672-4b6c-93e8-1a4c2cc14302', 4, 'generate_mc_storyboard', 'generate_storyboard',
     '{"profile":"product_ad_storyboard_v1","payloadFields":{"script":"script","sourceRevisionId":"sourceRevisionId"},"referenceImages":"all"}'::jsonb,
     '{step:2}'),
    ('9aa35334-b672-4b6c-93e8-1a4c2cc14325',
     '9aa35334-b672-4b6c-93e8-1a4c2cc14302', 5, 'generate_mc_keyframe', 'generate_keyframe',
     '{"profile":"vertical_ad_keyframe_v1","payloadFields":{"prompt":"scene1Prompt","sourceRevisionId":"sourceRevisionId"},"referenceImages":"all"}'::jsonb,
     '{step:4}'),
    ('9aa35334-b672-4b6c-93e8-1a4c2cc14326',
     '9aa35334-b672-4b6c-93e8-1a4c2cc14302', 6, 'approve_mc_keyframe', 'APPROVAL_CHECKPOINT',
     '{"kind":"keyframe_review"}'::jsonb, '{step:5}'),
    ('9aa35334-b672-4b6c-93e8-1a4c2cc14327',
     '9aa35334-b672-4b6c-93e8-1a4c2cc14302', 7, 'generate_mc_scene1_video', 'generate_video',
     '{"profile":"vertical_ad_clip_v2","payloadFields":{"prompt":"scene1Prompt","sourceRevisionId":"sourceRevisionId"},"payloadDefaults":{"durationSeconds":5,"resolution":"480p","firstFrameKey":"keyframe"},"outputInputs":[{"stepOrder":5,"outputName":"keyframe.jpg","inputKey":"keyframe"}],"referenceImages":"all"}'::jsonb,
     '{step:4}'),
    ('9aa35334-b672-4b6c-93e8-1a4c2cc14328',
     '9aa35334-b672-4b6c-93e8-1a4c2cc14302', 8, 'generate_mc_scene2_video', 'generate_video',
     '{"profile":"vertical_ad_clip_v2","payloadFields":{"prompt":"scene2Prompt","sourceRevisionId":"sourceRevisionId"},"payloadDefaults":{"durationSeconds":5,"resolution":"480p"},"referenceImages":"all"}'::jsonb,
     '{step:4}'),
    ('9aa35334-b672-4b6c-93e8-1a4c2cc14329',
     '9aa35334-b672-4b6c-93e8-1a4c2cc14302', 9, 'lipsync_mc_scene1', 'lipsync_video',
     '{"profile":"vertical_ad_lipsync_v1","payloadFields":{"sourceRevisionId":"sourceRevisionId"},"payloadDefaults":{"durationSeconds":5},"outputInputs":[{"stepOrder":7,"outputName":"clip.mp4","inputKey":"video"},{"stepOrder":3,"outputName":"speech.wav","inputKey":"audio"}]}'::jsonb,
     '{step:2}'),
    ('9aa35334-b672-4b6c-93e8-1a4c2cc1432a',
     '9aa35334-b672-4b6c-93e8-1a4c2cc14302', 10, 'render_mc_video', 'render_video',
     '{"profile":"vertical_ad_v2","outputInputs":[{"stepOrder":3,"outputName":"speech.wav","inputKey":"speech"},{"stepOrder":9,"outputName":"clip.mp4","inputKey":"generated_video_1"},{"stepOrder":8,"outputName":"clip.mp4","inputKey":"generated_video_2"}]}'::jsonb,
     NULL)
ON CONFLICT (id) DO NOTHING;
