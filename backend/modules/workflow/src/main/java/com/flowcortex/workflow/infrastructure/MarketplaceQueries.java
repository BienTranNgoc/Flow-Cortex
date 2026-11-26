package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.application.MarketplaceQuery.Browse;
import com.flowcortex.workflow.domain.Workflow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The catalogue reads: the one statement of which workflows are listed, the orders, the recommended and similar
 * lists, the facets and the totals. Every term, type and id reaches the database as a bound parameter; the only text
 * put into a statement is a fixed fragment chosen by an enum or by the number of bound values.
 */
@Repository
public class MarketplaceQueries {

    /** Which workflows are listed; binds {@code :productVideoOn} and {@code :productVideoId}, alias {@code w}. */
    static final String CATALOGUE = "w.deleted_at IS NULL AND w.status = 'published' AND w.visibility = 'public' "
            + "AND (:productVideoOn OR w.id <> :productVideoId)";

    /** Counts top-level runs only (alias {@code r}), so a child run never counts as its workflow's or its parent's. */
    static final String RUN_SCOPE = " AND r.parent_run_id IS NULL";

    /**
     * The step types of a workflow's published definition, ignoring per-member pins: the snapshot of its published
     * version, or its live steps when it is system-owned or has no published version. Alias {@code w}; binds
     * {@code :systemOwner}.
     */
    static final String PUBLISHED_STEP_TYPES = """
            SELECT ws.type AS type FROM workflow_step ws
             WHERE ws.workflow_id = w.id AND (w.owner_id = :systemOwner OR w.published_version_id IS NULL)
            UNION ALL
            SELECT st.elem ->> 'type' FROM workflow_version v
             CROSS JOIN LATERAL jsonb_array_elements(v.snapshot -> 'steps') AS st(elem)
             WHERE v.id = w.published_version_id AND w.owner_id <> :systemOwner
            """;

    private static final String FIGURES = """
            LEFT JOIN workflow_discovery_stats s ON s.workflow_id = w.id
            LEFT JOIN workflow_rating_total t ON t.workflow_id = w.id
            """;
    private static final String ROW_COLUMNS = """
            w.id AS workflow_id, COALESCE(s.members_30d, 0) AS members_30d,
            COALESCE(t.rating_count, 0) AS rating_count, COALESCE(t.rating_sum, 0) AS rating_sum
            """;
    private static final String PUBLISHED_AT = "COALESCE(pv.published_at, w.created_at)";
    private static final String AVERAGE = "t.rating_sum::numeric / NULLIF(t.rating_count, 0)";
    private static final String MEMBERS_THEN_RATING = "COALESCE(s.members_30d, 0) DESC, " + AVERAGE
            + " DESC NULLS LAST, w.id DESC";
    private static final int MAX_SIMILAR_RUNNERS = 500;
    private static final int WINDOW_DAYS = 90;
    private static final String APPROVAL_CHECKPOINT = "APPROVAL_CHECKPOINT";

    /** A catalogue workflow with the figures its item shows; missing figures are zero. */
    public record CatalogueRow(UUID workflowId, int membersLast30Days, int ratingCount, int ratingSum) {
    }

    /** One page of the catalogue in order and the number of workflows matching, past this page included. */
    public record CataloguePage(List<CatalogueRow> rows, long total) {
    }

    public record Facet(String type, long count) {
    }

    public record Stats(long totalWorkflows, long totalBuilders) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final boolean productVideoOn;

    public MarketplaceQueries(NamedParameterJdbcTemplate jdbc,
            @Value("${flowcortex.product-video.enabled:false}") boolean productVideoOn) {
        this.jdbc = jdbc;
        this.productVideoOn = productVideoOn;
    }

