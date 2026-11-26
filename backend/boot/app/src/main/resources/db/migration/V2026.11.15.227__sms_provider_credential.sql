-- The token chain of an SMS carrier account: one row per account, both tokens sealed (AES-256-GCM,
-- value kid.iv.tag.ct, account name as associated data), so the chain survives restarts and is shared
-- by every backend instance. seed_fingerprint is the SHA-256 of the refresh token the operator put
-- in the environment: a restart with the same seed leaves a renewed chain alone. A revoked chain is
-- 'reseed_required' until the seed changes. New empty table, nothing reads it unless the VietGuys
-- sender is selected. Rollback: drop the table.

CREATE TABLE sms_provider_credential (
    account            varchar(32)  PRIMARY KEY,
    refresh_token_enc  text         NOT NULL,
    access_token_enc   text         NULL,
    access_expires_at  timestamptz  NULL,
    state              varchar(16)  NOT NULL
        CONSTRAINT ck_sms_provider_credential_state CHECK (state IN ('active', 'reseed_required')),
    seed_fingerprint   char(64)     NOT NULL,
    last_renewed_at    timestamptz  NULL,
    updated_at         timestamptz  NOT NULL
);
