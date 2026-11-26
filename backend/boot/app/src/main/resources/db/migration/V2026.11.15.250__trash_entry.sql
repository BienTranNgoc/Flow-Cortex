-- The member's Trash index, owned by the trash module. No foreign keys across modules: the owner and the
-- trashed row are referenced by id and checked by the owning module's provider.
CREATE TABLE trash_entry (
    id            uuid         PRIMARY KEY,
    item_type     varchar(32)  NOT NULL CONSTRAINT ck_trash_entry_type CHECK (item_type ~ '^[a-z][a-z0-9_]{1,31}$'),
    item_id       varchar(64)  NOT NULL CONSTRAINT ck_trash_entry_item CHECK (item_id ~ '^[A-Za-z0-9_-]{1,64}$'),
    owner_id      uuid         NOT NULL,
    deleted_by    uuid         NOT NULL,
    name          text                  CONSTRAINT ck_trash_entry_name CHECK (name IS NULL OR char_length(name) BETWEEN 1 AND 200),
    name_key      text         NOT NULL,
    location_code varchar(128) NOT NULL CONSTRAINT ck_trash_entry_location CHECK (location_code ~ '^[A-Za-z0-9._-]{1,128}$'),
    deleted_at    timestamptz  NOT NULL,
    reminded_at   timestamptz,
    CONSTRAINT uq_trash_entry_item UNIQUE (item_type, item_id)
);
CREATE INDEX ix_trash_entry_owner_deleted      ON trash_entry (owner_id, deleted_at DESC, id);
CREATE INDEX ix_trash_entry_owner_type_deleted ON trash_entry (owner_id, item_type, deleted_at DESC, id);
CREATE INDEX ix_trash_entry_deleted            ON trash_entry (deleted_at, id);
CREATE INDEX ix_trash_entry_unreminded         ON trash_entry (deleted_at) WHERE reminded_at IS NULL;
