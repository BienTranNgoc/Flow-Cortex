-- Spaces: the unit later asset, drive and project data belongs to. Every member has one personal
-- space (the partial unique index is what makes creation exactly-once under concurrent calls).
--
-- owner_id / user_id carry no foreign key to app_user, like the other cross-module tables, so a
-- deleted account leaves its space rows behind. media_asset.space_id is nullable with no foreign
-- key or index yet: rows written by code that does not know the column stay valid.

CREATE TABLE space (
    id          uuid        PRIMARY KEY,
    owner_id    uuid        NOT NULL,
    is_default  boolean     NOT NULL,
    created_at  timestamptz NOT NULL
);

CREATE UNIQUE INDEX uq_space_personal_owner ON space (owner_id) WHERE is_default;

CREATE TABLE space_member (
    space_id   uuid        NOT NULL REFERENCES space (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL,
    role       varchar(20) NOT NULL CHECK (role IN ('manager', 'editor', 'viewer')),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (space_id, user_id)
);

CREATE INDEX idx_space_member_user_id ON space_member (user_id);

ALTER TABLE media_asset ADD COLUMN space_id uuid NULL;
