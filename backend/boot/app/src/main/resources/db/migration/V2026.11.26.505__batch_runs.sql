-- Additive: creates batch_run (one member-started batch of runs of one workflow) and batch_run_row
-- (one input line of a batch and the run it started). New tables only; runs before the deploy that reads it.
--
-- batch_run.owner_id carries no foreign key, like workflow_run.owner_id: the batch survives the way a run does.
-- batch_run_row.run_id is ON DELETE SET NULL so a run removed from the database leaves its row with the
-- row's last status (a soft-deleted run keeps its id).
-- lease_token / lease_until name the one holder allowed to dispatch the batch; claimed_at dates a row's claim.
-- Rollback: DROP TABLE batch_run_row, batch_run.
CREATE TABLE batch_run (
    id               uuid         PRIMARY KEY,
    owner_id         uuid         NOT NULL,
    workflow_id      uuid         NOT NULL REFERENCES workflow (id),
    parent_batch_id  uuid         REFERENCES batch_run (id) ON DELETE SET NULL,
    name             varchar(200),
    concurrency      smallint     NOT NULL CHECK (concurrency BETWEEN 1 AND 10),
    acknowledge_cost boolean      NOT NULL,
    status           varchar(24)  NOT NULL,
    pause_reason     varchar(32),
    cancel_reason    varchar(32),
    total_rows       integer      NOT NULL,
    created_at       timestamptz  NOT NULL,
    finished_at      timestamptz,
    lease_token      uuid,
    lease_until      timestamptz,
    dispatched_at    timestamptz,
    CONSTRAINT ck_batch_run_status CHECK (status IN ('running', 'paused', 'completed', 'completed_with_errors', 'cancelled'))
);
CREATE INDEX idx_batch_run_owner_created ON batch_run (owner_id, created_at DESC, id DESC);
CREATE INDEX idx_batch_run_open ON batch_run (dispatched_at NULLS FIRST) WHERE finished_at IS NULL;
CREATE UNIQUE INDEX uq_batch_run_parent ON batch_run (parent_batch_id) WHERE parent_batch_id IS NOT NULL;

CREATE TABLE batch_run_row (
    id               uuid         PRIMARY KEY,
    batch_id         uuid         NOT NULL REFERENCES batch_run (id) ON DELETE CASCADE,
    row_index        integer      NOT NULL,
    source_row_index integer,
    input            text         NOT NULL,
    status           varchar(16)  NOT NULL,
    run_id           uuid         REFERENCES workflow_run (id) ON DELETE SET NULL,
    start_failures   smallint     NOT NULL DEFAULT 0,
    stop_requested   boolean      NOT NULL DEFAULT false,
    error_code       varchar(64),
    error_message    varchar(300),
    claimed_at       timestamptz,
    started_at       timestamptz,
    finished_at      timestamptz,
    CONSTRAINT uq_batch_run_row_index UNIQUE (batch_id, row_index),
    CONSTRAINT ck_batch_run_row_status CHECK (status IN ('pending', 'starting', 'running', 'completed', 'failed', 'cancelled'))
);
CREATE INDEX idx_batch_run_row_open ON batch_run_row (batch_id, row_index) WHERE status IN ('pending', 'starting', 'running');
CREATE INDEX idx_batch_run_row_status ON batch_run_row (batch_id, status, row_index);
