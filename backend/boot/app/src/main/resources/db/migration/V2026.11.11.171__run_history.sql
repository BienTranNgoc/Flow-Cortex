-- Run history: a member title, a favourite flag, a soft-delete stamp and the trace id of the request
-- that started the run, plus the stored waveform of each sound a step produced.
-- Order: this runs before the deploy that reads the columns. Every column is nullable or has a
-- default, so existing rows read as untitled, not favourite, not deleted and without a trace id.
ALTER TABLE workflow_run ADD COLUMN title varchar(120);
ALTER TABLE workflow_run ADD COLUMN favorite boolean NOT NULL DEFAULT false;
ALTER TABLE workflow_run ADD COLUMN deleted_at timestamptz;
ALTER TABLE workflow_run ADD COLUMN trace_id varchar(32);

-- Serves every member list, tray and export read: the owner's live runs newest first.
CREATE INDEX idx_workflow_run_owner_history ON workflow_run (owner_id, started_at DESC, id DESC)
    WHERE deleted_at IS NULL;

-- One waveform per step, attempt and output name; peaks = NULL records a sound that cannot be decoded.
CREATE TABLE run_step_audio_peaks (
    run_step_id uuid         NOT NULL REFERENCES run_step (id) ON DELETE CASCADE,
    attempt     smallint     NOT NULL,
    output_name varchar(255) NOT NULL,
    peaks       jsonb,
    computed_at timestamptz  NOT NULL,
    PRIMARY KEY (run_step_id, attempt, output_name)
);
