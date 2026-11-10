-- Runtime settings registry: a row exists only while an admin value is stored (a reset deletes it).
-- The value is canonical text; its type lives in the key's declaration. updated_by / changed_by carry
-- no foreign key, so deleting an account keeps the history.

CREATE TABLE app_setting (
    key         varchar(128) PRIMARY KEY,
    value       text         NOT NULL,
    updated_by  uuid         NULL,
    updated_at  timestamptz  NOT NULL
);

CREATE TABLE app_setting_change (
    id              uuid         PRIMARY KEY,
    key             varchar(128) NOT NULL,
    previous_value  text         NULL,
    new_value       text         NULL,
    changed_by      uuid         NULL,
    changed_at      timestamptz  NOT NULL
);

-- Serves the only history query: one key, newest first.
CREATE INDEX idx_app_setting_change_key_at ON app_setting_change (key, changed_at DESC, id DESC);
