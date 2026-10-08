-- Seed the URL-based Douyin workflow used by the MVP acceptance environment.
-- Fixed identifiers keep the seed idempotent across local and CI databases.

INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at)
VALUES (
    'f2c8d6ef-9d9c-4c8a-91b3-7c6a7d2fb101',
    '00000000-0000-0000-0000-000000000000',
    'Douyin Video Repurpose',
    true,
    clock_timestamp(),
    clock_timestamp()
) ON CONFLICT (id) DO NOTHING;

INSERT INTO workflow_step (id, workflow_id, step_order, name, type, config, input_ref)
VALUES
    ('f2c8d6ef-9d9c-4c8a-91b3-7c6a7d2fb111',
     'f2c8d6ef-9d9c-4c8a-91b3-7c6a7d2fb101', 1, 'echo_input', 'echo', '{}'::jsonb, NULL),
    ('f2c8d6ef-9d9c-4c8a-91b3-7c6a7d2fb112',
     'f2c8d6ef-9d9c-4c8a-91b3-7c6a7d2fb101', 2, 'approval_checkpoint', 'APPROVAL_CHECKPOINT',
     '{}'::jsonb, NULL),
    ('f2c8d6ef-9d9c-4c8a-91b3-7c6a7d2fb113',
     'f2c8d6ef-9d9c-4c8a-91b3-7c6a7d2fb101', 3, 'echo_output', 'echo', '{}'::jsonb, NULL)
ON CONFLICT (id) DO NOTHING;
