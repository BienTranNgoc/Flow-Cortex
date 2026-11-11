-- Permission to assign support requests. Held by OPERATION_MANAGER and CUSTOMER_SUPPORT; ADMIN
-- holds it through its super_admin flag.
-- Rollback: delete the two role_permission rows and the permission row for 'support.ticket.assign',
-- then close the sort_order gap it leaves.

-- The key sits right after support.ticket.close, matching the Permission enum's declaration order,
-- so the keys after it move down one place. The guard makes a re-run leave the order untouched.
UPDATE permission
SET sort_order = sort_order + 1
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE key = 'support.ticket.assign')
  AND sort_order > (SELECT sort_order FROM permission WHERE key = 'support.ticket.close');

INSERT INTO permission (key, module, feature, action, super_admin_only, sort_order)
SELECT 'support.ticket.assign', 'support', 'ticket', 'assign', false, sort_order + 1
FROM permission
WHERE key = 'support.ticket.close'
ON CONFLICT (key) DO NOTHING;

INSERT INTO role_permission (role_code, permission_key) VALUES
    ('OPERATION_MANAGER', 'support.ticket.assign'),
    ('CUSTOMER_SUPPORT',  'support.ticket.assign')
ON CONFLICT DO NOTHING;
