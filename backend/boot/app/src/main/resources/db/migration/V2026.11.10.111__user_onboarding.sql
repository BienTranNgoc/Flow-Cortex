-- Additive: creates user_onboarding (a member's quiz answers, journey progress and builder state);
-- no existing row is touched and none is written here. Version block 60-69.
-- No FK to app_user: the onboarding module reaches users only through their public port.
CREATE TABLE user_onboarding (
    user_id              uuid PRIMARY KEY,
    purposes             jsonb,
    source               varchar(20),
    ai_level             varchar(20),
    industries           jsonb,
    content_types        jsonb,
    usage_type           varchar(20),
    completed_at         timestamptz,
    progress             jsonb       NOT NULL DEFAULT '{}'::jsonb,
    builder_state        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    builder_completed_at timestamptz,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    CONSTRAINT user_onboarding_source_chk
        CHECK (source IS NULL OR source IN ('facebook', 'tiktok', 'linkedin', 'google', 'referral', 'other')),
    CONSTRAINT user_onboarding_ai_level_chk
        CHECK (ai_level IS NULL OR ai_level IN ('beginner', 'tried', 'automation', 'builder')),
    CONSTRAINT user_onboarding_usage_type_chk
        CHECK (usage_type IS NULL OR usage_type IN ('personal', 'team')),
    CONSTRAINT user_onboarding_progress_object_chk CHECK (jsonb_typeof(progress) = 'object'),
    CONSTRAINT user_onboarding_builder_state_object_chk CHECK (jsonb_typeof(builder_state) = 'object')
);
