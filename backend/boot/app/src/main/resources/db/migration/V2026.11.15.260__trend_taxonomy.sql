-- Trend template taxonomy: the category and tag catalogues, the four starting categories, and the
-- two content.trend_template.* permission keys with their staff grants. Categories are addressed by
-- a stable text key that template rows will store; tags by uuid. No template table exists yet, so
-- neither table has a relation.
--
-- Rollback (forward-only, a later migration): DROP TABLE trend_tag; DROP TABLE trend_category;
-- DELETE FROM role_permission WHERE permission_key LIKE 'content.trend_template.%';
-- DELETE FROM permission WHERE key LIKE 'content.trend_template.%'; and remove the two
-- CONTENT_TREND_TEMPLATE_* constants from the Permission enum in the same release, because the
-- startup check requires the enum and the table to agree. Safe while no template table references
-- trend_category.

CREATE TABLE trend_category (
    key           varchar(64)  PRIMARY KEY CHECK (key ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    name_vi       varchar(60)  NOT NULL,
    name_en       varchar(60)  NOT NULL,
    display_order int          NOT NULL,
    visible       boolean      NOT NULL DEFAULT true,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE trend_tag (
    id            uuid         PRIMARY KEY,
    name_vi       varchar(30)  NOT NULL,
    name_en       varchar(30)  NOT NULL,
    display_order int          NOT NULL,
    visible       boolean      NOT NULL DEFAULT true,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now()
);

INSERT INTO trend_category (key, name_vi, name_en, display_order) VALUES
    ('nguoi', 'Người', 'People', 0),
    ('san-pham', 'Sản phẩm', 'Products', 1),
    ('thu-cung', 'Thú cưng', 'Pets', 2),
    ('nhay-chuyen-canh', 'Nhảy và chuyển cảnh', 'Dance and transitions', 3);

-- The enum Permission lists the two keys last, so they take the next two sort_order values after
-- the highest one present.
INSERT INTO permission (key, module, feature, action, super_admin_only, sort_order)
SELECT k.key, 'content', 'trend_template', k.action, false, base.top + k.pos
FROM (VALUES ('content.trend_template.view', 'view', 1), ('content.trend_template.manage', 'manage', 2))
         AS k (key, action, pos)
CROSS JOIN (SELECT COALESCE(MAX(sort_order), 0) AS top FROM permission) AS base;

INSERT INTO role_permission (role_code, permission_key)
SELECT role_code, key
FROM (VALUES ('OPERATION_MANAGER'), ('CONTENT_EDITOR')) AS staff (role_code)
CROSS JOIN permission
WHERE key IN ('content.trend_template.view', 'content.trend_template.manage');
