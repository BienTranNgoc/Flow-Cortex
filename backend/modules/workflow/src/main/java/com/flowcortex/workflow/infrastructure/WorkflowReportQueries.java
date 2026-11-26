package com.flowcortex.workflow.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The staff report queue: reports joined to the workflow they name, deleted workflows included. Native SQL so the
 * page, its count and the name filter run as plain statements; names and emails of the people involved are not
 * here, they come from the user module.
 */
@Repository
public class WorkflowReportQueries {

    /** A report with the fields of its workflow; {@code reviewerId}, {@code resolution} and {@code reviewedAt} are null while pending. */
    public record ReportRow(
            UUID id, String reason, String details, String status, String resolution, UUID reviewerId,
            Instant reviewedAt, String internalNote, Instant createdAt, UUID reporterId, UUID workflowId,
            String workflowName, String workflowStatus, String workflowVisibility, String moderationStatus,
            boolean workflowDeleted, UUID ownerId) {
    }

    private static final String SELECT = """
            SELECT r.id, r.reason, r.details, r.status, r.resolution, r.reviewer_id, r.reviewed_at, r.internal_note,
                   r.created_at, r.reporter_id, w.id AS workflow_id, w.name AS workflow_name,
                   w.status AS workflow_status, w.visibility AS workflow_visibility, w.moderation_status,
                   (w.deleted_at IS NOT NULL) AS workflow_deleted, w.owner_id
            FROM workflow_report r JOIN workflow w ON w.id = r.workflow_id
            """;

    private static final RowMapper<ReportRow> REPORT_ROW = (rs, row) -> new ReportRow(
            rs.getObject("id", UUID.class), rs.getString("reason"), rs.getString("details"), rs.getString("status"),
            rs.getString("resolution"), rs.getObject("reviewer_id", UUID.class), instant(rs, "reviewed_at"),
            rs.getString("internal_note"), instant(rs, "created_at"), rs.getObject("reporter_id", UUID.class),
            rs.getObject("workflow_id", UUID.class), rs.getString("workflow_name"),
            rs.getString("workflow_status"), rs.getString("workflow_visibility"), rs.getString("moderation_status"),
            rs.getBoolean("workflow_deleted"), rs.getObject("owner_id", UUID.class));

    private final NamedParameterJdbcTemplate jdbc;

    WorkflowReportQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Newest first; ties on the creation time break on the id. {@code status} and {@code q} are optional filters. */
    public List<ReportRow> page(String status, String q, int limit, long offset) {
        return jdbc.query(SELECT + "WHERE TRUE" + filterClauses(status, q)
                + " ORDER BY r.created_at DESC, r.id DESC LIMIT :limit OFFSET :offset",
                filterParams(status, q).addValue("limit", limit).addValue("offset", offset), REPORT_ROW);
    }

    public long count(String status, String q) {
        Long total = jdbc.queryForObject("SELECT count(*) FROM workflow_report r JOIN workflow w ON w.id = r.workflow_id "
                + "WHERE TRUE" + filterClauses(status, q), filterParams(status, q), Long.class);
        return total == null ? 0 : total;
    }

    public Optional<ReportRow> find(UUID id) {
        return jdbc.query(SELECT + "WHERE r.id = :id", new MapSqlParameterSource("id", id), REPORT_ROW).stream()
                .findFirst();
    }

    private static String filterClauses(String status, String q) {
        StringBuilder clauses = new StringBuilder();
        if (status != null) {
            clauses.append(" AND r.status = :status");
        }
        if (q != null) {
            clauses.append(" AND w.name ILIKE :pattern ESCAPE '\\'");
        }
        return clauses.toString();
    }

    private static MapSqlParameterSource filterParams(String status, String q) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (status != null) {
            params.addValue("status", status);
        }
        if (q != null) {
            params.addValue("pattern", "%" + escapeLike(q) + "%");
        }
        return params;
    }

    /** {@code %}, {@code _} and the escape character itself in a search match themselves. */
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
