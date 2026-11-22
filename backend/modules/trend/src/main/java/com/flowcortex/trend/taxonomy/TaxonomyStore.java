package com.flowcortex.trend.taxonomy;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** The category and tag tables over JDBC: the taxonomy needs {@code ON CONFLICT} and row locks. */
@Repository
class TaxonomyStore {

    /** The two record kinds a reorder addresses; the identifier is compared as text for both. */
    enum Kind {
        CATEGORY("trend_category", "key"),
        TAG("trend_tag", "id::text");

        private final String table;
        private final String identifier;

        Kind(String table, String identifier) {
            this.table = table;
            this.identifier = identifier;
        }
    }

    record CategoryRow(String key, String nameVi, String nameEn, int order, boolean visible) {
    }

    record TagRow(UUID id, String nameVi, String nameEn, int order, boolean visible) {
    }

    private static final String CATEGORY_COLUMNS = "key, name_vi, name_en, display_order, visible";
    private static final String TAG_COLUMNS = "id, name_vi, name_en, display_order, visible";

    private static final RowMapper<CategoryRow> CATEGORY_ROW = (rs, rowNum) -> new CategoryRow(
            rs.getString("key"), rs.getString("name_vi"), rs.getString("name_en"), rs.getInt("display_order"),
            rs.getBoolean("visible"));

    private static final RowMapper<TagRow> TAG_ROW = (rs, rowNum) -> new TagRow(
            rs.getObject("id", UUID.class), rs.getString("name_vi"), rs.getString("name_en"),
            rs.getInt("display_order"), rs.getBoolean("visible"));

    private final NamedParameterJdbcTemplate jdbc;

    TaxonomyStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<CategoryRow> listCategories() {
        return jdbc.query("SELECT " + CATEGORY_COLUMNS + " FROM trend_category ORDER BY display_order, key",
                CATEGORY_ROW);
    }

    List<TagRow> listTags() {
        return jdbc.query("SELECT " + TAG_COLUMNS + " FROM trend_tag ORDER BY display_order, created_at, id",
                TAG_ROW);
    }

    int countCategories() {
        return count("trend_category");
    }

    int countTags() {
        return count("trend_tag");
    }

    /** Empty when the key is taken, so the caller moves on to its next candidate. */
    Optional<CategoryRow> insertCategory(String key, String nameVi, String nameEn) {
        List<CategoryRow> inserted = jdbc.query(
                "INSERT INTO trend_category (key, name_vi, name_en, display_order)"
                        + " SELECT CAST(:key AS varchar), CAST(:nameVi AS varchar), CAST(:nameEn AS varchar),"
                        + " COALESCE(MAX(display_order), -1) + 1 FROM trend_category"
                        + " ON CONFLICT (key) DO NOTHING RETURNING " + CATEGORY_COLUMNS,
                new MapSqlParameterSource("key", key).addValue("nameVi", nameVi).addValue("nameEn", nameEn),
                CATEGORY_ROW);
        return inserted.stream().findFirst();
    }

    TagRow insertTag(UUID id, String nameVi, String nameEn) {
        return jdbc.query(
                "INSERT INTO trend_tag (id, name_vi, name_en, display_order)"
                        + " SELECT CAST(:id AS uuid), CAST(:nameVi AS varchar), CAST(:nameEn AS varchar),"
                        + " COALESCE(MAX(display_order), -1) + 1 FROM trend_tag RETURNING " + TAG_COLUMNS,
                new MapSqlParameterSource("id", id).addValue("nameVi", nameVi).addValue("nameEn", nameEn),
                TAG_ROW).get(0);
    }

    Optional<CategoryRow> updateCategory(String key, String nameVi, String nameEn, Boolean visible) {
        return jdbc.query(
                "UPDATE trend_category SET name_vi = COALESCE(CAST(:nameVi AS varchar), name_vi),"
                        + " name_en = COALESCE(CAST(:nameEn AS varchar), name_en),"
                        + " visible = COALESCE(CAST(:visible AS boolean), visible), updated_at = now()"
                        + " WHERE key = :key RETURNING " + CATEGORY_COLUMNS,
                patch("key", key, nameVi, nameEn, visible), CATEGORY_ROW).stream().findFirst();
    }

    Optional<TagRow> updateTag(UUID id, String nameVi, String nameEn, Boolean visible) {
        return jdbc.query(
                "UPDATE trend_tag SET name_vi = COALESCE(CAST(:nameVi AS varchar), name_vi),"
                        + " name_en = COALESCE(CAST(:nameEn AS varchar), name_en),"
                        + " visible = COALESCE(CAST(:visible AS boolean), visible), updated_at = now()"
                        + " WHERE id = CAST(:id AS uuid) RETURNING " + TAG_COLUMNS,
                patch("id", id, nameVi, nameEn, visible), TAG_ROW).stream().findFirst();
    }

    /**
     * Sets {@code display_order} to each identifier's position, but only when {@code wanted} is exactly the
     * current set. The rows of the kind stay locked from the comparison to the update, so a concurrent
     * reorder cannot interleave; false means nothing changed.
     */
    @Transactional
    boolean reorder(Kind kind, List<String> wanted) {
        List<String> current = jdbc.getJdbcOperations().queryForList(
                "SELECT " + kind.identifier + " FROM " + kind.table + " FOR UPDATE", String.class);
        if (current.size() != wanted.size() || !new HashSet<>(current).equals(new HashSet<>(wanted))) {
            return false;
        }
        SqlParameterSource[] positions = new SqlParameterSource[wanted.size()];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = new MapSqlParameterSource("position", i).addValue("identifier", wanted.get(i));
        }
        jdbc.batchUpdate("UPDATE " + kind.table + " SET display_order = :position, updated_at = now()"
                + " WHERE " + kind.identifier + " = :identifier", positions);
        return true;
    }

    /** A foreign key from a template row surfaces as {@code DataIntegrityViolationException}. */
    boolean deleteCategory(String key) {
        return jdbc.update("DELETE FROM trend_category WHERE key = :key", new MapSqlParameterSource("key", key)) > 0;
    }

    boolean deleteTag(UUID id) {
        return jdbc.update("DELETE FROM trend_tag WHERE id = CAST(:id AS uuid)",
                new MapSqlParameterSource("id", id)) > 0;
    }

    private int count(String table) {
        Integer count = jdbc.getJdbcOperations().queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private static MapSqlParameterSource patch(String idName, Object id, String nameVi, String nameEn,
            Boolean visible) {
        return new MapSqlParameterSource(idName, id)
                .addValue("nameVi", nameVi)
                .addValue("nameEn", nameEn)
                .addValue("visible", visible);
    }
}
