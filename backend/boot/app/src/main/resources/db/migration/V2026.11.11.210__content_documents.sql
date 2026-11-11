-- Versioned content documents: one editable draft row and one published row per slug, an
-- append-only revision history, and the media library. The same migration adds the four content
-- permission keys and seeds them to the two staff roles that manage content.
--
-- updated_by / author_id / uploaded_by carry no foreign key to app_user, like the other
-- cross-module tables, so a deleted account leaves its rows behind.

CREATE TABLE content_document (
    id           uuid         PRIMARY KEY,
    slug         varchar(64)  NOT NULL,
    status       varchar(16)  NOT NULL CHECK (status IN ('draft', 'published')),
    version      int          NOT NULL CHECK (version >= 1),
    sections     jsonb        NOT NULL,
    seo_meta     jsonb        NOT NULL,
    published_at timestamptz  NULL,
    updated_by   uuid         NOT NULL,
    created_at   timestamptz  NOT NULL,
    updated_at   timestamptz  NOT NULL,
    CONSTRAINT uq_content_document_slug_status UNIQUE (slug, status),
    CONSTRAINT ck_content_document_published_at CHECK ((status = 'published') = (published_at IS NOT NULL))
);

CREATE TABLE content_document_revision (
    id         uuid        PRIMARY KEY,
    slug       varchar(64) NOT NULL,
    kind       varchar(16) NOT NULL CHECK (kind IN ('draft', 'publication')),
    version    int         NOT NULL,
    sections   jsonb       NOT NULL,
    seo_meta   jsonb       NOT NULL,
    author_id  uuid        NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE INDEX idx_content_revision_slug_created
    ON content_document_revision (slug, created_at DESC, id DESC);
CREATE INDEX idx_content_revision_slug_kind_created
    ON content_document_revision (slug, kind, created_at DESC, id DESC);

CREATE TABLE content_media (
    id           uuid         PRIMARY KEY,
    kind         varchar(8)   NOT NULL CHECK (kind IN ('image', 'video')),
    content_type varchar(64)  NOT NULL,
    size_bytes   bigint       NOT NULL CHECK (size_bytes > 0),
    bucket       varchar(63)  NOT NULL,
    object_key   varchar(512) NOT NULL UNIQUE,
    file_name    varchar(255) NOT NULL,
    uploaded_by  uuid         NOT NULL,
    created_at   timestamptz  NOT NULL
);

CREATE INDEX idx_content_media_kind_created ON content_media (kind, created_at DESC, id DESC);
CREATE INDEX idx_content_media_created ON content_media (created_at DESC, id DESC);

-- The enum Permission lists the content keys right after content.help_article.manage, so the keys
-- after it move up four places. Both statements anchor on that key, not on a position, because
-- other migrations add keys before it. The guard makes a re-run leave the order untouched.
UPDATE permission
SET sort_order = sort_order + 4
WHERE NOT EXISTS (SELECT 1 FROM permission WHERE key = 'content.document.view')
  AND sort_order > (SELECT sort_order FROM permission WHERE key = 'content.help_article.manage');

INSERT INTO permission (key, module, feature, action, super_admin_only, sort_order)
SELECT new_key.key, 'content', new_key.feature, new_key.action, false, anchor.sort_order + new_key.place
FROM permission anchor
CROSS JOIN (VALUES ('content.document.view',   'document', 'view',   1),
                   ('content.document.manage', 'document', 'manage', 2),
                   ('content.media.view',      'media',    'view',   3),
                   ('content.media.manage',    'media',    'manage', 4)) AS new_key (key, feature, action, place)
WHERE anchor.key = 'content.help_article.manage'
ON CONFLICT (key) DO NOTHING;

INSERT INTO role_permission (role_code, permission_key)
SELECT role_code, key
FROM (VALUES ('OPERATION_MANAGER'), ('CONTENT_EDITOR')) AS staff (role_code)
CROSS JOIN permission
WHERE key IN ('content.document.view', 'content.document.manage',
              'content.media.view', 'content.media.manage');
