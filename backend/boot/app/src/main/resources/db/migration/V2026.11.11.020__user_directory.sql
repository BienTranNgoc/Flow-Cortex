-- Admin user directory: accent-insensitive search index, owner-scoped paging index and the export key.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- lower() is relied on for ASCII only; the Vietnamese composed letters are mapped explicitly so the
-- result does not depend on the database LC_CTYPE. Keep in step with SearchFold in the user module.
CREATE FUNCTION app_user_search_fold(text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE RETURNS NULL ON NULL INPUT
    AS $$ SELECT translate(lower($1),
        'àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđÀÁẠẢÃÂẦẤẬẨẪĂẰẮẶẲẴÈÉẸẺẼÊỀẾỆỂỄÌÍỊỈĨÒÓỌỎÕÔỒỐỘỔỖƠỜỚỢỞỠÙÚỤỦŨƯỪỨỰỬỮỲÝỴỶỸĐ',
        'aaaaaaaaaaaaaaaaaeeeeeeeeeeeiiiiiooooooooooooooooouuuuuuuuuuuyyyyydaaaaaaaaaaaaaaaaaeeeeeeeeeeeiiiiiooooooooooooooooouuuuuuuuuuuyyyyyd') $$;

CREATE INDEX idx_app_user_search_trgm ON app_user
    USING gin (app_user_search_fold(name || ' ' || email || ' ' || coalesce(username, '')) gin_trgm_ops);

CREATE INDEX idx_app_user_owner_created ON app_user (owner_id, created_at DESC, id DESC);

INSERT INTO permission (key, module, feature, action, super_admin_only, sort_order)
SELECT 'user.account.export', 'user', 'account', 'export', false, coalesce(max(sort_order), 0) + 1
FROM permission
ON CONFLICT DO NOTHING;

INSERT INTO role_permission (role_code, permission_key) VALUES ('OPERATION_MANAGER', 'user.account.export')
ON CONFLICT DO NOTHING;
