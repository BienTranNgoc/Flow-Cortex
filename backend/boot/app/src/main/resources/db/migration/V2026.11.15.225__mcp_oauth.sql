-- MCP authorization server: dynamically registered clients, short-lived authorization codes and the
-- link from a personal access token to the client it was issued to.
-- Rollback: DROP TABLE oauth_issued_token, oauth_authorization_code, oauth_client;

CREATE TABLE oauth_client (
    client_id          varchar(40)  PRIMARY KEY,
    client_secret_hash varchar(64),
    client_name        varchar(128) NOT NULL,
    redirect_uris      jsonb        NOT NULL,
    auth_method        varchar(24)  NOT NULL
        CONSTRAINT ck_oauth_client_auth_method CHECK (auth_method IN ('client_secret_post', 'client_secret_basic', 'none')),
    created_at         timestamptz  NOT NULL
);
CREATE INDEX idx_oauth_client_created_at ON oauth_client (created_at);

CREATE TABLE oauth_authorization_code (
    code_hash       varchar(64)   PRIMARY KEY,
    client_id       varchar(40)   NOT NULL REFERENCES oauth_client (client_id) ON DELETE CASCADE,
    user_id         uuid          NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    redirect_uri    varchar(2000) NOT NULL,
    code_challenge  varchar(128)  NOT NULL,
    scope           varchar(32)   NOT NULL,
    created_at      timestamptz   NOT NULL,
    expires_at      timestamptz   NOT NULL,
    redeemed_at     timestamptz,
    issued_token_id uuid
);
CREATE INDEX idx_oauth_code_expires_at ON oauth_authorization_code (expires_at);

CREATE TABLE oauth_issued_token (
    token_id  uuid        PRIMARY KEY REFERENCES personal_access_token (id) ON DELETE CASCADE,
    client_id varchar(40) NOT NULL REFERENCES oauth_client (client_id) ON DELETE CASCADE,
    issued_at timestamptz NOT NULL
);
CREATE INDEX idx_oauth_issued_token_client ON oauth_issued_token (client_id);

COMMENT ON COLUMN oauth_authorization_code.issued_token_id IS
    'No foreign key: the token row may be purged before the code row.';
