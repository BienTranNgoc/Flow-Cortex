-- Google OAuth 2.0 + refresh-token rotation (B3).
--
-- refresh_token holds the SHA-256 hash, never the raw token. family_id groups
-- rotations: a leak anywhere in the chain revokes the whole family (a
-- legitimate user re-authenticates once; the alternative is letting reuse go
-- undetected). user_id FK is local to this table's column set and stays inside
-- the user module, so the cross-module no-FK rule from personal.md §1.3 does
-- not apply.
--
-- app_user gains:
--   token_version    — bumped on logout so an issued access JWT's `tv` claim
--                       stops matching (B2 spec called for this; the column
--                       lands in this migration).
--   google_subject   — Google's stable per-user `sub` claim. Unique partial so
--                       non-Google users (subject NULL) are not blocked.
--   has_password     — distinguishes password accounts from OAuth-only ones,
--                       used by the G5 trust-existing-account flow.
--   oauth_provider   — placeholder for the multi-provider future; only
--                       'google' is set today.

CREATE TABLE refresh_token (
    id              uuid         PRIMARY KEY,
    user_id         uuid         NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash      varchar(255) NOT NULL UNIQUE,
    family_id       uuid         NOT NULL,
    issued_at       timestamptz  NOT NULL,
    expires_at      timestamptz  NOT NULL,
    used_at         timestamptz  NULL,
    revoked_at      timestamptz  NULL,
    replaced_by_id  uuid         NULL REFERENCES refresh_token(id),
    created_ip      varchar(64)  NULL,
    user_agent      varchar(512) NULL
);

CREATE INDEX idx_refresh_user_active
    ON refresh_token (user_id) WHERE revoked_at IS NULL AND used_at IS NULL;

CREATE INDEX idx_refresh_family ON refresh_token (family_id);

ALTER TABLE app_user
    ADD COLUMN token_version  bigint       NOT NULL DEFAULT 0,
    ADD COLUMN google_subject varchar(255) NULL,
    ADD COLUMN has_password   boolean      NOT NULL DEFAULT true,
    ADD COLUMN oauth_provider varchar(40)  NULL;

CREATE UNIQUE INDEX idx_app_user_google_subject
    ON app_user (google_subject) WHERE google_subject IS NOT NULL;

CREATE INDEX idx_app_user_oauth_provider
    ON app_user (oauth_provider) WHERE oauth_provider IS NOT NULL;
