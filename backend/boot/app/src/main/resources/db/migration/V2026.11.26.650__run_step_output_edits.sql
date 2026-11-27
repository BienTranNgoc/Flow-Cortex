-- Additive: a member's edit of a finished step's text, kept beside the untouched original, and its history.
--
-- edited_output is the whole effective output (the original with the edited strings applied); edited_at and
-- edit_diff_pct are set and cleared with it. edit_seq is the revision counter a client sends back: it rises by
-- one on every edit, undo and discard that changes the step (the wire revision is edit_seq + 1).
-- run_step_output_version.content holds the edited field values as one JSON object (pointer -> text);
-- (run_step_id, version) is unique so two concurrent writers cannot store the same version.
--
-- The previous image ignores the new columns and table (its inserts leave the columns null and edit_seq at 0).
-- This script is metadata only: both checks are added NOT VALID so no row is scanned while the table lock is held;
-- V2026.11.26.651 validates them while writers keep running.
-- Rollback: DROP TABLE run_step_output_version; ALTER TABLE run_step DROP CONSTRAINT run_step_edit_all_or_none,
-- DROP CONSTRAINT run_step_edit_diff_range, DROP COLUMN edited_output, DROP COLUMN edited_at,
-- DROP COLUMN edit_diff_pct, DROP COLUMN edit_seq.

SET LOCAL lock_timeout = '10s';

ALTER TABLE run_step
    ADD COLUMN edited_output  text,
    ADD COLUMN edited_at      timestamptz,
    ADD COLUMN edit_diff_pct  numeric(5, 2),
    ADD COLUMN edit_seq       integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT run_step_edit_all_or_none CHECK (
        (edited_output IS NULL) = (edited_at IS NULL) AND (edited_output IS NULL) = (edit_diff_pct IS NULL)) NOT VALID,
    ADD CONSTRAINT run_step_edit_diff_range CHECK (edit_diff_pct BETWEEN 0 AND 100) NOT VALID;

CREATE TABLE run_step_output_version (
    id          uuid         PRIMARY KEY,
    run_step_id uuid         NOT NULL REFERENCES run_step (id) ON DELETE CASCADE,
    version     integer      NOT NULL CHECK (version > 0),
    content     text         NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_run_step_output_version UNIQUE (run_step_id, version)
);
