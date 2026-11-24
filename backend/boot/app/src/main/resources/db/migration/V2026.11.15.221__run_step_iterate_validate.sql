-- Validates the constraints V2026.11.15.220 added NOT VALID. VALIDATE CONSTRAINT takes SHARE UPDATE
-- EXCLUSIVE (and SHARE ROW EXCLUSIVE on the referenced table), so run_step writers keep running
-- while the table is scanned.

ALTER TABLE run_step VALIDATE CONSTRAINT run_step_parent_fk;
ALTER TABLE run_step VALIDATE CONSTRAINT run_step_parent_all_or_none;
