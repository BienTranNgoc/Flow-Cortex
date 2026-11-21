-- Inbox of payment-provider callbacks. An accepted row is the durable hand-off to the ledger: at most one
-- per provider and request id. A rejected row is evidence only and never blocks a later acceptance.
-- raw_payload is bytea because the received bytes may hold NUL or invalid UTF-8, which text and jsonb refuse.
CREATE TABLE payment_webhook_event (
    id                  UUID PRIMARY KEY,
    provider            TEXT        NOT NULL,
    provider_request_id TEXT        NOT NULL,
    disposition         TEXT        NOT NULL,
    reason              TEXT,
    event               JSONB,
    raw_payload         BYTEA       NOT NULL,
    source_address      TEXT,
    received_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT payment_webhook_event_disposition_check CHECK (disposition IN ('accepted', 'rejected'))
);

CREATE UNIQUE INDEX payment_webhook_event_accepted_uq
    ON payment_webhook_event (provider, provider_request_id) WHERE disposition = 'accepted';

CREATE INDEX payment_webhook_event_disposition_received_idx
    ON payment_webhook_event (disposition, received_at);
