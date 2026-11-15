-- Workflow lifecycle: a draft/published status and a soft-delete timestamp.
--
-- Order: this migration runs before the deploy that reads the columns. Every row that exists
-- when it runs becomes published (the column is added with DEFAULT 'published', so the seeded
-- and already-shared workflows stay listed and startable); a workflow created afterwards
-- starts as a draft.

ALTER TABLE workflow ADD COLUMN status varchar(20) NOT NULL DEFAULT 'published';
ALTER TABLE workflow ADD CONSTRAINT ck_workflow_status CHECK (status IN ('draft', 'published'));
ALTER TABLE workflow ADD COLUMN deleted_at timestamptz;
ALTER TABLE workflow ALTER COLUMN status SET DEFAULT 'draft';
