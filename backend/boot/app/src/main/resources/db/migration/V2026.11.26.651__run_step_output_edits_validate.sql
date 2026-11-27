-- Validates the constraints V2026.11.26.650 added NOT VALID. VALIDATE CONSTRAINT takes SHARE UPDATE EXCLUSIVE,
-- so run_step writers keep running while the table is scanned.

ALTER TABLE run_step VALIDATE CONSTRAINT run_step_edit_all_or_none;
ALTER TABLE run_step VALIDATE CONSTRAINT run_step_edit_diff_range;
