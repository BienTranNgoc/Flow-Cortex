-- Product Video input snapshot and the shared MVP workflow.

CREATE TABLE product_video_input (
    run_id UUID PRIMARY KEY REFERENCES workflow_run(id) ON DELETE CASCADE,
    product_name VARCHAR(200) NOT NULL,
    description VARCHAR(4000) NOT NULL,
    cta VARCHAR(500) NOT NULL,
    price VARCHAR(200),
    promotion VARCHAR(500),
    aspect_ratio VARCHAR(8) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_product_video_aspect_ratio CHECK (aspect_ratio = '9:16')
);

INSERT INTO workflow (id, owner_id, name, is_shared, created_at, updated_at)
VALUES (
    '9aa35334-b672-4b6c-93e8-1a4c2cc14301',
    '00000000-0000-0000-0000-000000000000',
    'Product Video',
    true,
    clock_timestamp(),
    clock_timestamp()
) ON CONFLICT (id) DO NOTHING;

INSERT INTO workflow_step (id, workflow_id, step_order, name, type, config, input_ref)
VALUES
    ('9aa35334-b672-4b6c-93e8-1a4c2cc14311',
     '9aa35334-b672-4b6c-93e8-1a4c2cc14301', 1, 'generate_product_script', 'generate_script',
     '{"profile":"product_ad_v1"}'::jsonb, NULL),
    ('9aa35334-b672-4b6c-93e8-1a4c2cc14312',
     '9aa35334-b672-4b6c-93e8-1a4c2cc14301', 2, 'approve_product_script', 'APPROVAL_CHECKPOINT',
     '{}'::jsonb, NULL),
    ('9aa35334-b672-4b6c-93e8-1a4c2cc14313',
     '9aa35334-b672-4b6c-93e8-1a4c2cc14301', 3, 'generate_speech', 'generate_speech',
     '{"profile":"vi_voice_v1"}'::jsonb, '{step:2.script}'),
    ('9aa35334-b672-4b6c-93e8-1a4c2cc14314',
     '9aa35334-b672-4b6c-93e8-1a4c2cc14301', 4, 'render_product_video', 'render_video',
     '{"profile":"vertical_ad_v1"}'::jsonb, NULL)
ON CONFLICT (id) DO NOTHING;