    /** One page of the catalogue matching the parsed read, in the order of its sort. */
    public CataloguePage page(Browse browse) {
        MapSqlParameterSource params = base();
        StringBuilder where = new StringBuilder(CATALOGUE);
        for (int i = 0; i < browse.terms().size(); i++) {
            where.append(" AND workflow_search_fold(w.name) LIKE '%' || workflow_search_fold(:term").append(i)
                    .append(") || '%' ESCAPE '\\'");
            params.addValue("term" + i, browse.terms().get(i));
        }
        if (!browse.stepTypes().isEmpty()) {
            where.append(" AND EXISTS (SELECT 1 FROM (").append(PUBLISHED_STEP_TYPES)
                    .append(") pst WHERE pst.type IN (:stepTypes))");
            params.addValue("stepTypes", browse.stepTypes());
        }
        String from = " FROM workflow w " + FIGURES + " LEFT JOIN workflow_version pv ON pv.id = w.published_version_id"
                + " WHERE " + where;
        Long total = jdbc.queryForObject("SELECT count(*)" + from, params, Long.class);
        params.addValue("limit", browse.size()).addValue("offset", (long) browse.page() * browse.size());
        List<CatalogueRow> rows = jdbc.query("SELECT " + ROW_COLUMNS + from + " ORDER BY " + orderBy(browse)
                + " LIMIT :limit OFFSET :offset", params, MarketplaceQueries::row);
        return new CataloguePage(rows, total == null ? 0 : total);
    }

    private static String orderBy(Browse browse) {
        return switch (browse.sort()) {
            case TRENDING -> "COALESCE(s.trending_score, 0) DESC, COALESCE(s.members_30d, 0) DESC, "
                    + PUBLISHED_AT + " DESC, w.id DESC";
            case POPULAR -> "COALESCE(s.members_30d, 0) DESC, COALESCE(s.trending_score, 0) DESC, w.id DESC";
            case NEWEST -> PUBLISHED_AT + " DESC, w.id DESC";
            case TOP_RATED -> AVERAGE + " DESC NULLS LAST, COALESCE(t.rating_count, 0) DESC, w.id DESC";
            case NAME -> "workflow_search_fold(w.name) ASC, w.id ASC";
        };
    }

    /**
     * Candidates the caller has not run to completion and does not own, with at least one member this month. Those
     * sharing a published step type with what the caller completed in the last 90 days come alone when any exist.
     */
    public List<CatalogueRow> recommended(UUID caller, int limit, Instant now, Set<UUID> excluded) {
        Set<String> affinity = affinityTypes(caller, now);
        if (!affinity.isEmpty()) {
            List<CatalogueRow> sharing = candidates(caller, limit, excluded, affinity);
            if (!sharing.isEmpty()) {
                return sharing;
            }
        }
        return candidates(caller, limit, excluded, Set.of());
    }

    private Set<String> affinityTypes(UUID caller, Instant now) {
        MapSqlParameterSource params = base().addValue("caller", caller).addValue("since", since(now))
                .addValue("approval", APPROVAL_CHECKPOINT);
        return new HashSet<>(jdbc.queryForList("""
                SELECT DISTINCT pst.type FROM workflow_run r JOIN workflow w ON w.id = r.workflow_id
                 CROSS JOIN LATERAL (""" + PUBLISHED_STEP_TYPES + """
                ) pst
                 WHERE r.owner_id = :caller AND r.status = 'completed' AND r.started_at > :since""" + RUN_SCOPE + """
                   AND pst.type IS NOT NULL AND pst.type <> :approval
                """, params, String.class));
    }

    private List<CatalogueRow> candidates(UUID caller, int limit, Set<UUID> excluded, Set<String> affinity) {
        MapSqlParameterSource params = base().addValue("caller", caller).addValue("limit", limit);
        StringBuilder where = new StringBuilder(CATALOGUE)
                .append(" AND w.owner_id <> :caller AND COALESCE(s.members_30d, 0) >= 1")
                .append(" AND NOT EXISTS (SELECT 1 FROM workflow_run c WHERE c.workflow_id = w.id"
                        + " AND c.owner_id = :caller AND c.status = 'completed')");
        if (!excluded.isEmpty()) {
            where.append(" AND w.id NOT IN (:excluded)");
            params.addValue("excluded", excluded);
        }
        if (!affinity.isEmpty()) {
            where.append(" AND EXISTS (SELECT 1 FROM (").append(PUBLISHED_STEP_TYPES)
                    .append(") pst WHERE pst.type IN (:affinity))");
            params.addValue("affinity", affinity);
        }
        return jdbc.query("SELECT " + ROW_COLUMNS + " FROM workflow w " + FIGURES + " WHERE " + where
                + " ORDER BY " + MEMBERS_THEN_RATING + " LIMIT :limit", params, MarketplaceQueries::row);
    }

