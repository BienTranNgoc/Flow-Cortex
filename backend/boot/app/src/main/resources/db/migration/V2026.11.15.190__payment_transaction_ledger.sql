-- Money record of a payment, its status history, and the application state of accepted provider callbacks.
--
-- Exactly-once rule: a callback is applied by claiming its payment_webhook_event row with FOR UPDATE SKIP
-- LOCKED, locking the payment row (provider + order_ref) FOR UPDATE, moving the status with a conditional
-- UPDATE ... WHERE status = :from, appending one payment_transaction_event row, and marking the inbox row
-- (applied_at set, WHERE applied_at IS NULL) in the same transaction. Lock order is inbox row, then payment
-- row. Only an applied move writes history; a refused or skipped callback keeps its outcome on the inbox row.
--
-- Rollback (no payment can be opened before checkout ships, so no money row is lost): drop index
-- payment_webhook_event_unapplied_idx; drop the two payment_webhook_event_application constraints; drop the
-- columns applied_at, application_outcome, application_reason, transaction_id, apply_attempts and
-- next_attempt_at from payment_webhook_event; drop tables payment_transaction_event, then payment_transaction.
CREATE TABLE payment_transaction (
    id                      UUID PRIMARY KEY,
    user_id                 UUID        NOT NULL,
    kind                    TEXT        NOT NULL,
    item_ref                TEXT        NOT NULL,
    description             TEXT        NOT NULL,
    amount_vnd              BIGINT      NOT NULL,
    currency                TEXT        NOT NULL DEFAULT 'VND',
    provider                TEXT        NOT NULL,
    merchant_code           TEXT        NOT NULL,
    instrument              TEXT,
    order_ref               TEXT        NOT NULL,
    request_ref             UUID        NOT NULL,
    status                  TEXT        NOT NULL,
    provider_result_code    TEXT,
    provider_message        TEXT,
    provider_transaction_id TEXT,
    provider_pay_type       TEXT,
    provider_status_at      TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL,
    updated_at              TIMESTAMPTZ NOT NULL,
    succeeded_at            TIMESTAMPTZ,
    CONSTRAINT payment_transaction_kind_check CHECK (kind IN ('plan_purchase', 'topup')),
    CONSTRAINT payment_transaction_item_ref_check CHECK (item_ref ~ '^[a-z0-9][a-z0-9_.:-]{0,63}$'),
    CONSTRAINT payment_transaction_description_check CHECK (char_length(description) BETWEEN 1 AND 200),
    CONSTRAINT payment_transaction_amount_check CHECK (amount_vnd > 0),
    CONSTRAINT payment_transaction_currency_check CHECK (currency = 'VND'),
    CONSTRAINT payment_transaction_provider_check CHECK (provider ~ '^[a-z][a-z0-9_]{1,31}$'),
    CONSTRAINT payment_transaction_merchant_code_check CHECK (char_length(merchant_code) BETWEEN 1 AND 64),
    CONSTRAINT payment_transaction_instrument_check CHECK (instrument IS NULL OR instrument IN ('wallet', 'domestic_card')),
    CONSTRAINT payment_transaction_order_ref_check CHECK (order_ref ~ '^fc_[0-9a-f]{32}$'),
    CONSTRAINT payment_transaction_status_check CHECK (status IN
        ('pending', 'succeeded', 'failed', 'expired', 'partially_refunded', 'refunded', 'disputed')),
    CONSTRAINT payment_transaction_provider_facts_check CHECK (
        char_length(provider_result_code) <= 32
        AND char_length(provider_message) <= 512
        AND char_length(provider_transaction_id) <= 64
        AND char_length(provider_pay_type) <= 32),
    CONSTRAINT payment_transaction_succeeded_at_check CHECK (
        succeeded_at IS NULL OR status IN ('succeeded', 'partially_refunded', 'refunded', 'disputed')),
    CONSTRAINT payment_transaction_order_ref_uq UNIQUE (order_ref),
    CONSTRAINT payment_transaction_request_ref_uq UNIQUE (provider, request_ref)
);

CREATE INDEX idx_payment_transaction_user_created ON payment_transaction (user_id, created_at DESC, id DESC);

CREATE TABLE payment_transaction_event (
    id                   UUID PRIMARY KEY,
    transaction_id       UUID        NOT NULL REFERENCES payment_transaction (id),
    from_status          TEXT,
    to_status            TEXT        NOT NULL,
    reason               TEXT        NOT NULL,
    provider_result_code TEXT,
    evidence_ref         TEXT,
    provider_timestamp   TIMESTAMPTZ,
    occurred_at          TIMESTAMPTZ NOT NULL,
    CONSTRAINT payment_transaction_event_from_status_check CHECK (from_status IS NULL OR from_status IN
        ('pending', 'succeeded', 'failed', 'expired', 'partially_refunded', 'refunded', 'disputed')),
    CONSTRAINT payment_transaction_event_to_status_check CHECK (to_status IN
        ('pending', 'succeeded', 'failed', 'expired', 'partially_refunded', 'refunded', 'disputed')),
    CONSTRAINT payment_transaction_event_bounds_check CHECK (
        char_length(reason) <= 64
        AND char_length(provider_result_code) <= 32
        AND char_length(evidence_ref) <= 64),
    CONSTRAINT payment_transaction_event_opening_check CHECK ((from_status IS NULL) = (reason = 'opened'))
);

CREATE INDEX idx_payment_transaction_event_transaction ON payment_transaction_event (transaction_id, occurred_at, id);

ALTER TABLE payment_webhook_event
    ADD COLUMN applied_at          TIMESTAMPTZ,
    ADD COLUMN application_outcome TEXT,
    ADD COLUMN application_reason  TEXT,
    ADD COLUMN transaction_id      UUID,
    ADD COLUMN apply_attempts      INTEGER NOT NULL DEFAULT 0 CHECK (apply_attempts >= 0),
    ADD COLUMN next_attempt_at     TIMESTAMPTZ;

ALTER TABLE payment_webhook_event
    ADD CONSTRAINT payment_webhook_event_application_check CHECK (
        (applied_at IS NULL) = (application_outcome IS NULL)
        AND (application_outcome IS NULL OR application_outcome IN ('applied', 'skipped', 'refused'))
        AND char_length(application_reason) <= 64) NOT VALID,
    ADD CONSTRAINT payment_webhook_event_application_rejected_check CHECK (
        disposition = 'accepted'
        OR (applied_at IS NULL
            AND application_outcome IS NULL
            AND application_reason IS NULL
            AND transaction_id IS NULL
            AND next_attempt_at IS NULL
            AND apply_attempts = 0)) NOT VALID;

ALTER TABLE payment_webhook_event VALIDATE CONSTRAINT payment_webhook_event_application_check;
ALTER TABLE payment_webhook_event VALIDATE CONSTRAINT payment_webhook_event_application_rejected_check;

CREATE INDEX payment_webhook_event_unapplied_idx
    ON payment_webhook_event (received_at, id) WHERE disposition = 'accepted' AND applied_at IS NULL;
