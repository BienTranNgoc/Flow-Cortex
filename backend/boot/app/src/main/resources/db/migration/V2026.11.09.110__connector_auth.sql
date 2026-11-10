-- Connector credentials: one row per member and provider, tokens stored as ciphertext.
--
-- No FK to app_user: users belong to another module, and tables of one module carry no FK to
-- another's. A member's rows are removed by the connector module's own disconnect.
-- access_token / refresh_token hold the cipher's "kid.iv.tag.ct" value, never a plaintext token;
-- metadata is non-secret display data (email, name) and must hold no token.
-- usage_enabled is the member's switch and is independent of is_active (false = revoked or
-- needs reconnecting).
--
-- New table, expand-only. Rollback: DROP TABLE connector_auth (nothing else reads it).

CREATE TABLE connector_auth (
    id             uuid         PRIMARY KEY,
    user_id        uuid         NOT NULL,
    provider       varchar(64)  NOT NULL CHECK (provider ~ '^[a-z][a-z0-9_-]{0,63}$'),
    access_token   text         NOT NULL,
    refresh_token  text         NULL,
    expires_at     timestamptz  NULL,
    scopes         text[]       NOT NULL DEFAULT '{}',
    metadata       jsonb        NOT NULL DEFAULT '{}',
    is_active      boolean      NOT NULL DEFAULT true,
    usage_enabled  boolean      NOT NULL DEFAULT true,
    revoked_at     timestamptz  NULL,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_connector_auth_user_provider UNIQUE (user_id, provider)
);
