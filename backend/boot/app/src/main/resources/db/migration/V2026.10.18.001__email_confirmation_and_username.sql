-- Registration fields + email confirmation.
--
-- name stays the display name; first_name/last_name are written alongside it by new
-- registrations and stay NULL on older rows. username is optional, so uniqueness is partial.
-- Every account that exists before confirmation was introduced counts as confirmed.
--
-- email_confirmation_token holds the SHA-256 of the emailed token, never the token. A token is
-- consumed by one conditional UPDATE (used_at IS NULL AND expires_at > now()), which is what
-- makes a link single-use when two requests race. The FK stays inside the user module.

ALTER TABLE app_user
    ADD COLUMN first_name         varchar(100) NULL,
    ADD COLUMN last_name          varchar(100) NULL,
    ADD COLUMN username           varchar(20)  NULL,
    ADD COLUMN email_confirmed_at timestamptz  NULL;

UPDATE app_user SET email_confirmed_at = now();

CREATE UNIQUE INDEX uq_app_user_username ON app_user (username) WHERE username IS NOT NULL;

CREATE TABLE email_confirmation_token (
    id          uuid         PRIMARY KEY,
    user_id     uuid         NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash  varchar(64)  NOT NULL UNIQUE,
    expires_at  timestamptz  NOT NULL,
    used_at     timestamptz  NULL,
    created_at  timestamptz  NOT NULL
);

-- Resend invalidates the user's open tokens.
CREATE INDEX idx_email_confirmation_token_user_open
    ON email_confirmation_token (user_id) WHERE used_at IS NULL;
