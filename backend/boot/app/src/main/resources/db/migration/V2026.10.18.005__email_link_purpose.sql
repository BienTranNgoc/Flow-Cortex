-- email_confirmation_token now holds three kinds of one-time token, told apart by purpose:
--   EMAIL_CONFIRM  the emailed confirmation link (every existing row)
--   PASSWORD_LINK  an emailed link that signs a confirmed account without a password in
--   PASSWORD_SETUP the token a password-setup form must present with the new password
-- A token is spent only by a request that names its purpose. A constant default is a
-- metadata-only change in PostgreSQL, so existing rows are not rewritten. Flyway runs this file
-- in one transaction, so the ACCESS EXCLUSIVE lock the ADD COLUMN takes is held through the
-- check's validation scan until commit: reads and writes of the table wait for that scan.
-- Additive only: code that does not know the column keeps working, and rolling back means
-- dropping it.

ALTER TABLE email_confirmation_token
    ADD COLUMN purpose varchar(32) NOT NULL DEFAULT 'EMAIL_CONFIRM';

ALTER TABLE email_confirmation_token
    ADD CONSTRAINT ck_email_confirmation_token_purpose
        CHECK (purpose IN ('EMAIL_CONFIRM', 'PASSWORD_LINK', 'PASSWORD_SETUP')) NOT VALID;

ALTER TABLE email_confirmation_token VALIDATE CONSTRAINT ck_email_confirmation_token_purpose;
