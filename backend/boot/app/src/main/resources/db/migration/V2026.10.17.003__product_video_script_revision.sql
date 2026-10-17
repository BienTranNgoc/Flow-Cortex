CREATE TABLE script_revision (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES workflow_run(id) ON DELETE CASCADE,
    review_task_id UUID NOT NULL REFERENCES review_task(id) ON DELETE CASCADE,
    revision_number INTEGER NOT NULL,
    content TEXT NOT NULL,
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_script_revision_task_number UNIQUE (review_task_id, revision_number),
    CONSTRAINT ck_script_revision_number CHECK (revision_number > 0),
    CONSTRAINT ck_script_revision_content CHECK (length(btrim(content)) > 0)
);

ALTER TABLE review_task
    ADD COLUMN current_revision_id UUID REFERENCES script_revision(id),
    ADD COLUMN approved_revision_id UUID REFERENCES script_revision(id);

CREATE INDEX idx_script_revision_run ON script_revision(run_id);
