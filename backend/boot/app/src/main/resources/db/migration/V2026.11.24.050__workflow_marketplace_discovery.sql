-- Workflow marketplace discovery: accent-insensitive name search function and the tables of the periodic
-- discovery figures and the exact rating totals. The indexes are built by the next migration
-- (CONCURRENTLY, outside a transaction) and the rating totals are filled by the one after it, so neither
-- the index builds nor the scan of workflow_review runs while this transaction holds the lock that
-- creating a table referencing workflow takes on it.
--
-- workflow_search_fold has the rule and constants of app_user_search_fold (V2026.11.12.110__user_directory.sql);
-- the only addition is normalize(.., NFC), so a name stored decomposed folds like a composed one.
-- Deploy condition: the migration role owns the database, as pg_trgm needs for the user-directory migrations
-- (the statement is a no-op where the extension already exists).
-- Rollback (deploy the previous image first): DROP TABLE workflow_rating_total, workflow_discovery_stats;
-- DROP INDEX idx_workflow_bookmark_created, idx_workflow_catalogue, idx_workflow_name_fold_trgm;
-- DROP FUNCTION workflow_search_fold(text); then delete the Flyway history rows of this version and of the
-- two that follow it. pg_trgm stays.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE FUNCTION workflow_search_fold(text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE RETURNS NULL ON NULL INPUT
    AS $$ SELECT translate(lower(normalize($1, NFC)),
        'àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđÀÁẠẢÃÂẦẤẬẨẪĂẰẮẶẲẴÈÉẸẺẼÊỀẾỆỂỄÌÍỊỈĨÒÓỌỎÕÔỒỐỘỔỖƠỜỚỢỞỠÙÚỤỦŨƯỪỨỰỬỮỲÝỴỶỸĐ',
        'aaaaaaaaaaaaaaaaaeeeeeeeeeeeiiiiiooooooooooooooooouuuuuuuuuuuyyyyydaaaaaaaaaaaaaaaaaeeeeeeeeeeeiiiiiooooooooooooooooouuuuuuuuuuuyyyyyd') $$;

CREATE TABLE workflow_discovery_stats (
    workflow_id     uuid        PRIMARY KEY REFERENCES workflow (id) ON DELETE CASCADE,
    runners_7d      integer     NOT NULL CHECK (runners_7d >= 0),
    saves_7d        integer     NOT NULL CHECK (saves_7d >= 0),
    members_30d     integer     NOT NULL CHECK (members_30d >= 0),
    trending_score  integer     NOT NULL CHECK (trending_score >= 0),
    computed_at     timestamptz NOT NULL
);

CREATE TABLE workflow_rating_total (
    workflow_id  uuid    PRIMARY KEY REFERENCES workflow (id) ON DELETE CASCADE,
    rating_count integer NOT NULL CHECK (rating_count >= 0),
    rating_sum   integer NOT NULL CHECK (rating_sum >= 0)
);
