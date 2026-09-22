-- =============================================================================
-- Flow-Cortex - workflow module (V2026.09.22.002)
--
-- Four greenfield tables (design.md D1, task 5.1): workflow / workflow_step
-- (the definition) and workflow_run / run_step (one execution of a
-- definition). Columns and types match the Hibernate-mapped entities exactly
-- so the app can boot with `spring.jpa.hibernate.ddl-auto: validate`
-- (design.md D7/R-004) — same convention V2026.09.22.001__user.sql follows.
--
-- Visibility vs. ownership (design.md D1, R-049): a workflow DEFINITION can be
-- shared (`is_shared = true`, listed to every authenticated user); a
-- workflow RUN never is — `workflow_run.owner_id` always scopes reads to the
-- user who started it (R-047), regardless of whether the workflow itself is
-- shared.
-- =============================================================================

CREATE TABLE workflow (
    id         uuid         PRIMARY KEY,
    owner_id   uuid         NOT NULL,
    name       varchar(255) NOT NULL,
    is_shared  boolean      NOT NULL DEFAULT false,
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL
);

CREATE INDEX idx_workflow_owner_id ON workflow (owner_id);

-- workflow_step: the ordered definition of a workflow (design.md D1, R-040).
-- `input_ref` carries the raw `{step:K.field}` reference text (R-042); NULL
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

-- workflow_run: one execution of a workflow (design.md D5). `status` is the
-- 4->3-value ladder's RUN-level derivation (`running`|`completed`|`error`,
-- R-044) persisted as a plain lowercase string (RunStatusConverter maps the
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

-- run_step: one step's execution within a run (design.md D5, R-043/R-045).
-- `status` is the 4-value ladder (`pending`|`running`|`completed`|`error`)
-- persisted lowercase (RunStepStatusConverter). The single-active-dispatch
-- atomic claim (R-045) is a raw `UPDATE run_step SET status='running' ...
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
