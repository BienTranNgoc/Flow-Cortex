-- request_key makes a run start idempotent per owner: a caller that retries a start with the same key gets the
-- run it already created. Runs started without a key (the run form, the Product Video form) leave it NULL, and a
-- partial unique index lets any number of those coexist.
ALTER TABLE workflow_run ADD COLUMN request_key text;

CREATE UNIQUE INDEX uq_workflow_run_owner_request_key
    ON workflow_run (owner_id, request_key)
    WHERE request_key IS NOT NULL;
