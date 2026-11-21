-- Payment settings console: the two permission keys, the view grant for OPERATION_MANAGER (ADMIN
-- holds every key through super_admin; no other role is granted either), and the optional reason an
-- admin gives for a setting change.

INSERT INTO permission (key, module, feature, action, super_admin_only, sort_order)
SELECT k.key, 'payment', 'setting', k.action, false, base.top + k.pos
FROM (VALUES ('payment.setting.view', 'view', 1), ('payment.setting.update', 'update', 2)) AS k (key, action, pos)
CROSS JOIN (SELECT COALESCE(MAX(sort_order), 0) AS top FROM permission) AS base;

INSERT INTO role_permission (role_code, permission_key) VALUES
    ('OPERATION_MANAGER', 'payment.setting.view');

ALTER TABLE app_setting_change ADD COLUMN reason varchar(2000);
