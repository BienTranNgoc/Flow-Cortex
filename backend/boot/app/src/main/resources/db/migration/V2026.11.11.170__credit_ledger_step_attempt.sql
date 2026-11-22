-- A retried step is charged and refunded again on its own: the ledger allows one debit and one
-- refund per step attempt instead of per step. Existing rows are attempt 1.
ALTER TABLE credit_ledger ADD COLUMN attempt smallint NOT NULL DEFAULT 1 CHECK (attempt >= 1);

DROP INDEX uq_credit_ledger_step_kind;
CREATE UNIQUE INDEX uq_credit_ledger_step_attempt_kind
    ON credit_ledger (run_step_id, attempt, kind) WHERE run_step_id IS NOT NULL;
