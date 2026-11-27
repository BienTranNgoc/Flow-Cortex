-- Additive: creates account_deletion_request (one row per request, at most one pending per account)
-- and deletion_block_condition (operator-editable text and switch per blocking condition), seeds the
-- two conditions, and adds the nullable projection columns to app_user.
--
-- No FK from account_deletion_request to app_user: an admin hard delete must not cascade away the
-- record of a request, and the module reaches users only by plain uuid.
--
-- app_user: two nullable columns without a default are a catalog-only change. The check is added
-- NOT VALID and then validated; every existing row has both columns null, so it passes.
-- Rollback: drop ck_app_user_deletion_projection, the two columns, then both new tables.
CREATE TABLE account_deletion_request (
    id           uuid        PRIMARY KEY,
    user_id      uuid        NOT NULL,
    state        text        NOT NULL,
    requested_at timestamptz NOT NULL,
    scheduled_at timestamptz NOT NULL,
    cancelled_at timestamptz NULL,
    created_at   timestamptz NOT NULL,
    CONSTRAINT ck_account_deletion_request_state CHECK (state IN ('pending', 'cancelled')),
    CONSTRAINT ck_account_deletion_request_cancelled CHECK ((state = 'cancelled') = (cancelled_at IS NOT NULL)),
    CONSTRAINT ck_account_deletion_request_deadline CHECK (scheduled_at > requested_at)
);

CREATE UNIQUE INDEX uq_account_deletion_request_pending ON account_deletion_request (user_id) WHERE state = 'pending';
CREATE INDEX idx_account_deletion_request_user ON account_deletion_request (user_id, requested_at DESC);

ALTER TABLE app_user
    ADD COLUMN deletion_requested_at timestamptz NULL,
    ADD COLUMN deletion_scheduled_at timestamptz NULL;
ALTER TABLE app_user
    ADD CONSTRAINT ck_app_user_deletion_projection
        CHECK ((deletion_requested_at IS NULL) = (deletion_scheduled_at IS NULL)) NOT VALID;
ALTER TABLE app_user VALIDATE CONSTRAINT ck_app_user_deletion_projection;

CREATE TABLE deletion_block_condition (
    key        varchar(64) PRIMARY KEY,
    enabled    boolean     NOT NULL,
    sort_order smallint    NOT NULL,
    message_vi text        NOT NULL,
    message_en text        NOT NULL,
    updated_by uuid        NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_deletion_block_condition_order CHECK (sort_order BETWEEN 0 AND 999)
);

INSERT INTO deletion_block_condition (key, enabled, sort_order, message_vi, message_en, updated_by, updated_at)
VALUES
    ('workflow.active_run', true, 0,
     'Bạn có workflow đang chạy. Hãy chờ chạy xong hoặc liên hệ bộ phận hỗ trợ, rồi thử lại.',
     'A workflow run is still in progress. Wait until it finishes, or contact support, then try again.',
     NULL, now()),
    ('support.open_request', true, 1,
     'Bạn có yêu cầu hỗ trợ đang mở. Hãy chờ yêu cầu được giải quyết hoặc liên hệ bộ phận hỗ trợ, rồi thử lại.',
     'You have an open Help Center request. Wait until it is resolved, or contact support, then try again.',
     NULL, now())
ON CONFLICT (key) DO NOTHING;
