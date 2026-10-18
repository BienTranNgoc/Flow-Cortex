-- Phone verification by one-time code.
--
-- user_phone: one row per user; verifying a new number replaces the old one. A number has at
-- most one verified owner, enforced by the partial unique index, not by application checks.
--
-- phone_challenge: code_hash is BCrypt, never the code. Attempts are counted by a conditional
-- UPDATE (attempts < 5 AND consumed_at IS NULL AND invalidated_at IS NULL). Send caps and the
-- cooldown ladder read sends per number over the last 24 h, hence (phone_e164, created_at).
--
-- sms_outbox: the development SMS sink; nothing is delivered to a carrier.
-- FKs stay inside the user module.

CREATE TABLE user_phone (
    user_id      uuid         PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
    phone_e164   varchar(16)  NOT NULL,
    verified_at  timestamptz  NULL
);

CREATE UNIQUE INDEX uq_user_phone_verified_number
    ON user_phone (phone_e164) WHERE verified_at IS NOT NULL;

CREATE TABLE phone_challenge (
    id              uuid         PRIMARY KEY,
    user_id         uuid         NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    phone_e164      varchar(16)  NOT NULL,
    code_hash       varchar(255) NOT NULL,
    attempts        integer      NOT NULL DEFAULT 0,
    expires_at      timestamptz  NOT NULL,
    consumed_at     timestamptz  NULL,
    invalidated_at  timestamptz  NULL,
    created_at      timestamptz  NOT NULL
);

CREATE INDEX idx_phone_challenge_phone_created_at ON phone_challenge (phone_e164, created_at);
CREATE INDEX idx_phone_challenge_user_id ON phone_challenge (user_id);

CREATE TABLE sms_outbox (
    id          uuid         PRIMARY KEY,
    phone_e164  varchar(16)  NOT NULL,
    body        text         NOT NULL,
    created_at  timestamptz  NOT NULL
);

CREATE INDEX idx_sms_outbox_created_at ON sms_outbox (created_at DESC);
