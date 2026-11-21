-- Personal access tokens an admin account uses for scripted API calls. Only the SHA-256 hex of the
-- token is stored. Rollback: DROP TABLE personal_access_token;
CREATE TABLE personal_access_token (
    id           uuid          PRIMARY KEY,
    user_id      uuid          NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    name         varchar(128)  NOT NULL,
    token_hash   varchar(64)   NOT NULL UNIQUE,
    created_at   timestamptz   NOT NULL,
    expires_at   timestamptz   NOT NULL,
    last_used_at timestamptz   NULL,
    revoked_at   timestamptz   NULL
);

CREATE INDEX idx_pat_user_live ON personal_access_token (user_id, created_at DESC) WHERE revoked_at IS NULL;
