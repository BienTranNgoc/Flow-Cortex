-- Capability catalog (one row per step type the engine runs, written only by migrations) and the model
-- catalog (admin-managed rows under a capability). Nothing at runtime reads either table yet; they are
-- display and curation data. Both tables are new, so the migration alters no existing row or shape.
--
-- Rollback (revert the code too), in one transaction, valid only while no admin has written model rows;
-- after that, roll back with a forward migration instead:
--   DROP TABLE model_catalog;
--   DROP TABLE capability_catalog;
--   DELETE FROM role_permission WHERE permission_key IN ('ai.model.view', 'ai.model.update');
--   DELETE FROM permission WHERE key IN ('ai.model.view', 'ai.model.update');
--   DELETE FROM flyway_schema_history WHERE version = '2026.11.26.530';

CREATE TABLE capability_catalog (
    capability  text        PRIMARY KEY CHECK (capability ~ '^[A-Za-z0-9._-]{1,64}$'),
    category    text        NOT NULL CHECK (category IN ('ingest', 'analyze', 'generate', 'transform', 'present', 'review', 'deliver', 'control')),
    ui_group    text        NOT NULL CHECK (ui_group IN ('input', 'generate', 'edit', 'replace', 'analyze', 'deliver', 'control')),
    ui_icon     text        NULL CHECK (ui_icon IS NULL OR ui_icon ~ '^[a-z0-9-]{1,64}$'),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE model_catalog (
    capability      text        NOT NULL REFERENCES capability_catalog (capability) ON DELETE RESTRICT,
    model_key       text        NOT NULL CHECK (length(model_key) <= 128 AND model_key ~ '^[A-Za-z0-9._:-]+(/[A-Za-z0-9._:-]+)*$'),
    provider_key    text        NOT NULL CHECK (provider_key ~ '^[a-z0-9._-]{1,64}$'),
    vendor_label    text        NULL CHECK (vendor_label IS NULL OR length(vendor_label) BETWEEN 1 AND 128),
    maker_label     text        NULL CHECK (maker_label IS NULL OR length(maker_label) BETWEEN 1 AND 128),
    mode            text        NOT NULL CHECK (mode IN ('text', 'image', 'video', 'audio', 'transform', 'deliver', 'ingest', 'control')),
    enabled         boolean     NOT NULL DEFAULT true,
    not_implemented boolean     NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (capability, model_key)
);

CREATE INDEX idx_model_catalog_capability_enabled ON model_catalog (capability, enabled);

-- One row per step type the workflow engine registers (every executor type plus the two human-gate
-- types). ui_icon stays NULL: the console shows the group's icon until an admin sets one.
INSERT INTO capability_catalog (capability, category, ui_group) VALUES
    ('echo', 'control', 'control'),
    ('fail', 'control', 'control'),
    ('iterate', 'control', 'control'),
    ('workflow.call', 'control', 'control'),
    ('generate_script', 'generate', 'generate'),
    ('generate_storyboard', 'generate', 'generate'),
    ('generate_speech', 'generate', 'generate'),
    ('generate_video', 'generate', 'generate'),
    ('tts', 'generate', 'generate'),
    ('probe', 'analyze', 'analyze'),
    ('extract_audio', 'transform', 'edit'),
    ('align_audio', 'transform', 'edit'),
    ('render_video', 'transform', 'edit'),
    ('separate_audio', 'transform', 'edit'),
    ('translate', 'transform', 'edit'),
    ('APPROVAL_CHECKPOINT', 'review', 'control'),
    ('qa.review', 'review', 'analyze'),
    ('fetch_video', 'ingest', 'input'),
    ('ingest_video', 'ingest', 'input'),
    ('ingest_image', 'ingest', 'input'),
    ('connector.call', 'ingest', 'input'),
    ('drive.read', 'ingest', 'input'),
    ('load_template', 'ingest', 'input');

INSERT INTO permission (key, module, feature, action, super_admin_only, sort_order)
SELECT 'ai.model.view', 'ai', 'model', 'view', false, COALESCE(MAX(sort_order), 0) + 1 FROM permission;

INSERT INTO permission (key, module, feature, action, super_admin_only, sort_order)
SELECT 'ai.model.update', 'ai', 'model', 'update', false, COALESCE(MAX(sort_order), 0) + 1 FROM permission;

INSERT INTO role_permission (role_code, permission_key) VALUES
    ('OPERATION_MANAGER', 'ai.model.view'),
    ('OPERATION_MANAGER', 'ai.model.update'),
    ('PLATFORM_OPERATOR', 'ai.model.view'),
    ('PLATFORM_OPERATOR', 'ai.model.update');
