-- Reconciliation of payments with their provider: the schedule of asking about a payment still pending, the
-- record of every disagreement found, and the time a payment was last asked about.
--
-- One item, one transaction: an answer is applied (payment row locked FOR UPDATE, conditional status move,
-- history entry, status event), its drift record is inserted, the schedule row is disposed with
-- WHERE resolved_at IS NULL AND escalated_at IS NULL, and payment_transaction.reconciled_at is set, all in one
-- transaction. Lock order is payment row, then schedule row; the callback applier never touches the schedule.
--
-- Rollback (no payment can be opened before checkout ships, so no money row is lost): drop table
-- payment_drift_record, then payment_reconciliation_schedule; drop indexes
-- idx_payment_transaction_pending_created and idx_payment_transaction_reconciled; drop column reconciled_at
-- from payment_transaction. Once real money has been taken, export payment_drift_record first: it is the
-- only account of a disagreement.
CREATE TABLE payment_reconciliation_schedule (
    id                UUID PRIMARY KEY,
    transaction_id    UUID        NOT NULL REFERENCES payment_transaction (id),
    initiated_at      TIMESTAMPTZ NOT NULL,
    attempts          INTEGER     NOT NULL DEFAULT 0,
    last_swept_at     TIMESTAMPTZ,
    next_sweep_at     TIMESTAMPTZ,
    resolved_at       TIMESTAMPTZ,
    escalated_at      TIMESTAMPTZ,
    last_outcome      TEXT,
    escalation_reason TEXT,
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT payment_reconciliation_schedule_transaction_uq UNIQUE (transaction_id),
    CONSTRAINT payment_reconciliation_schedule_attempts_check CHECK (attempts >= 0),
    CONSTRAINT payment_reconciliation_schedule_outcome_check CHECK (last_outcome IS NULL OR last_outcome IN
        ('resolved', 'settled_elsewhere', 'still_processing', 'unchanged', 'superseded', 'discrepancy',
         'query_failed', 'circuit_open', 'provider_unavailable', 'escalated')),
    CONSTRAINT payment_reconciliation_schedule_reason_check CHECK (escalation_reason IS NULL
        OR escalation_reason IN ('ceiling', 'discrepancy')),
    CONSTRAINT payment_reconciliation_schedule_closed_check CHECK (resolved_at IS NULL OR escalated_at IS NULL),
    CONSTRAINT payment_reconciliation_schedule_next_check CHECK (
        (next_sweep_at IS NULL) = (resolved_at IS NOT NULL OR escalated_at IS NOT NULL)),
    CONSTRAINT payment_reconciliation_schedule_escalation_check CHECK (
        (escalated_at IS NULL) = (escalation_reason IS NULL))
);

CREATE INDEX idx_payment_reconciliation_schedule_due
    ON payment_reconciliation_schedule (next_sweep_at) WHERE resolved_at IS NULL AND escalated_at IS NULL;

CREATE INDEX idx_payment_reconciliation_schedule_escalated
    ON payment_reconciliation_schedule (escalated_at) WHERE escalated_at IS NOT NULL;

CREATE TABLE payment_drift_record (
    id                   UUID PRIMARY KEY,
    transaction_id       UUID        NOT NULL REFERENCES payment_transaction (id),
    drift_class          TEXT        NOT NULL,
    pass                 TEXT        NOT NULL,
    provider             TEXT        NOT NULL,
    our_status           TEXT,
    provider_status      TEXT        NOT NULL,
    provider_result_code TEXT,
    our_amount_vnd       BIGINT      NOT NULL,
    provider_amount_vnd  BIGINT,
    evidence_ref         TEXT        NOT NULL,
    resolution           TEXT        NOT NULL,
    needs_person         BOOLEAN     NOT NULL,
    detected_at          TIMESTAMPTZ NOT NULL,
    CONSTRAINT payment_drift_record_class_check CHECK (drift_class IN
        ('missed_confirmation', 'false_positive', 'amount_mismatch', 'never_arrived')),
    CONSTRAINT payment_drift_record_pass_check CHECK (pass IN ('ageing', 'daily')),
    CONSTRAINT payment_drift_record_resolution_check CHECK (resolution IN ('corrected', 'superseded', 'refused')),
    CONSTRAINT payment_drift_record_our_status_check CHECK (our_status IS NULL OR our_status IN
        ('pending', 'succeeded', 'failed', 'expired', 'partially_refunded', 'refunded', 'disputed')),
    CONSTRAINT payment_drift_record_bounds_check CHECK (
        char_length(provider_status) <= 48
        AND char_length(provider_result_code) <= 32
        AND char_length(evidence_ref) <= 64),
    CONSTRAINT payment_drift_record_evidence_uq UNIQUE (transaction_id, evidence_ref)
);

CREATE INDEX idx_payment_drift_record_class_detected ON payment_drift_record (drift_class, detected_at);

CREATE INDEX idx_payment_drift_record_needs_person ON payment_drift_record (detected_at) WHERE needs_person;

ALTER TABLE payment_transaction ADD COLUMN reconciled_at TIMESTAMPTZ;

CREATE INDEX idx_payment_transaction_pending_created ON payment_transaction (created_at) WHERE status = 'pending';

CREATE INDEX idx_payment_transaction_reconciled ON payment_transaction (reconciled_at NULLS FIRST, created_at);
