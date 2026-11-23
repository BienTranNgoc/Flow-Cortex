-- Space entities (brand, product, character, voice) with their parts, tags, versions and run usage.
-- All five tables are owned by the entity module and carry no foreign keys across modules: spaces,
-- accounts, library assets, workflows and runs are referenced by id and checked in the application, as
-- the other module tables do. Foreign keys inside the module cascade from space_entity.
--
-- The search_key, category_key, label_key and tag_key columns hold the application's normalised key
-- (NFC, then NFD with marks stripped, lower case). They are text because NFD turns a Hangul syllable
-- into 2-3 kept jamo, so a key can be longer than its label.

CREATE TABLE space_entity (
    id              uuid         PRIMARY KEY,
    space_id        uuid         NOT NULL,
    kind            varchar(16)  NOT NULL CHECK (kind IN ('brand', 'product', 'character', 'voice')),
    handle          varchar(48)  NOT NULL CHECK (handle ~ '^[a-z0-9-]{2,48}$'),
    name            text         NOT NULL CHECK (char_length(name) BETWEEN 1 AND 200),
    search_key      text         NOT NULL,
    fields          jsonb        NOT NULL DEFAULT '{}'::jsonb,
    category_label  varchar(40),
    category_key    text,
    version         integer      NOT NULL CHECK (version >= 1),
    created_by      uuid         NOT NULL,
    created_at      timestamptz  NOT NULL,
    updated_at      timestamptz  NOT NULL,
    deleted_at      timestamptz,
    deleted_by      uuid,
    CONSTRAINT ck_space_entity_trash CHECK ((deleted_at IS NULL) = (deleted_by IS NULL)),
    CONSTRAINT ck_space_entity_category CHECK ((category_label IS NULL) = (category_key IS NULL))
);
CREATE UNIQUE INDEX uq_space_entity_handle_live ON space_entity (space_id, handle) WHERE deleted_at IS NULL;
CREATE INDEX idx_space_entity_live_updated ON space_entity (space_id, updated_at DESC, id) WHERE deleted_at IS NULL;

CREATE TABLE space_entity_part (
    id         uuid        PRIMARY KEY,
    entity_id  uuid        NOT NULL REFERENCES space_entity (id) ON DELETE CASCADE,
    asset_id   uuid        NOT NULL,
    role       varchar(32) NOT NULL,
    label      varchar(60),
    label_key  text,
    sort       integer     NOT NULL CHECK (sort BETWEEN 0 AND 19),
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_space_entity_part_sort UNIQUE (entity_id, sort),
    CONSTRAINT ck_space_entity_part_label CHECK ((label IS NULL) = (label_key IS NULL))
);
CREATE INDEX idx_space_entity_part_asset ON space_entity_part (asset_id);

CREATE TABLE space_entity_tag (
    entity_id  uuid        NOT NULL REFERENCES space_entity (id) ON DELETE CASCADE,
    tag_key    text        NOT NULL,
    tag_label  varchar(40) NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (entity_id, tag_key)
);
CREATE INDEX idx_space_entity_tag_key ON space_entity_tag (tag_key);

CREATE TABLE space_entity_version (
    entity_id  uuid        NOT NULL REFERENCES space_entity (id) ON DELETE CASCADE,
    version    integer     NOT NULL CHECK (version >= 1),
    snapshot   jsonb       NOT NULL,
    changes    jsonb       NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (entity_id, version)
);

CREATE TABLE space_entity_run (
    run_id        uuid        NOT NULL,
    entity_id     uuid        NOT NULL REFERENCES space_entity (id) ON DELETE CASCADE,
    space_id      uuid        NOT NULL,
    version       integer     NOT NULL CHECK (version >= 1),
    workflow_id   uuid,
    workflow_name text,
    created_at    timestamptz NOT NULL,
    completed_at  timestamptz,
    PRIMARY KEY (run_id, entity_id)
);
CREATE INDEX idx_space_entity_run_done ON space_entity_run (entity_id, completed_at DESC, run_id) WHERE completed_at IS NOT NULL;
