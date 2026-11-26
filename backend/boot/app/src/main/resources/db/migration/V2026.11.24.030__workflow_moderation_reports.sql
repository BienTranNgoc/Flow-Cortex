-- Workflow moderation: one moderation state per workflow, member reports of a workflow and the
-- append-only history of every moderation action, plus the three staff permission keys.
--
-- Runs before the deploy that reads it. The column is additive with a default, so the previous image
-- keeps working and every existing workflow stays visible. The CHECK on the new column scans the
-- workflow rows once under the table lock; the table is small.
--
-- Rollback (schema only, the rows are lost): DROP TABLE workflow_moderation_action; DROP TABLE
-- workflow_report; ALTER TABLE workflow DROP COLUMN moderation_status; then delete the three
-- moderation.* rows from role_permission and permission.
--
-- reporter_id, reviewer_id and moderator_id carry no foreign key to app_user, like the other
-- cross-module tables, so a deleted account leaves its rows behind.

ALTER TABLE workflow
    ADD COLUMN moderation_status varchar(16) NOT NULL DEFAULT 'visible',
    ADD CONSTRAINT ck_workflow_moderation_status CHECK (moderation_status IN ('visible', 'hidden', 'removed'));

CREATE TABLE workflow_report (
    id            uuid          PRIMARY KEY,
    workflow_id   uuid          NOT NULL REFERENCES workflow (id) ON DELETE CASCADE,
    reporter_id   uuid          NOT NULL,
    reason        varchar(20)   NOT NULL CHECK (reason IN ('spam', 'harmful', 'scam', 'inappropriate', 'copyright', 'pii_data_leak', 'misleading', 'other')),
    details       varchar(1000),
    status        varchar(16)   NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'dismissed', 'resolved', 'hidden', 'removed')),
    resolution    varchar(16)   CHECK (resolution IN ('dismiss', 'resolve', 'hide', 'remove', 'restore')),
    reviewer_id   uuid,
    reviewed_at   timestamptz,
    internal_note varchar(1000),
    created_at    timestamptz   NOT NULL,
    CONSTRAINT uq_workflow_report_reporter UNIQUE (workflow_id, reporter_id),
    CONSTRAINT ck_workflow_report_review CHECK ((status = 'pending') = (reviewer_id IS NULL AND reviewed_at IS NULL AND resolution IS NULL))
);

CREATE INDEX idx_workflow_report_queue        ON workflow_report (created_at DESC, id DESC);
CREATE INDEX idx_workflow_report_status_queue ON workflow_report (status, created_at DESC, id DESC);
CREATE INDEX idx_workflow_report_reporter     ON workflow_report (reporter_id, created_at DESC);

-- report_id is SET NULL on delete so a report removed later never deletes the history it produced.
CREATE TABLE workflow_moderation_action (
    id                uuid          PRIMARY KEY,
    workflow_id       uuid          NOT NULL REFERENCES workflow (id) ON DELETE CASCADE,
    report_id         uuid          REFERENCES workflow_report (id) ON DELETE SET NULL,
    source            varchar(8)    NOT NULL CHECK (source IN ('report', 'direct')),
    action            varchar(16)   NOT NULL CHECK (action IN ('dismiss', 'resolve', 'hide', 'remove', 'restore')),
    moderator_id      uuid          NOT NULL,
    reason            varchar(1000),
    internal_note     varchar(1000),
    moderation_status varchar(16)   NOT NULL CHECK (moderation_status IN ('visible', 'hidden', 'removed')),
    created_at        timestamptz   NOT NULL,
    CONSTRAINT ck_wma_source CHECK (source = 'report' OR report_id IS NULL),
    CONSTRAINT ck_wma_direct_action CHECK (source = 'report' OR action IN ('hide', 'remove', 'restore')),
    CONSTRAINT ck_wma_reason CHECK (action NOT IN ('hide', 'remove', 'restore') OR (reason IS NOT NULL AND length(btrim(reason)) > 0))
);

CREATE INDEX idx_wma_workflow_history ON workflow_moderation_action (workflow_id, created_at DESC, id DESC);

-- The enum Permission appends these keys after every key already in the catalog, so sort_order
-- follows the highest value present when this migration runs.
INSERT INTO permission (key, module, feature, action, super_admin_only, sort_order)
SELECT new_key.key, 'moderation', new_key.feature, new_key.action, false,
       (SELECT coalesce(max(sort_order), 0) FROM permission) + new_key.place
FROM (VALUES ('moderation.report.view',    'report',  'view',    1),
             ('moderation.report.resolve', 'report',  'resolve', 2),
             ('moderation.content.hide',   'content', 'hide',    3)) AS new_key (key, feature, action, place)
ON CONFLICT (key) DO NOTHING;

INSERT INTO role_permission (role_code, permission_key) VALUES
    ('OPERATION_MANAGER', 'moderation.report.view'),
    ('OPERATION_MANAGER', 'moderation.report.resolve'),
    ('OPERATION_MANAGER', 'moderation.content.hide'),
    ('CUSTOMER_SUPPORT',  'moderation.report.view'),
    ('PLATFORM_OPERATOR', 'moderation.content.hide')
ON CONFLICT DO NOTHING;
