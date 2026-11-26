-- Plan entitlement: the period a plan purchase bought, stored on the payment when its money settles, the plan a
-- member holds (one row per member, none means Free) and the history of every decision about it.
--
-- The three CHECKs on payment_transaction are added NOT VALID: ADD COLUMN holds ACCESS EXCLUSIVE until this
-- file commits, so the scan that validates them runs in V2026.11.24.202, under a lock that does not block the
-- ledger's writes. Until then they bind every new write.
--
-- Rollback (revert the code too). Run in one transaction, and include the history delete: without it
-- Flyway still counts these versions as applied and a later roll-forward skips them.
--   BEGIN;
--   DROP TABLE member_subscription_event, member_subscription;
--   ALTER TABLE payment_transaction
--       DROP CONSTRAINT payment_transaction_period_pair_check,
--       DROP CONSTRAINT payment_transaction_period_order_check,
--       DROP CONSTRAINT payment_transaction_period_kind_check,
--       DROP COLUMN period_starts_at,
--       DROP COLUMN period_renews_at;
--   DELETE FROM flyway_schema_history WHERE version IN ('2026.11.24.201', '2026.11.24.202');
--   COMMIT;
-- This holds only until a later migration references these objects; after that, roll back with a forward
-- migration.

ALTER TABLE payment_transaction
    ADD COLUMN period_starts_at TIMESTAMPTZ,
    ADD COLUMN period_renews_at TIMESTAMPTZ,
    ADD CONSTRAINT payment_transaction_period_pair_check CHECK (
        (period_starts_at IS NULL) = (period_renews_at IS NULL)) NOT VALID,
    ADD CONSTRAINT payment_transaction_period_order_check CHECK (
        period_renews_at > period_starts_at) NOT VALID,
    ADD CONSTRAINT payment_transaction_period_kind_check CHECK (
        period_starts_at IS NULL OR (kind = 'plan_purchase' AND succeeded_at IS NOT NULL)) NOT VALID;

-- status and billing_mode list the values the later lifecycle and mandate changes write, so they add no
-- CHECK rewrite on a table that holds paid rows; this change writes only 'active' and 'manual'.
CREATE TABLE member_subscription (
    user_id                      UUID        PRIMARY KEY,
    tier_id                      TEXT        NOT NULL REFERENCES pricing_tier (id),
    billing_cycle                TEXT        NOT NULL,
    status                       TEXT        NOT NULL,
    billing_mode                 TEXT        NOT NULL DEFAULT 'manual',
    started_at                   TIMESTAMPTZ NOT NULL,
    renews_at                    TIMESTAMPTZ NOT NULL,
    granted_from_transaction_id  UUID        NOT NULL REFERENCES payment_transaction (id),
    pending_tier_id              TEXT        REFERENCES pricing_tier (id),
    pending_billing_cycle        TEXT,
    pending_transaction_id       UUID        REFERENCES payment_transaction (id),
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT member_subscription_cycle_check CHECK (billing_cycle IN ('monthly', 'yearly')),
    CONSTRAINT member_subscription_status_check CHECK (status IN ('active', 'grace', 'cancelled', 'expired')),
    CONSTRAINT member_subscription_mode_check CHECK (billing_mode IN ('manual', 'auto_debit')),
    CONSTRAINT member_subscription_period_check CHECK (renews_at > started_at),
    CONSTRAINT member_subscription_pending_cycle_check CHECK (
        pending_billing_cycle IS NULL OR pending_billing_cycle IN ('monthly', 'yearly')),
    CONSTRAINT member_subscription_pending_all_or_none_check CHECK (
        (pending_tier_id IS NULL) = (pending_billing_cycle IS NULL)
        AND (pending_tier_id IS NULL) = (pending_transaction_id IS NULL)),
    CONSTRAINT member_subscription_pending_distinct_check CHECK (
        pending_transaction_id IS NULL OR pending_transaction_id <> granted_from_transaction_id)
);

-- The activation job finds holdings that record a next-cycle plan.
CREATE INDEX idx_member_subscription_pending ON member_subscription (pending_transaction_id)
    WHERE pending_transaction_id IS NOT NULL;

-- Append-only: the application never updates or deletes a row. from_* and to_* are null where there is none or
-- nothing moved; a refusal moves nothing.
CREATE TABLE member_subscription_event (
    id             UUID        PRIMARY KEY,
    user_id        UUID        NOT NULL,
    transaction_id UUID        NOT NULL REFERENCES payment_transaction (id),
    from_tier_id   TEXT,
    to_tier_id     TEXT,
    from_status    TEXT,
    to_status      TEXT,
    reason         TEXT        NOT NULL,
    occurred_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT member_subscription_event_reason_check CHECK (char_length(reason) BETWEEN 1 AND 64)
);

CREATE INDEX idx_member_subscription_event_user ON member_subscription_event (user_id, occurred_at, id);
