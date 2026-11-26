-- A member's change-of-mind refund request on one of their payments. The member routes write 'pending' and
-- 'cancelled' rows; the other statuses are written by whoever decides a request.
--
-- At most one 'pending' request exists per payment (uq_refund_request_pending), which is the backstop of the
-- per-member lock the request path takes. decided_at is null exactly when the request is pending.
-- user_id has no foreign key, like payment_transaction.user_id: a request keeps the retention of its payment.
--
-- Rollback (the table holds no rows before checkout ships): drop table refund_request; drop function
-- refund_request_next_code(); drop sequence refund_request_code_seq.
CREATE SEQUENCE refund_request_code_seq;

-- 'HT-' plus the sequence value padded to six digits; a longer value is kept whole.
CREATE FUNCTION refund_request_next_code() RETURNS VARCHAR(16)
    LANGUAGE sql
AS
$$
SELECT 'HT-' || lpad(n::text, greatest(6, length(n::text)), '0')
FROM (SELECT nextval('refund_request_code_seq') AS n) AS next_value
$$;

CREATE TABLE refund_request (
    id             UUID PRIMARY KEY,
    code           VARCHAR(16) NOT NULL DEFAULT refund_request_next_code(),
    user_id        UUID        NOT NULL,
    transaction_id UUID        NOT NULL REFERENCES payment_transaction (id),
    reason         TEXT        NOT NULL,
    status         TEXT        NOT NULL,
    decided_at     TIMESTAMPTZ,
    decided_by     UUID,
    decision_note  TEXT,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT refund_request_code_uq UNIQUE (code),
    CONSTRAINT refund_request_reason_check CHECK (char_length(reason) BETWEEN 1 AND 2000),
    CONSTRAINT refund_request_status_check CHECK (status IN
        ('pending', 'auto_approved', 'approved', 'rejected', 'cancelled')),
    CONSTRAINT refund_request_decision_note_check CHECK (char_length(decision_note) <= 2000),
    CONSTRAINT refund_request_decided_at_check CHECK ((status = 'pending') = (decided_at IS NULL))
);

CREATE UNIQUE INDEX uq_refund_request_pending ON refund_request (transaction_id) WHERE status = 'pending';

CREATE INDEX idx_refund_request_transaction ON refund_request (transaction_id, created_at DESC, id DESC);

CREATE INDEX idx_refund_request_user_created ON refund_request (user_id, created_at);
