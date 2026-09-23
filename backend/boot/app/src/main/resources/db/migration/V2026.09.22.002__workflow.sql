-- workflow / workflow_step (the definition) and workflow_run / run_step (one
-- execution). Columns match the Hibernate entities so `ddl-auto: validate` passes.
--
-- A workflow DEFINITION may be shared (`is_shared = true`, listed to every
-- authenticated user); a RUN never is — `workflow_run.owner_id` always scopes
-- reads to the user who started it.

CREATE TABLE workflow (
    id         uuid         PRIMARY KEY,
    owner_id   uuid         NOT NULL,
    name       varchar(255) NOT NULL,
    is_shared  boolean      NOT NULL DEFAULT false,
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL
);

CREATE INDEX idx_workflow_owner_id ON workflow (owner_id);

-- workflow_step: the ordered definition of a workflow.
-- `input_ref` carries the raw `{step:K.field}` reference text; NULL
-- means "default to the previous step's output" (or, for step 1, the run's
-- own input).
CREATE TABLE workflow_step (
    id         uuid         PRIMARY KEY,
    workflow_id uuid        NOT NULL REFERENCES workflow (id),
    step_order smallint     NOT NULL,
    name       varchar(255),
    type       varchar(50)  NOT NULL,
    config     jsonb,
    input_ref  varchar(255),
    CONSTRAINT uq_workflow_step_order UNIQUE (workflow_id, step_order)
);

-- workflow_run: one execution of a workflow. `status` is the run-level
-- derivation of the step ladder (`running`|`completed`|`error`)
-- persisted as a plain lowercase string (RunStatusConverter maps the
-- Java enum to/from this exact wire form).
CREATE TABLE workflow_run (
    id            uuid        PRIMARY KEY,
    workflow_id   uuid        NOT NULL REFERENCES workflow (id),
    owner_id      uuid        NOT NULL,
    status        varchar(20) NOT NULL DEFAULT 'running',
    input         text,
    error_message text,
    started_at    timestamptz NOT NULL,
    finished_at   timestamptz
);

CREATE INDEX idx_workflow_run_owner_id ON workflow_run (owner_id);
CREATE INDEX idx_workflow_run_workflow_id ON workflow_run (workflow_id);

-- run_step: one step's execution within a run.
-- `status` is the 4-value ladder (`pending`|`running`|`completed`|`error`)
-- persisted lowercase (RunStepStatusConverter). The single-active-dispatch
-- atomic claim is a raw `UPDATE run_step SET status='running' ...
-- WHERE id = ? AND status = 'pending'` acting on this exact column.
CREATE TABLE run_step (
    id            uuid        PRIMARY KEY,
    run_id        uuid        NOT NULL REFERENCES workflow_run (id),
    step_order    smallint    NOT NULL,
    type          varchar(50) NOT NULL,
    status        varchar(20) NOT NULL DEFAULT 'pending',
    input         text,
    output        text,
    error_message text,
    started_at    timestamptz,
    finished_at   timestamptz,
    CONSTRAINT uq_run_step_order UNIQUE (run_id, step_order)
);

CREATE INDEX idx_run_step_run_id ON run_step (run_id);
