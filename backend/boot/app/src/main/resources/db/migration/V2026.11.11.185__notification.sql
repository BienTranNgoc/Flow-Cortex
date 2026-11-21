-- In-app notifications. Additive: two new tables, nothing existing is read or changed.
-- No FK to app_user (another module's table): user ids are plain uuids; delivery checks the member exists.

-- Text is kept as bundle codes plus arguments and rendered when read, in the reader's language.
CREATE TABLE notification (
    id          uuid          PRIMARY KEY,
    user_id     uuid          NOT NULL,
    type        varchar(64)   NOT NULL,
    title_code  varchar(128)  NOT NULL,
    body_code   varchar(128)  NULL,
    args        jsonb         NOT NULL DEFAULT '[]',
    link        varchar(512)  NULL,
    read_at     timestamptz   NULL,
    created_at  timestamptz   NOT NULL
);

CREATE INDEX notification_user_created_idx ON notification (user_id, created_at DESC, id DESC);
CREATE INDEX notification_user_unread_idx ON notification (user_id) WHERE read_at IS NULL;

-- One row per member who changed a choice; no row means every in-app notification is on.
CREATE TABLE notification_preference (
    user_id     uuid         PRIMARY KEY,
    in_app      boolean      NOT NULL DEFAULT true,
    updated_at  timestamptz  NOT NULL
);