    /**
     * Catalogue workflows other than {@code id} run, in the last 90 days, by the members who ran {@code id} (its
     * 500 latest runners), most shared runners first.
     */
    public List<CatalogueRow> similar(UUID id, int limit, Instant now, Set<UUID> excluded) {
        MapSqlParameterSource params = base().addValue("id", id).addValue("since", since(now))
                .addValue("limit", limit).addValue("runnerCap", MAX_SIMILAR_RUNNERS);
        String excludedClause = "";
        if (!excluded.isEmpty()) {
            excludedClause = " AND r.workflow_id NOT IN (:excluded)";
            params.addValue("excluded", excluded);
        }
        return jdbc.query("""
                WITH runners AS (
                    SELECT r.owner_id FROM workflow_run r
                     WHERE r.workflow_id = :id AND r.started_at > :since""" + RUN_SCOPE + """

                     GROUP BY r.owner_id ORDER BY max(r.started_at) DESC LIMIT :runnerCap),
                co AS (
                    SELECT r.workflow_id, count(DISTINCT r.owner_id) AS shared
                      FROM workflow_run r JOIN runners u ON u.owner_id = r.owner_id
                     WHERE r.started_at > :since AND r.workflow_id <> :id""" + RUN_SCOPE + excludedClause + """

                     GROUP BY r.workflow_id)
                SELECT\s""" + ROW_COLUMNS + """
                  FROM co JOIN workflow w ON w.id = co.workflow_id\s""" + FIGURES + """
                 WHERE\s""" + CATALOGUE + """

                 ORDER BY co.shared DESC, COALESCE(s.trending_score, 0) DESC, w.id DESC
                 LIMIT :limit
                """, params, MarketplaceQueries::row);
    }

    /** Every step type of a catalogue workflow's published definition with the workflows using it. */
    public List<Facet> facets() {
        return jdbc.query("""
                SELECT pst.type AS type, count(DISTINCT w.id) AS workflows
                  FROM workflow w CROSS JOIN LATERAL (""" + PUBLISHED_STEP_TYPES + """
                ) pst
                 WHERE\s""" + CATALOGUE + """
                   AND pst.type IS NOT NULL
                 GROUP BY pst.type ORDER BY 2 DESC, 1
                """, base(), (rs, row) -> new Facet(rs.getString("type"), rs.getLong("workflows")));
    }

    /** Catalogue workflows and their distinct member owners; the system owner is not a builder. */
    public Stats stats() {
        return jdbc.queryForObject("""
                SELECT count(*) AS workflows,
                       count(DISTINCT w.owner_id) FILTER (WHERE w.owner_id <> :systemOwner) AS builders
                  FROM workflow w WHERE\s""" + CATALOGUE,
                base(), (rs, row) -> new Stats(rs.getLong("workflows"), rs.getLong("builders")));
    }

    /** The caller's own workflows that are not deleted, drafts included. */
    public long countMine(UUID caller) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM workflow WHERE owner_id = :caller"
                + " AND deleted_at IS NULL", new MapSqlParameterSource("caller", caller), Long.class);
        return count == null ? 0 : count;
    }

    /** The ids among {@code ids} the caller saved. */
    public Set<UUID> bookmarkedAmong(UUID caller, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList("SELECT workflow_id FROM workflow_bookmark WHERE user_id = :caller"
                + " AND workflow_id IN (:ids)", new MapSqlParameterSource("caller", caller).addValue("ids", ids),
                UUID.class));
    }

    private MapSqlParameterSource base() {
        return new MapSqlParameterSource()
                .addValue("productVideoOn", productVideoOn)
                .addValue("productVideoId", ProductVideoWorkflow.ID)
                .addValue("systemOwner", Workflow.SYSTEM_OWNER_ID);
    }

    private static OffsetDateTime since(Instant now) {
        return OffsetDateTime.ofInstant(now.minusSeconds(WINDOW_DAYS * 86_400L), ZoneOffset.UTC);
    }

    private static CatalogueRow row(ResultSet rs, int row) throws SQLException {
        return new CatalogueRow(rs.getObject("workflow_id", UUID.class), rs.getInt("members_30d"),
                rs.getInt("rating_count"), rs.getInt("rating_sum"));
    }
}
