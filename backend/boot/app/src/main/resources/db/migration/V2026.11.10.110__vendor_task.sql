-- vendor_task: the ledger of paid asynchronous vendor jobs (one row per vendor job), so a repeated
-- identical ask attaches to the job already paid for. Rows are swept after the configured retention.
-- No foreign keys: a run or step may be deleted before its rows expire.
-- Rollback: DROP TABLE vendor_task (loses only ledger rows).
CREATE TABLE vendor_task (
    id                   uuid         PRIMARY KEY,
    owner_id             uuid         NOT NULL,
    capability           varchar(50)  NOT NULL,
    request_key          char(64)     NOT NULL,
    fingerprint          char(64),
    vendor               varchar(32)  NOT NULL,
    model                varchar(200) NOT NULL,
    vendor_job_id        varchar(200) NOT NULL,
    handle               jsonb        NOT NULL DEFAULT '{}'::jsonb,
    status               varchar(16)  NOT NULL,
    vendor_created_at    timestamptz  NOT NULL,
    requested_by_step_id uuid,
    held_by_step_id      uuid,
    consumed_by_step_id  uuid,
    consumed_at          timestamptz,
    created_at           timestamptz  NOT NULL,
    updated_at           timestamptz  NOT NULL,
    CONSTRAINT ck_vendor_task_status CHECK (status IN ('running', 'succeeded', 'failed', 'expired', 'gone')),
    CONSTRAINT uq_vendor_task_vendor_job UNIQUE (vendor, vendor_job_id)
);

CREATE INDEX idx_vendor_task_candidates ON vendor_task (owner_id, capability, request_key, created_at)
    WHERE consumed_at IS NULL AND status IN ('running', 'succeeded');

CREATE INDEX idx_vendor_task_created_at ON vendor_task (created_at);
