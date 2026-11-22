-- Writes made with a personal access token: one audit row per call (no query, no body) and the stored
-- answers of keyed retries. user_id carries no foreign key: rows outlive tokens and accounts until retention.
-- Rollback: DROP TABLE api_token_idempotency, api_token_call;

CREATE TABLE api_token_call (
    id          uuid         PRIMARY KEY,
    user_id     uuid         NOT NULL,
    token_id    uuid         NOT NULL,
    method      varchar(7)   NOT NULL,
    route       varchar(200) NOT NULL,
    outcome     varchar(10)  NOT NULL
        CONSTRAINT ck_api_token_call_outcome CHECK (outcome IN ('ok', 'error', 'blocked', 'replayed')),
    http_status smallint     NOT NULL,
    error_code  varchar(120),
    duration_ms integer      NOT NULL,
    occurred_at timestamptz  NOT NULL
);

CREATE INDEX idx_api_token_call_user_time ON api_token_call (user_id, occurred_at);
CREATE INDEX idx_api_token_call_occurred_at ON api_token_call (occurred_at);

CREATE TABLE api_token_idempotency (
    user_id       uuid         NOT NULL,
    idem_key      varchar(80)  NOT NULL,
    fingerprint   varchar(64)  NOT NULL,
    state         varchar(10)  NOT NULL
        CONSTRAINT ck_api_token_idempotency_state CHECK (state IN ('running', 'done')),
    reserved_at   timestamptz  NOT NULL,
    http_status   smallint,
    content_type  varchar(255),
    response_body bytea,
    expires_at    timestamptz  NOT NULL,
    PRIMARY KEY (user_id, idem_key)
);

CREATE INDEX idx_api_token_idempotency_expires_at ON api_token_idempotency (expires_at);
