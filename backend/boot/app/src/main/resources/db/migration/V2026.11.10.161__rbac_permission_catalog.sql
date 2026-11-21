-- Permission catalog, system roles and their keys. The Java enum Permission is the source of the
-- key list; the startup check compares it with this table. ADMIN holds every key through its
-- super_admin flag and has no role_permission rows, so a key added later reaches it without a seed.

CREATE TABLE permission (
    key              varchar(64) PRIMARY KEY,
    module           varchar(32) NOT NULL,
    feature          varchar(32) NOT NULL,
    action           varchar(32) NOT NULL,
    super_admin_only boolean     NOT NULL,
    sort_order       int         NOT NULL
);

CREATE TABLE role (
    code        varchar(20) PRIMARY KEY,
    staff       boolean     NOT NULL,
    super_admin boolean     NOT NULL,
    system      boolean     NOT NULL,
    sort_order  int         NOT NULL
);

CREATE TABLE role_permission (
    role_code      varchar(20) NOT NULL REFERENCES role (code) ON DELETE CASCADE,
    permission_key varchar(64) NOT NULL REFERENCES permission (key),
    PRIMARY KEY (role_code, permission_key)
);

INSERT INTO permission (key, module, feature, action, super_admin_only, sort_order) VALUES
    ('user.account.view',            'user',     'account',       'view',        false,  1),
    ('user.account.create',          'user',     'account',       'create',      false,  2),
    ('user.account.update',          'user',     'account',       'update',      false,  3),
    ('user.account.change_role',     'user',     'account',       'change_role', false,  4),
    ('user.account.suspend',         'user',     'account',       'suspend',     false,  5),
    ('user.account.delete',          'user',     'account',       'delete',      false,  6),
    ('workflow.run.view',            'workflow', 'run',           'view',        false,  7),
    ('workflow.workflow.manage',     'workflow', 'workflow',      'manage',      false,  8),
    ('support.ticket.view',          'support',  'ticket',        'view',        false,  9),
    ('support.ticket.reply',         'support',  'ticket',        'reply',       false, 10),
    ('support.ticket.close',         'support',  'ticket',        'close',       false, 11),
    ('content.help_topic.view',      'content',  'help_topic',    'view',        false, 12),
    ('content.help_topic.manage',    'content',  'help_topic',    'manage',      false, 13),
    ('content.help_article.view',    'content',  'help_article',  'view',        false, 14),
    ('content.help_article.manage',  'content',  'help_article',  'manage',      false, 15),
    ('platform.setting.view',        'platform', 'setting',       'view',        false, 16),
    ('platform.setting.update',      'platform', 'setting',       'update',      false, 17),
    ('platform.sms_outbox.view',     'platform', 'sms_outbox',    'view',        false, 18),
    ('system.role.view',             'system',   'role',          'view',        true,  19),
    ('system.admin_account.view',    'system',   'admin_account', 'view',        true,  20),
    ('system.admin_account.manage',  'system',   'admin_account', 'manage',      true,  21);

INSERT INTO role (code, staff, super_admin, system, sort_order) VALUES
    ('ADMIN',             false, true,  true, 1),
    ('OPERATION_MANAGER', true,  false, true, 2),
    ('CUSTOMER_SUPPORT',  true,  false, true, 3),
    ('CONTENT_EDITOR',    true,  false, true, 4),
    ('PLATFORM_OPERATOR', true,  false, true, 5),
    ('MEMBER',            false, false, true, 6),
    ('CREATOR',           false, false, true, 7);

-- Every key that is not super-admin-only, except the workflow write key, which stays with ADMIN.
INSERT INTO role_permission (role_code, permission_key)
SELECT 'OPERATION_MANAGER', key
FROM permission
WHERE NOT super_admin_only AND key <> 'workflow.workflow.manage';

INSERT INTO role_permission (role_code, permission_key) VALUES
    ('CUSTOMER_SUPPORT',  'support.ticket.view'),
    ('CUSTOMER_SUPPORT',  'support.ticket.reply'),
    ('CUSTOMER_SUPPORT',  'support.ticket.close'),
    ('CUSTOMER_SUPPORT',  'user.account.view'),
    ('CUSTOMER_SUPPORT',  'workflow.run.view'),
    ('CONTENT_EDITOR',    'content.help_topic.view'),
    ('CONTENT_EDITOR',    'content.help_topic.manage'),
    ('CONTENT_EDITOR',    'content.help_article.view'),
    ('CONTENT_EDITOR',    'content.help_article.manage'),
    ('PLATFORM_OPERATOR', 'platform.setting.view'),
    ('PLATFORM_OPERATOR', 'platform.setting.update'),
    ('PLATFORM_OPERATOR', 'support.ticket.view'),
    ('PLATFORM_OPERATOR', 'support.ticket.reply'),
    ('PLATFORM_OPERATOR', 'user.account.view'),
    ('PLATFORM_OPERATOR', 'workflow.run.view');

-- Admin deactivation is separate from account status: the person keeps member-app use.
ALTER TABLE app_user
    ADD COLUMN admin_disabled_at timestamptz,
    ADD COLUMN admin_disabled_by uuid;

-- NOT VALID then VALIDATE: a stray role value fails the migration loudly instead of silently.
ALTER TABLE app_user
    ADD CONSTRAINT fk_app_user_role FOREIGN KEY (role) REFERENCES role (code) NOT VALID;
ALTER TABLE app_user VALIDATE CONSTRAINT fk_app_user_role;

CREATE INDEX idx_app_user_role ON app_user (role);
