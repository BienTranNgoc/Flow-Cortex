-- Validates the constraints V2026.11.26.660 added NOT VALID. VALIDATE CONSTRAINT takes SHARE UPDATE EXCLUSIVE,
-- so workflow_run writers keep running while the table is scanned.

ALTER TABLE workflow_run VALIDATE CONSTRAINT workflow_run_feedback_shape;
ALTER TABLE workflow_run VALIDATE CONSTRAINT workflow_run_feedback_detail_length;
