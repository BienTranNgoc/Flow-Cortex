-- Validates the constraint V2026.11.15.230 added NOT VALID. VALIDATE CONSTRAINT scans the table
-- under SHARE UPDATE EXCLUSIVE, which does not block reads or writes of support_ai_message; every
-- existing row holds one of the three older outcomes, so it cannot fail on existing data.

ALTER TABLE support_ai_message VALIDATE CONSTRAINT ck_support_ai_message_outcome;
