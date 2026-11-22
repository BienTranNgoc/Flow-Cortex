-- Support request assignment and the debounced reply mail, all additive and nullable.
-- Rollback: drop the five indexes, then the two CHECK constraints and the four columns
-- (assignee_id, assigned_at, reply_mail_due_at, reply_mail_message_id). pg_trgm may stay installed.

-- No foreign key: the account lives in the user module, as requester_id does.
ALTER TABLE support_request
    ADD COLUMN assignee_id uuid NULL,
    ADD COLUMN assigned_at timestamptz NULL,
    ADD CONSTRAINT ck_support_request_assignment CHECK ((assignee_id IS NULL) = (assigned_at IS NULL));

-- No foreign key on the message either: it is deleted only with its request, which removes this row
-- too, and ON DELETE SET NULL would break the CHECK.
ALTER TABLE support_request
    ADD COLUMN reply_mail_due_at timestamptz NULL,
    ADD COLUMN reply_mail_message_id uuid NULL,
    ADD CONSTRAINT ck_support_request_reply_mail CHECK ((reply_mail_due_at IS NULL) = (reply_mail_message_id IS NULL));

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_support_request_assignee ON support_request (assignee_id, last_activity_at DESC);
CREATE INDEX idx_support_request_created_at ON support_request (created_at);
CREATE INDEX idx_support_request_reply_mail_due ON support_request (reply_mail_due_at)
    WHERE reply_mail_due_at IS NOT NULL;
CREATE INDEX idx_support_request_closed_activity ON support_request (last_activity_at)
    WHERE status = 'closed';
CREATE INDEX idx_support_request_subject_trgm ON support_request USING gin (lower(subject) gin_trgm_ops);
