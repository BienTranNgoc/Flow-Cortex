-- The member's own preferences switch becomes a third source of list changes, and a change can now go either way.
-- Additive: the widened CHECK and the defaulted column accept every row and insert mail already writes.

-- Postgres names an unnamed column CHECK <table>_<column>_check.
ALTER TABLE mail_unsubscribe_history DROP CONSTRAINT mail_unsubscribe_history_source_check;
ALTER TABLE mail_unsubscribe_history ADD CONSTRAINT mail_unsubscribe_history_source_check
    CHECK (source IN ('footer_link', 'one_click', 'preferences'));

-- Rows written before this migration are all unsubscribes.
ALTER TABLE mail_unsubscribe_history
    ADD COLUMN action varchar(16) NOT NULL DEFAULT 'unsubscribe' CHECK (action IN ('unsubscribe', 'resubscribe'));
