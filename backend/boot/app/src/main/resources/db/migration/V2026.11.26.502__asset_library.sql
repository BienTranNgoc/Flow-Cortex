-- What the library reads on top of the registered assets: a favourite flag, the mark a link route sets
-- when the stored file is gone, accent-folded search text for titles and tags, and one tag per folded
-- label per asset. Expand-only: it applies before the deploy that reads these columns, and the
-- registry's inserts are unaffected because every new column is defaulted, nullable or generated.
--
-- The folding rule lives in one IMMUTABLE function so the stored form (generated columns) and the
-- queried form (the same function applied to the search term) cannot drift, and every writer gets
-- the folded text without code. The mark ranges are the combining diacritical blocks:
-- U+0300-036F, U+1AB0-1AFF, U+1DC0-1DFF, U+20D0-20FF, U+FE20-FE2F. `normalize` needs a UTF8 database.
--
-- ADD COLUMN ... GENERATED ... STORED rewrites space_asset once under an ACCESS EXCLUSIVE lock; the
-- table holds only what the registry has written since it shipped. Rollback is one manual statement
-- set (drop space_asset_tag, the idx_space_asset_space_created index, the three columns and the
-- function); no data outside these columns is touched.

CREATE FUNCTION asset_search_fold(t text) RETURNS text
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
    AS $$ SELECT lower(regexp_replace(normalize(replace(replace(t, 'đ', 'd'), 'Đ', 'D'), NFD),
                 '[̀-ͯ᪰-᫿᷀-᷿⃐-⃿︠-︯]', '', 'g')) $$;

ALTER TABLE space_asset
    ADD COLUMN favorite     boolean     NOT NULL DEFAULT false,
    ADD COLUMN missing_at   timestamptz,
    ADD COLUMN title_search text        GENERATED ALWAYS AS (asset_search_fold(title)) STORED;

CREATE INDEX idx_space_asset_space_created ON space_asset (space_id, created_at DESC, id DESC);

-- The foreign key stays inside the asset module. space_id is copied so the per-space tag list needs
-- no join on space_asset.
CREATE TABLE space_asset_tag (
    asset_id   uuid        NOT NULL REFERENCES space_asset (id) ON DELETE CASCADE,
    space_id   uuid        NOT NULL,
    label      text        NOT NULL CHECK (char_length(label) BETWEEN 1 AND 40),
    tag_key    text        GENERATED ALWAYS AS (asset_search_fold(label)) STORED,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (asset_id, tag_key)
);
CREATE INDEX idx_space_asset_tag_space_key ON space_asset_tag (space_id, tag_key);
