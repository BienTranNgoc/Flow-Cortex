-- Roles become editable rows: a name, a description and a keys version, a case-insensitive unique
-- name, and a code format. A custom role is always a staff role, never the super admin.

-- sort_order mirrors the declaration order of the Permission enum, so the new key takes the slot
-- right after system.role.view and the admin-account keys move down one place.
UPDATE permission SET sort_order = sort_order + 1
WHERE key IN ('system.admin_account.view', 'system.admin_account.manage');

INSERT INTO permission (key, module, feature, action, super_admin_only, sort_order)
VALUES ('system.role.manage', 'system', 'role', 'manage', true,
        (SELECT sort_order + 1 FROM permission WHERE key = 'system.role.view'));

ALTER TABLE role
    ADD COLUMN name                varchar(100),
    ADD COLUMN description         varchar(500),
    ADD COLUMN permissions_version integer     NOT NULL DEFAULT 0,
    ADD COLUMN created_at          timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN updated_at          timestamptz NOT NULL DEFAULT now();

UPDATE role SET name = CASE code
    WHEN 'ADMIN'             THEN 'Super admin'
    WHEN 'OPERATION_MANAGER' THEN 'Operation manager'
    WHEN 'CUSTOMER_SUPPORT'  THEN 'Customer support'
    WHEN 'CONTENT_EDITOR'    THEN 'Content editor'
    WHEN 'PLATFORM_OPERATOR' THEN 'Platform operator'
    WHEN 'MEMBER'            THEN 'Member'
    WHEN 'CREATOR'           THEN 'Creator'
END;

ALTER TABLE role ALTER COLUMN name SET NOT NULL;

-- The index, not a pre-check, decides two creates with one name at once.
CREATE UNIQUE INDEX uq_role_name_ci ON role (lower(btrim(name)));

ALTER TABLE role
    ADD CONSTRAINT ck_role_code_format CHECK (code ~ '^[A-Z][A-Z0-9_]{0,19}$'),
    ADD CONSTRAINT ck_role_custom_is_staff CHECK (system OR (staff AND NOT super_admin));
