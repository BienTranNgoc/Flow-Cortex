package com.flowcortex.workflow.infrastructure;

import com.flowcortex.workflow.api.AdminWorkflowFilter;
import com.flowcortex.workflow.api.DayCount;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Grouped read models over {@code workflow} and {@code workflow_run} for the admin workflow list and the
 * builder dashboard. Native SQL: each page needs aggregates over runs that an entity graph would load row by
 * row. Runs a member removed from their own history are still counted: these reads never name
 * {@code workflow_run.deleted_at}.
 */
@Repository
public class WorkflowUsageQueries {

    /** A live (not deleted) workflow's own columns plus its step count. */
    public record WorkflowRow(
            UUID id, String name, UUID ownerId, String status, boolean shared, int steps, Instant createdAt,
            Instant updatedAt) {
    }

    /** A workflow's run total, runs since a cutoff, and newest start. */
    public record RunAggregate(long total, long recent, Instant lastRunAt) {
    }

    /** An owner's workflow counts and the runs of those workflows. */
    public record BuilderTotals(long workflows, long published, long runs) {
    }

    private static final String WORKFLOW_COLUMNS = "SELECT w.id, w.name, w.owner_id, w.status, w.is_shared, "
            + "w.created_at, w.updated_at, s.n AS steps FROM workflow w "
            + "LEFT JOIN LATERAL (SELECT count(*) AS n FROM workflow_step ws WHERE ws.workflow_id = w.id) s ON true "
            + "WHERE w.deleted_at IS NULL";

    private static final String OWNED_LIVE_WORKFLOWS =
            "SELECT id FROM workflow WHERE owner_id = :ownerId AND deleted_at IS NULL";

    private static final String DAY_BUCKETS = "SELECT (started_at AT TIME ZONE :zone)::date AS day, count(*) AS runs, "
            + "count(*) FILTER (WHERE status = 'error') AS failed FROM workflow_run WHERE started_at >= :windowStart ";

    private static final RowMapper<WorkflowRow> WORKFLOW_ROW = (rs, row) -> new WorkflowRow(
            rs.getObject("id", UUID.class), rs.getString("name"), rs.getObject("owner_id", UUID.class),
            rs.getString("status"), rs.getBoolean("is_shared"), rs.getInt("steps"), instant(rs, "created_at"),
            instant(rs, "updated_at"));

    private static final RowMapper<DayCount> DAY_COUNT = (rs, row) -> new DayCount(
            rs.getDate("day").toLocalDate(), rs.getLong("runs"), rs.getLong("failed"));

    private final NamedParameterJdbcTemplate jdbc;

    WorkflowUsageQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One page of live workflows, most recently updated first. */
    public List<WorkflowRow> pageWorkflows(AdminWorkflowFilter filter, int page, int size) {
        MapSqlParameterSource params = filterParams(filter);
        params.addValue("limit", size).addValue("offset", (long) page * size);
        return jdbc.query(WORKFLOW_COLUMNS + filterClauses(filter)
                + " ORDER BY w.updated_at DESC, w.id DESC LIMIT :limit OFFSET :offset", params, WORKFLOW_ROW);
    }

    public long countWorkflows(AdminWorkflowFilter filter) {
        Long total = jdbc.queryForObject("SELECT count(*) FROM workflow w WHERE w.deleted_at IS NULL"
                + filterClauses(filter), filterParams(filter), Long.class);
        return total == null ? 0 : total;
    }

    public Optional<WorkflowRow> findWorkflow(UUID id) {
        return jdbc.query(WORKFLOW_COLUMNS + " AND w.id = :id", new MapSqlParameterSource("id", id), WORKFLOW_ROW)
                .stream().findFirst();
    }

    /** Run totals for the given workflows in one grouped statement; a workflow never run is absent. */
    public Map<UUID, RunAggregate> runAggregates(Collection<UUID> workflowIds, Instant since) {
        if (workflowIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, RunAggregate> aggregates = new HashMap<>();
        jdbc.query("SELECT workflow_id, count(*) AS total, count(*) FILTER (WHERE started_at >= :since) AS recent, "
                        + "max(started_at) AS last_run_at FROM workflow_run WHERE workflow_id IN (:ids) "
                        + "GROUP BY workflow_id",
                new MapSqlParameterSource("ids", workflowIds).addValue("since", Timestamp.from(since)),
                (ResultSet rs) -> {
                    aggregates.put(rs.getObject("workflow_id", UUID.class), new RunAggregate(rs.getLong("total"),
                            rs.getLong("recent"), instant(rs, "last_run_at")));
                });
        return aggregates;
    }

    /** Run count per status value as stored (lowercase); a status with no run is absent. */
    public Map<String, Long> statusCounts(UUID workflowId) {
        Map<String, Long> counts = new HashMap<>();
        jdbc.query("SELECT status, count(*) AS runs FROM workflow_run WHERE workflow_id = :id GROUP BY status",
                new MapSqlParameterSource("id", workflowId),
                (ResultSet rs) -> {
                    counts.put(rs.getString("status"), rs.getLong("runs"));
                });
        return counts;
    }

    /** Runs per local day since {@code windowStart} for one workflow; days with no run are absent. */
    public List<DayCount> dailyCounts(UUID workflowId, Instant windowStart, ZoneId zone) {
        return jdbc.query(DAY_BUCKETS + "AND workflow_id = :workflowId GROUP BY 1",
                dayParams(windowStart, zone).addValue("workflowId", workflowId), DAY_COUNT);
    }

    /** Runs per local day since {@code windowStart} over every live workflow the owner has. */
    public List<DayCount> dailyCountsForOwner(UUID ownerId, Instant windowStart, ZoneId zone) {
        return jdbc.query(DAY_BUCKETS + "AND workflow_id IN (" + OWNED_LIVE_WORKFLOWS + ") GROUP BY 1",
                dayParams(windowStart, zone).addValue("ownerId", ownerId), DAY_COUNT);
    }

    public BuilderTotals builderTotals(UUID ownerId) {
        return jdbc.queryForObject("SELECT count(*) AS workflows, count(*) FILTER (WHERE status = 'published') "
                + "AS published, (SELECT count(*) FROM workflow_run WHERE workflow_id IN (" + OWNED_LIVE_WORKFLOWS
                + ")) AS runs FROM workflow WHERE owner_id = :ownerId AND deleted_at IS NULL",
                new MapSqlParameterSource("ownerId", ownerId), (rs, row) -> new BuilderTotals(
                        rs.getLong("workflows"), rs.getLong("published"), rs.getLong("runs")));
    }

    private static String filterClauses(AdminWorkflowFilter filter) {
        StringBuilder clauses = new StringBuilder();
        if (filter.status() != null) {
            clauses.append(" AND w.status = :status");
        }
        if (filter.q() != null) {
            clauses.append(" AND w.name ILIKE :pattern");
        }
        return clauses.toString();
    }

    private static MapSqlParameterSource filterParams(AdminWorkflowFilter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (filter.status() != null) {
            params.addValue("status", filter.status());
        }
        if (filter.q() != null) {
            params.addValue("pattern", "%" + escapeLike(filter.q()) + "%");
        }
        return params;
    }

    /** Postgres' default LIKE escape is the backslash: {@code %}, {@code _} and {@code \} in a search match themselves. */
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static MapSqlParameterSource dayParams(Instant windowStart, ZoneId zone) {
        return new MapSqlParameterSource("zone", zone.getId()).addValue("windowStart", Timestamp.from(windowStart));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
