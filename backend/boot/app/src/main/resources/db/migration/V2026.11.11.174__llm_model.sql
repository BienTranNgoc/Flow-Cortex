-- Registry of language models the admin console manages. Keyed by the model id; no price and no
-- provider key (keys stay in the environment). provider_key has no CHECK: the set of providers is code.

CREATE TABLE llm_model (
    model_key          varchar(128) PRIMARY KEY,
    provider_key       varchar(32)  NOT NULL,
    display_name       varchar(128) NOT NULL,
    max_output_tokens  integer      NOT NULL DEFAULT 4096 CHECK (max_output_tokens BETWEEN 1 AND 8192),
    supports_vision    boolean      NOT NULL DEFAULT false,
    supports_audio     boolean      NOT NULL DEFAULT false,
    active             boolean      NOT NULL DEFAULT true,
    display_order      smallint     NOT NULL DEFAULT 0,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX llm_model_display_order_idx ON llm_model (display_order, model_key);

INSERT INTO permission (key, module, feature, action, super_admin_only, sort_order)
SELECT 'ai.provider.view', 'ai', 'provider', 'view', false, COALESCE(MAX(sort_order), 0) + 1 FROM permission;

INSERT INTO permission (key, module, feature, action, super_admin_only, sort_order)
SELECT 'ai.provider.update', 'ai', 'provider', 'update', false, COALESCE(MAX(sort_order), 0) + 1 FROM permission;

INSERT INTO role_permission (role_code, permission_key) VALUES
    ('OPERATION_MANAGER', 'ai.provider.view'),
    ('OPERATION_MANAGER', 'ai.provider.update'),
    ('PLATFORM_OPERATOR', 'ai.provider.view'),
    ('PLATFORM_OPERATOR', 'ai.provider.update');
