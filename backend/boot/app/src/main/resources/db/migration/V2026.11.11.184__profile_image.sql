-- Profile images (avatar and cover): the member's original uploads (history) and the one cropped
-- image currently shown per slot.
--
-- user_id carries no foreign key to app_user, like the other cross-module tables, so a deleted
-- account leaves its rows behind.

CREATE TABLE profile_image (
    id          uuid         PRIMARY KEY,
    user_id     uuid         NOT NULL,
    slot        varchar(10)  NOT NULL CHECK (slot IN ('avatar', 'cover')),
    role        varchar(10)  NOT NULL CHECK (role IN ('original', 'current')),
    object_key  varchar(512) NOT NULL UNIQUE,
    mime_type   varchar(50)  NOT NULL,
    width       integer      NOT NULL,
    height      integer      NOT NULL,
    bytes       bigint       NOT NULL,
    created_at  timestamptz  NOT NULL
);

CREATE UNIQUE INDEX uq_profile_image_current ON profile_image (user_id, slot) WHERE role = 'current';
CREATE INDEX idx_profile_image_history ON profile_image (user_id, slot, created_at DESC) WHERE role = 'original';
