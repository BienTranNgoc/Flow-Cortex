-- run_step grows a pickup-based lease and result metadata; the step row is the media job
-- (its id is the jobId), so no job table exists.
-- Additive only: every column is nullable or defaulted, so code that does not read them keeps
-- working. `started_at` stays the claim time; pickup is its own column.
--   lease_until      extended by each heartbeat; NULL until pickup
--   progress_percent last reported progress
--   attempt          API-owned fencing token every result CAS checks; stays 1 until an
--                    API-side re-dispatch exists. Broker redeliveries go to delivery_count.
--   error_code       queue_timeout | lease_expired | run_timeout | media_disabled |
--                    input_rejected | probe_failed | contract_violation
--   outputs          the result's objects: [{name, bucket, objectKey, mime, bytes, meta}]
--   picked_up_at     first pickup by an executor; NULL = still waiting in the queue
--   queue_deadline   set at claim; an unpicked step past it is a queue_timeout
--   run_deadline     set by each delivery's `started`; past it is a run_timeout
--   delivery_count   the broker's delivery counter, for display only
ALTER TABLE run_step
    ADD COLUMN lease_until      timestamptz,
    ADD COLUMN progress_percent smallint CHECK (progress_percent BETWEEN 0 AND 100),
    ADD COLUMN attempt          smallint NOT NULL DEFAULT 1,
    ADD COLUMN error_code       varchar(50),
    ADD COLUMN outputs          jsonb,
    ADD COLUMN picked_up_at     timestamptz,
    ADD COLUMN queue_deadline   timestamptz,
    ADD COLUMN run_deadline     timestamptz,
    ADD COLUMN delivery_count   smallint NOT NULL DEFAULT 0 CHECK (delivery_count >= 0);

-- A step left running by the previous engine has no deadline and would never be reaped; the
-- first reaper pass closes it as queue_timeout.
UPDATE run_step SET queue_deadline = now() WHERE status = 'running';

-- The reaper scans only running steps; the partial predicate keeps that to a handful of rows.
CREATE INDEX idx_run_step_running ON run_step (queue_deadline) WHERE status = 'running';
