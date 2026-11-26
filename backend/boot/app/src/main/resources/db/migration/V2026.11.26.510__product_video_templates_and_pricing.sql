-- Product Video template metadata (showroom-v1 / mc-v1), preset selections, and pricing for new capabilities.

ALTER TABLE product_video_input
    ADD COLUMN template_id          VARCHAR(64)  NOT NULL DEFAULT 'showroom-v1',
    ADD COLUMN template_version     INT          NOT NULL DEFAULT 1,
    ADD COLUMN voice_gender         VARCHAR(16)  NOT NULL DEFAULT 'male',
    ADD COLUMN mc_preset_id         VARCHAR(64)  NULL,
    ADD COLUMN background_preset_id VARCHAR(64)  NULL,
    ADD COLUMN scene_note           VARCHAR(500) NULL,
    ADD CONSTRAINT ck_product_video_template_id CHECK (template_id IN ('showroom-v1', 'mc-v1')),
    ADD CONSTRAINT ck_product_video_template_version CHECK (template_version >= 1),
    ADD CONSTRAINT ck_product_video_voice_gender CHECK (voice_gender IN ('male', 'female'));

INSERT INTO rate_card (id, rate_card_ref, pricing_unit, provider_key, credits_per_unit, markup_pct, effective_from)
SELECT gen_random_uuid(), p.ref, 'per_request', 'flowcortex', p.credits, 0, TIMESTAMPTZ '2026-11-23 00:00:00+00'
FROM (VALUES
    ('generate_keyframe', 40),
    ('lipsync_video', 120)
) AS p (ref, credits)
ON CONFLICT DO NOTHING;

INSERT INTO capability_pricing (capability, estimator_kind, rate_card_ref, pricing_unit, provider_key)
SELECT p.ref, 'rate_card', p.ref, 'per_request', 'flowcortex'
FROM (VALUES
    ('generate_keyframe'),
    ('lipsync_video')
) AS p (ref)
ON CONFLICT (capability) DO NOTHING;
