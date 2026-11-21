-- Durable schedule of the cluster job runner: one row per job, holding when it runs next and which
-- instance holds it. Rollback: DROP TABLE scheduled_job (no other table references it).
--
-- lease_token fences a run: only the instance whose token is on the row may record a finish. Rows of jobs
-- no running instance registers are left as they are.

CREATE TABLE scheduled_job (
    name             text        PRIMARY KEY CHECK (name ~ '^[a-z0-9.-]{3,64}$'),
    interval_ms      bigint      NOT NULL CHECK (interval_ms > 0),
    next_run_at      timestamptz NOT NULL,
    lease_token      uuid,
    lease_owner      text,
    lease_until      timestamptz,
    last_started_at  timestamptz,
    last_finished_at timestamptz,
    last_outcome     text        CHECK (last_outcome IN ('succeeded', 'failed')),
    last_error       text,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL
);
