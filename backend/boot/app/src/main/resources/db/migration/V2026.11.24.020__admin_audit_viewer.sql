-- Audit viewer and PII keys: the trigram extension its search index needs, catalog rows and default grants.
-- The audit indexes are in V2026.11.24.022, built CONCURRENTLY outside this script's transaction.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- The three keys take the next sort_order values in the order of the Permission enum.
INSERT INTO permission (key, module, feature, action, super_admin_only, sort_order)
SELECT 'system.audit_log.view', 'system', 'audit_log', 'view', false, coalesce(max(sort_order), 0) + 1
FROM permission
ON CONFLICT DO NOTHING;

INSERT INTO permission (key, module, feature, action, super_admin_only, sort_order)
SELECT 'system.audit_log.export', 'system', 'audit_log', 'export', false, coalesce(max(sort_order), 0) + 1
FROM permission
ON CONFLICT DO NOTHING;

INSERT INTO permission (key, module, feature, action, super_admin_only, sort_order)
SELECT 'user.account.view_pii', 'user', 'account', 'view_pii', false, coalesce(max(sort_order), 0) + 1
FROM permission
ON CONFLICT DO NOTHING;

INSERT INTO role_permission (role_code, permission_key) VALUES
    ('OPERATION_MANAGER', 'system.audit_log.view'),
    ('OPERATION_MANAGER', 'user.account.view_pii'),
    ('CUSTOMER_SUPPORT', 'user.account.view_pii')
ON CONFLICT DO NOTHING;
