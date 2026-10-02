-- review_task: human-in-the-loop approval checkpoint for workflow executions.
CREATE TABLE review_task (
    id                uuid         PRIMARY KEY,
    run_id            uuid         NOT NULL REFERENCES workflow_run (id) ON DELETE CASCADE,
    run_step_id       uuid         NOT NULL REFERENCES run_step (id) ON DELETE CASCADE,
    review_type       varchar(50)  NOT NULL,
    status            varchar(20)  NOT NULL DEFAULT 'pending',
    original_payload  text         NOT NULL,
    reviewed_payload  text,
    reviewed_by       uuid,
    reviewed_at       timestamptz,
    created_at        timestamptz  NOT NULL DEFAULT clock_timestamp(),
    version           bigint       NOT NULL DEFAULT 0,
    CONSTRAINT uq_review_task_run_step_id UNIQUE (run_step_id),
    CONSTRAINT chk_review_task_status CHECK (status IN ('pending', 'approved', 'rejected'))
);

CREATE INDEX idx_review_task_run_id ON review_task (run_id);
CREATE INDEX idx_review_task_status ON review_task (status);
