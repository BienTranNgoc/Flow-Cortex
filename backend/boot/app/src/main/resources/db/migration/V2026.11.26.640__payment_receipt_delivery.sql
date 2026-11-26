-- The queue of receipt mails: one row per collected payment, written in the transaction that moves the payment
-- to succeeded (when the payment setting payment.receipt.email_enabled is on at that moment). The primary key is
-- the once-per-payment rule. A pass claims a row with a conditional update (claimed_at, attempts) and every later
-- write of that pass is conditional on its own claim time, so two passes never both record an outcome.
-- No row exists for payments collected before this migration: no payment can be collected before checkout ships.
--
-- Rollback: DROP TABLE payment_receipt_delivery (nothing else references it; the delivery history is lost and
-- payments are untouched).
CREATE TABLE payment_receipt_delivery (
    transaction_id  UUID        PRIMARY KEY REFERENCES payment_transaction (id) ON DELETE CASCADE,
    user_id         UUID        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    claimed_at      TIMESTAMPTZ,
    attempts        INTEGER     NOT NULL DEFAULT 0,
    outcome         TEXT,
    sent_at         TIMESTAMPTZ,
    CONSTRAINT payment_receipt_delivery_attempts_check CHECK (attempts >= 0),
    CONSTRAINT payment_receipt_delivery_outcome_check CHECK (outcome IN ('sent', 'no_address', 'failed')),
    CONSTRAINT payment_receipt_delivery_sent_check CHECK ((sent_at IS NOT NULL) = (outcome IS NOT DISTINCT FROM 'sent'))
);

CREATE INDEX idx_payment_receipt_delivery_due ON payment_receipt_delivery (next_attempt_at) WHERE outcome IS NULL;
