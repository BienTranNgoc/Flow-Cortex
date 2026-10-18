-- Credits: one wallet per user and an append-only ledger.
--
-- No FK to app_user, workflow_run or run_step: those belong to other modules.
-- The balance CHECK plus a conditional debit (balance >= :cost) is what stops an overdraft when
-- two debits race. The partial unique indexes make every write idempotent: one debit and one
-- refund per run step, one signup grant per user.
--
-- Every user that exists now gets the signup grant of 1000 here; users created later get it from
-- the user-created event.

CREATE TABLE credit_wallet (
    user_id  uuid    PRIMARY KEY,
    balance  bigint  NOT NULL CHECK (balance >= 0)
);

CREATE TABLE credit_ledger (
    id           uuid         PRIMARY KEY,
    user_id      uuid         NOT NULL,
    run_id       uuid         NULL,
    run_step_id  uuid         NULL,
    kind         varchar(10)  NOT NULL CHECK (kind IN ('grant', 'debit', 'refund')),
    amount       bigint       NOT NULL CHECK (amount > 0),
    created_at   timestamptz  NOT NULL
);

CREATE UNIQUE INDEX uq_credit_ledger_step_kind
    ON credit_ledger (run_step_id, kind) WHERE run_step_id IS NOT NULL;
CREATE UNIQUE INDEX uq_credit_ledger_user_grant
    ON credit_ledger (user_id) WHERE kind = 'grant';
CREATE INDEX idx_credit_ledger_run_id ON credit_ledger (run_id) WHERE run_id IS NOT NULL;
CREATE INDEX idx_credit_ledger_user_created_at ON credit_ledger (user_id, created_at DESC);

INSERT INTO credit_wallet (user_id, balance)
SELECT id, 1000 FROM app_user;

INSERT INTO credit_ledger (id, user_id, kind, amount, created_at)
SELECT gen_random_uuid(), id, 'grant', 1000, now() FROM app_user;
