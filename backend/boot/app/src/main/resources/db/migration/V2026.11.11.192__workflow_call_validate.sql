-- Validates the constraints V2026.11.11.190 added NOT VALID. VALIDATE CONSTRAINT scans the table
-- under SHARE UPDATE EXCLUSIVE, which does not block reads or writes of workflow_run; every
-- existing row satisfies them (no parent, depth 0), so it cannot fail on existing data.

ALTER TABLE workflow_run VALIDATE CONSTRAINT workflow_run_parent_run_fk;
ALTER TABLE workflow_run VALIDATE CONSTRAINT workflow_run_parent_step_fk;
ALTER TABLE workflow_run VALIDATE CONSTRAINT workflow_run_parent_all_or_none;
ALTER TABLE workflow_run VALIDATE CONSTRAINT workflow_run_call_depth;
