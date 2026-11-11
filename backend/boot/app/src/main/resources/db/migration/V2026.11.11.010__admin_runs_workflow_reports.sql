-- Per-workflow run index and four workflow report keys with their staff seeds. ADMIN holds the new
-- keys through its super_admin flag and gets no role_permission rows.

CREATE INDEX idx_workflow_run_workflow_started ON workflow_run (workflow_id, started_at DESC);

INSERT INTO permission (key, module, feature, action, super_admin_only, sort_order) VALUES
    ('workflow.run.export',      'workflow', 'run',      'export', false, 22),
    ('workflow.run.cancel',      'workflow', 'run',      'cancel', false, 23),
    ('workflow.workflow.view',   'workflow', 'workflow', 'view',   false, 24),
    ('workflow.workflow.export', 'workflow', 'workflow', 'export', false, 25);

INSERT INTO role_permission (role_code, permission_key) VALUES
    ('OPERATION_MANAGER', 'workflow.run.export'),
    ('OPERATION_MANAGER', 'workflow.run.cancel'),
    ('OPERATION_MANAGER', 'workflow.workflow.view'),
    ('OPERATION_MANAGER', 'workflow.workflow.export'),
    ('CUSTOMER_SUPPORT',  'workflow.workflow.view'),
    ('PLATFORM_OPERATOR', 'workflow.run.export'),
    ('PLATFORM_OPERATOR', 'workflow.workflow.view'),
    ('PLATFORM_OPERATOR', 'workflow.workflow.export');
