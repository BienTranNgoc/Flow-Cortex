-- Every send-code request, refusals included. phone_hash is the hex HMAC-SHA256 of the E.164
-- number under a server secret, so quotas compare numbers without storing or exposing them. outcome is 'sent', 'number_taken' or the
-- refusal reason; member quotas count 'sent' and 'number_taken', the global budget 'sent'.
-- A scheduled job deletes old rows. New empty table, so plain CREATE INDEX blocks nobody.

CREATE TABLE phone_send_attempt (
    id          uuid         PRIMARY KEY,
    user_id     uuid         NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    phone_hash  char(64)     NOT NULL,
    ip          varchar(64)  NOT NULL,
    outcome     varchar(32)  NOT NULL,
    created_at  timestamptz  NOT NULL
);

CREATE INDEX idx_phone_send_attempt_user_created_at ON phone_send_attempt (user_id, created_at);
CREATE INDEX idx_phone_send_attempt_phone_created_at ON phone_send_attempt (phone_hash, created_at);
CREATE INDEX idx_phone_send_attempt_sent_created_at ON phone_send_attempt (created_at) WHERE outcome = 'sent';
