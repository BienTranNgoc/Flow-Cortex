-- A code now carries its purpose (a plain verification or a number change) and, once a change is
-- verified, the number it replaced with its undo deadline. The constant default lets existing rows
-- read 'verify' without a table rewrite; the plain index is a short lock on a table that holds only
-- short-lived per-member code rows. Rollback: drop the index, then the four columns.
ALTER TABLE phone_challenge
    ADD COLUMN purpose          varchar(16) NOT NULL DEFAULT 'verify'
        CONSTRAINT ck_phone_challenge_purpose CHECK (purpose IN ('verify', 'change')),
    ADD COLUMN prior_phone_e164 varchar(16) NULL,
    ADD COLUMN undo_until       timestamptz NULL,
    ADD COLUMN reverted_at      timestamptz NULL;

CREATE INDEX idx_phone_challenge_user_purpose_created ON phone_challenge (user_id, purpose, created_at DESC);
