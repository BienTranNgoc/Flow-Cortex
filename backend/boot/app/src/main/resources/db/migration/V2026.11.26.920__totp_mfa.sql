-- Time-based one-time passwords as a second sign-in factor.
--
-- user_totp: one row per account. secret_encrypted is the keyring ciphertext (kid.iv.tag.ct) bound to the
-- account id; enabled_at is NULL while the setup is pending. last_used_step is the highest accepted 30 s
-- step, so a code is accepted once. failed_attempts and locked_until implement the per-account lockout.
-- user_recovery_code: one row per unspent code; a code is spent by deleting its row. code_hash is the hex
-- HMAC-SHA256 of the normalized code under the key named by key_id.
-- login_challenge: the half-finished sign-in of an account that has a second factor; token_hash is the
-- SHA-256 of the cookie value. failed_codes is bounded by the configured limit in the statements that
-- change it, not here, so the limit can change without a migration.
-- refresh_token.mfa_verified_at: set on the family's first row when the session passed the second factor,
-- copied to every rotation.
--
-- New empty tables and a nullable column without a default: no table rewrite, no backfill, and the code
-- that is still serving at migration time never reads or writes any of it.
--
-- Rollback (revert the code too; include the history delete so a later roll-forward re-applies it):
--   BEGIN;
--   ALTER TABLE refresh_token DROP COLUMN mfa_verified_at;
--   DROP TABLE login_challenge;
--   DROP TABLE user_recovery_code;
--   DROP TABLE user_totp;
--   DELETE FROM flyway_schema_history WHERE version = '2026.11.26.920';
--   COMMIT;
-- Dropping the tables deletes every enrolment; recovery of those rows means a restore.

CREATE TABLE user_totp (
    user_id          uuid         PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    secret_encrypted text         NOT NULL,
    enabled_at       timestamptz  NULL,
    last_used_step   bigint       NULL,
    failed_attempts  integer      NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    locked_until     timestamptz  NULL,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE user_recovery_code (
    id          uuid         PRIMARY KEY,
    user_id     uuid         NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    key_id      varchar(32)  NOT NULL,
    code_hash   char(64)     NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_recovery_code UNIQUE (user_id, key_id, code_hash)
);

CREATE TABLE login_challenge (
    id            uuid         PRIMARY KEY,
    user_id       uuid         NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash    char(64)     NOT NULL UNIQUE,
    remember_me   boolean      NOT NULL,
    origin        varchar(16)  NOT NULL CHECK (origin IN ('password', 'google', 'link')),
    failed_codes  integer      NOT NULL DEFAULT 0 CHECK (failed_codes >= 0),
    expires_at    timestamptz  NOT NULL,
    spent_at      timestamptz  NULL,
    created_at    timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX idx_login_challenge_expires ON login_challenge (expires_at);
CREATE INDEX idx_login_challenge_user ON login_challenge (user_id);

ALTER TABLE refresh_token ADD COLUMN mfa_verified_at timestamptz NULL;
