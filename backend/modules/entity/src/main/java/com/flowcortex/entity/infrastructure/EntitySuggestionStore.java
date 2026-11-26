package com.flowcortex.entity.infrastructure;

import com.flowcortex.entity.domain.LabelSuggestions.LabelUse;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** What the space's live entities offer as tag, category and part-label suggestions; trashed entities offer nothing. */
@Repository
public class EntitySuggestionStore {

    /** A suggested text and the number of live entities (or parts) that use it. */
    public record Counted(String label, long count) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public EntitySuggestionStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Tags by use count, then first use; the label is the earliest spelling. {@code qKey} filters by word prefix; blank keeps all. */
    public List<Counted> tags(UUID spaceId, String qKey, int limit) {
        return jdbc.query("SELECT (array_agg(t.tag_label ORDER BY t.created_at, t.entity_id))[1] AS label, "
                + "count(*) AS uses FROM space_entity_tag t JOIN space_entity e ON e.id = t.entity_id "
                + "WHERE e.space_id = :space AND e.deleted_at IS NULL" + wordPrefix("t.tag_key", qKey)
                + " GROUP BY t.tag_key ORDER BY uses DESC, min(t.created_at), t.tag_key LIMIT :limit",
                params(spaceId, qKey).addValue("limit", limit),
                (rs, i) -> new Counted(rs.getString("label"), rs.getLong("uses")));
    }

    /** Categories by use count, then earliest creation; the label is the earliest-created entity's spelling. */
    public List<Counted> categories(UUID spaceId, String qKey, int limit) {
        return jdbc.query("SELECT (array_agg(e.category_label ORDER BY e.created_at, e.id))[1] AS label, "
                + "count(*) AS uses FROM space_entity e WHERE e.space_id = :space AND e.deleted_at IS NULL "
                + "AND e.category_key IS NOT NULL" + wordPrefix("e.category_key", qKey)
                + " GROUP BY e.category_key ORDER BY uses DESC, min(e.created_at), e.category_key LIMIT :limit",
                params(spaceId, qKey).addValue("limit", limit),
                (rs, i) -> new Counted(rs.getString("label"), rs.getLong("uses")));
    }

    /** Every label on a live entity's part, oldest use first, optionally only parts of {@code role}. */
    public List<LabelUse> labelUses(UUID spaceId, String role) {
        return jdbc.query("SELECT p.label, p.label_key, p.created_at FROM space_entity_part p "
                + "JOIN space_entity e ON e.id = p.entity_id WHERE e.space_id = :space AND e.deleted_at IS NULL "
                + "AND p.label IS NOT NULL AND (CAST(:role AS varchar) IS NULL OR p.role = :role) "
                + "ORDER BY p.created_at, p.entity_id, p.sort",
                new MapSqlParameterSource("space", spaceId).addValue("role", role),
                (rs, i) -> new LabelUse(rs.getString("label"), rs.getString("label_key"),
                        rs.getTimestamp("created_at").toInstant()));
    }

    private static String wordPrefix(String column, String qKey) {
        return qKey == null ? "" : " AND (" + column + " LIKE :prefix OR " + column + " LIKE :word)";
    }

    private static MapSqlParameterSource params(UUID spaceId, String qKey) {
        MapSqlParameterSource params = new MapSqlParameterSource("space", spaceId);
        if (qKey != null) {
            params.addValue("prefix", SqlLike.prefix(qKey)).addValue("word", SqlLike.wordStart(qKey));
        }
        return params;
    }
}
