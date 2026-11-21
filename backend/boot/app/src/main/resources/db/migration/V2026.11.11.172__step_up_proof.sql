-- Single-use proofs that a signed-in user just re-entered the account password, one per purpose.
-- Only the SHA-256 hex of the proof is stored. Rollback: DROP TABLE step_up_proof;
CREATE TABLE step_up_proof (
    id          uuid         PRIMARY KEY,
    user_id     uuid         NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    purpose     varchar(40)  NOT NULL,
    token_hash  varchar(64)  NOT NULL UNIQUE,
    created_at  timestamptz  NOT NULL,
    expires_at  timestamptz  NOT NULL,
    consumed_at timestamptz  NULL
);

CREATE INDEX idx_step_up_proof_expires_at ON step_up_proof (expires_at);
