-- Additive: creates workflow_schedule (one schedule per member and workflow) and
-- workflow_schedule_fire (the history of the runs a schedule started or tried to start).
--
-- Runs before the deploy that reads it; both tables are new, so older code never reads them.
-- Rollback: DROP TABLE workflow_schedule_fire, workflow_schedule (the fire table first, it references the other).
--
-- owner_id carries no foreign key, like workflow_run.owner_id; workflow_id references workflow without
-- cascade, like workflow_run.workflow_id, because workflows are soft-deleted.
CREATE TABLE workflow_schedule (
    id               uuid         PRIMARY KEY,
    owner_id         uuid         NOT NULL,
    workflow_id      uuid         NOT NULL REFERENCES workflow (id),
    frequency        varchar(8)   NOT NULL CHECK (frequency IN ('daily', 'weekly', 'monthly')),
    day_of_week      smallint     CHECK (day_of_week BETWEEN 1 AND 7),
    day_of_month     smallint     CHECK (day_of_month BETWEEN 1 AND 28),
    local_time       time         NOT NULL CHECK (EXTRACT(MINUTE FROM local_time) IN (0, 15, 30, 45)
                                                  AND EXTRACT(SECOND FROM local_time) = 0),
    time_zone        varchar(64)  NOT NULL,
    status           varchar(8)   NOT NULL,
    paused_at        timestamptz,
    next_run_at      timestamptz,
    input            text,
    acknowledge_cost boolean      NOT NULL,
    created_at       timestamptz  NOT NULL,
    updated_at       timestamptz  NOT NULL,
    CONSTRAINT uq_workflow_schedule_owner_workflow UNIQUE (owner_id, workflow_id),
    CONSTRAINT ck_workflow_schedule_status CHECK (
        (status = 'active' AND next_run_at IS NOT NULL AND paused_at IS NULL)
     OR (status = 'paused' AND next_run_at IS NULL AND paused_at IS NOT NULL)),
    CONSTRAINT ck_workflow_schedule_days CHECK (
        (frequency = 'daily'   AND day_of_week IS NULL     AND day_of_month IS NULL)
     OR (frequency = 'weekly'  AND day_of_week IS NOT NULL AND day_of_month IS NULL)
     OR (frequency = 'monthly' AND day_of_week IS NULL     AND day_of_month IS NOT NULL))
);

-- schedule_id is cleared, not cascaded, when a schedule is deleted: the history outlives the schedule.
-- error_key and error_args hold the refusal's message key and arguments; the text is resolved when read.
CREATE TABLE workflow_schedule_fire (
    id            uuid         PRIMARY KEY,
    owner_id      uuid         NOT NULL,
    workflow_id   uuid         NOT NULL REFERENCES workflow (id),
    schedule_id   uuid         REFERENCES workflow_schedule (id) ON DELETE SET NULL,
    trigger       varchar(10)  NOT NULL CHECK (trigger IN ('manual', 'scheduled')),
    scheduled_at  timestamptz  NOT NULL,
    status        varchar(10)  NOT NULL CHECK (status IN ('starting', 'started', 'failed')),
    request_key   text         NOT NULL,
    error_code    varchar(64),
    error_key     varchar(128),
    error_args    jsonb,
    created_at    timestamptz  NOT NULL
);

CREATE INDEX idx_workflow_schedule_fire_history
    ON workflow_schedule_fire (owner_id, workflow_id, created_at DESC, id DESC);
