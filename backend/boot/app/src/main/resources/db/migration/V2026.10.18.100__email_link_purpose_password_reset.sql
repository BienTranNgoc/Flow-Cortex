-- PASSWORD_RESET: an emailed link that lets an account with a password choose a new one.
-- The check only widens, so every existing row already satisfies it. Flyway runs this file in
-- one transaction: the ACCESS EXCLUSIVE lock the DROP takes is held through the validation scan.
-- Rolling back means deleting the PASSWORD_RESET rows, then restoring the three-value check.

ALTER TABLE email_confirmation_token DROP CONSTRAINT ck_email_confirmation_token_purpose;

ALTER TABLE email_confirmation_token
    ADD CONSTRAINT ck_email_confirmation_token_purpose
        CHECK (purpose IN ('EMAIL_CONFIRM', 'PASSWORD_LINK', 'PASSWORD_SETUP', 'PASSWORD_RESET')) NOT VALID;

ALTER TABLE email_confirmation_token VALIDATE CONSTRAINT ck_email_confirmation_token_purpose;
