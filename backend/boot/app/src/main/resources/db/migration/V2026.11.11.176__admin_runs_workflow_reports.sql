-- Per-workflow run index and four workflow report keys with their staff seeds. ADMIN holds the new
-- keys through its super_admin flag and gets no role_permission rows.

CREATE INDEX idx_workflow_run_workflow_started ON workflow_run (workflow_id, started_at DESC);

INSERT INTO permission (key, module, feature, action, super_admin_only, sort_order)
SELECT k.key, k.module, k.feature, k.action, false, base.top + k.pos
FROM (VALUES
    ('workflow.run.export',      'workflow', 'run',      'export', 1),
    ('workflow.run.cancel',      'workflow', 'run',      'cancel', 2),
    ('workflow.workflow.view',   'workflow', 'workflow', 'view',   3),
    ('workflow.workflow.export', 'workflow', 'workflow', 'export', 4)) AS k (key, module, feature, action, pos)
CROSS JOIN (SELECT COALESCE(MAX(sort_order), 0) AS top FROM permission) AS base;

INSERT INTO role_permission (role_code, permission_key) VALUES
    ('OPERATION_MANAGER', 'workflow.run.export'),
    ('OPERATION_MANAGER', 'workflow.run.cancel'),
    ('OPERATION_MANAGER', 'workflow.workflow.view'),
    ('OPERATION_MANAGER', 'workflow.workflow.export'),
    ('CUSTOMER_SUPPORT',  'workflow.workflow.view'),
    ('PLATFORM_OPERATOR', 'workflow.run.export'),
    ('PLATFORM_OPERATOR', 'workflow.workflow.view'),
    ('PLATFORM_OPERATOR', 'workflow.workflow.export');
