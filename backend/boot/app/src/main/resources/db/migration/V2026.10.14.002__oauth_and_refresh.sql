-- Google OAuth 2.0 + refresh-token rotation.
--
-- refresh_token holds the SHA-256 hash, never the raw token. family_id groups
-- rotations: a leak anywhere in the chain revokes the whole family (a
-- legitimate user re-authenticates once; the alternative is letting reuse go
-- undetected). Every row of a family carries the same expires_at (the session's
-- absolute end) and remember_me (whether the cookie outlives the browser session).
-- user_id FK is local to this table's column set and stays inside the user
-- module, so the no-FK-across-modules rule does not apply.
--
-- app_user gains:
--   google_subject   — Google's stable per-user `sub` claim. Unique partial so
--                       non-Google users (subject NULL) are not blocked.
--   has_password     — distinguishes password accounts from OAuth-only ones.
--   oauth_provider   — placeholder for the multi-provider future; only
--                       'google' is set today.

CREATE TABLE refresh_token (
    id              uuid         PRIMARY KEY,
    user_id         uuid         NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash      varchar(255) NOT NULL UNIQUE,
    family_id       uuid         NOT NULL,
    issued_at       timestamptz  NOT NULL,
    expires_at      timestamptz  NOT NULL,
    remember_me     boolean      NOT NULL DEFAULT false,
    used_at         timestamptz  NULL,
    revoked_at      timestamptz  NULL,
    replaced_by_id  uuid         NULL REFERENCES refresh_token(id) ON DELETE SET NULL,
    created_ip      varchar(64)  NULL,
    user_agent      varchar(512) NULL
);

CREATE INDEX idx_refresh_user_active
    ON refresh_token (user_id) WHERE revoked_at IS NULL AND used_at IS NULL;

CREATE INDEX idx_refresh_family ON refresh_token (family_id);

ALTER TABLE app_user
    ADD COLUMN google_subject varchar(255) NULL,
    ADD COLUMN has_password   boolean      NOT NULL DEFAULT true,
    ADD COLUMN oauth_provider varchar(40)  NULL;

CREATE UNIQUE INDEX idx_app_user_google_subject
    ON app_user (google_subject) WHERE google_subject IS NOT NULL;

-- Emails are matched case-insensitively (login, register, Google), so uniqueness must be too.
CREATE UNIQUE INDEX uq_app_user_email_lower ON app_user (lower(email));

CREATE INDEX idx_app_user_oauth_provider
    ON app_user (oauth_provider) WHERE oauth_provider IS NOT NULL;
