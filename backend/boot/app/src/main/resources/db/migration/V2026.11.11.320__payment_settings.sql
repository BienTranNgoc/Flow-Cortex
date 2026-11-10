-- Payment settings console: the two permission keys, the view grant for OPERATION_MANAGER (ADMIN
-- holds every key through super_admin; no other role is granted either), and the optional reason an
-- admin gives for a setting change.

INSERT INTO permission (key, module, feature, action, super_admin_only, sort_order) VALUES
    ('payment.setting.view',   'payment', 'setting', 'view',   false, 22),
    ('payment.setting.update', 'payment', 'setting', 'update', false, 23);

INSERT INTO role_permission (role_code, permission_key) VALUES
    ('OPERATION_MANAGER', 'payment.setting.view');

ALTER TABLE app_setting_change ADD COLUMN reason varchar(2000);
