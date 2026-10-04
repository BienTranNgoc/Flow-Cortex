-- Seed the URL-based Douyin workflow used by the MVP acceptance environment.
-- Fixed identifiers keep the seed idempotent across local and CI databases.

INSERT INTO app_user (
    id, owner_id, email, password_hash, name, role, status, created_at, updated_at
) VALUES (
    '6f7f8f2a-9d11-4d16-9d1a-1d5c4f9d3e01',
    '6f7f8f2a-9d11-4d16-9d1a-1d5c4f9d3e01',
    'test@flowcortex.io',
    '$2a$10$7HqfBnXMbLMAo/wPHX2MBe0uzFnJ.inLJGzg0llMkFj01WMPnjMiW',
    'FlowCortex Test Creator',
    'CREATOR',
    'ACTIVE',
    clock_timestamp(),
    clock_timestamp()
) ON CONFLICT (email) DO NOTHING;

INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at)
VALUES (
    'f2c8d6ef-9d9c-4c8a-91b3-7c6a7d2fb101',
    '6f7f8f2a-9d11-4d16-9d1a-1d5c4f9d3e01',
    'Douyin Video Repurpose',
    true,
    clock_timestamp(),
    clock_timestamp()
) ON CONFLICT (id) DO NOTHING;

INSERT INTO workflow_step (id, workflow_id, step_order, name, type, config, input_ref)
VALUES
    ('f2c8d6ef-9d9c-4c8a-91b3-7c6a7d2fb111',
     'f2c8d6ef-9d9c-4c8a-91b3-7c6a7d2fb101', 1, 'download_media', 'download_media', '{}'::jsonb, NULL),
    ('f2c8d6ef-9d9c-4c8a-91b3-7c6a7d2fb112',
     'f2c8d6ef-9d9c-4c8a-91b3-7c6a7d2fb101', 2, 'extract_audio', 'extract_audio', '{}'::jsonb, NULL),
    ('f2c8d6ef-9d9c-4c8a-91b3-7c6a7d2fb113',
     'f2c8d6ef-9d9c-4c8a-91b3-7c6a7d2fb101', 3, 'transcribe_and_translate',
     'transcribe_and_translate', '{}'::jsonb, NULL),
    ('f2c8d6ef-9d9c-4c8a-91b3-7c6a7d2fb114',
     'f2c8d6ef-9d9c-4c8a-91b3-7c6a7d2fb101', 4, 'human_review', 'APPROVAL_CHECKPOINT',
     '{}'::jsonb, NULL),
    ('f2c8d6ef-9d9c-4c8a-91b3-7c6a7d2fb115',
     'f2c8d6ef-9d9c-4c8a-91b3-7c6a7d2fb101', 5, 'tts_and_render', 'tts_and_render',
     '{}'::jsonb, NULL)
ON CONFLICT (id) DO NOTHING;
