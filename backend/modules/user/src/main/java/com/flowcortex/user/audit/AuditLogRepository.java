package com.flowcortex.user.audit;

import com.flowcortex.user.pii.PiiMask;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads {@code admin_audit_event} newest first by {@code (occurred_at, id)}. Every filter shape leads
 * with its equality columns, so an index of the audit viewer migration supplies the order. The search
 * predicate repeats the trigram index expression byte for byte: any change to one needs the other.
 */
@Repository
class AuditLogRepository {

    private static final String RESOURCE_TYPE_USER = "user";
    private static final String SEARCH_EXPRESSION = """
            lower(coalesce(actor_email, '') || ' ' || coalesce(actor_name, '') || ' '
                || coalesce(resource_id, '') || ' ' || coalesce(resource_label, ''))""";
    private static final String COLUMNS = """
            id, occurred_at, actor_user_id, actor_email, actor_name, action, resource_type, resource_id,
            resource_label, change_summary::text AS change_summary, status, error_code, ip, user_agent,
            origin_source""";

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper mapper = JsonMapper.builder().build();

    AuditLogRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One stored row. {@code label()} is the label as shown to any caller: a member's is masked. */
    record Row(UUID id, Instant occurredAt, UUID actorUserId, String actorEmail, String actorName, String action,
            String resourceType, String resourceId, String resourceLabel, JsonNode changeSummary, String status,
            String errorCode, String ip, String userAgent, String origin) {

        String label() {
            return RESOURCE_TYPE_USER.equals(resourceType) ? PiiMask.label(resourceLabel) : resourceLabel;
        }
    }

    /** Up to {@code limit} rows strictly after {@code after} in the list order. */
    List<Row> page(AuditLogFilter filter, AuditLogCursor after, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        List<String> conditions = conditions(filter, params);
        if (after != null) {
            conditions.add("(occurred_at, id) < (:cursorAt, :cursorId)");
            params.addValue("cursorAt", Timestamp.from(after.at()));
            params.addValue("cursorId", after.id());
        }
        params.addValue("limit", limit);
        return jdbc.query("SELECT " + COLUMNS + " FROM admin_audit_event" + where(conditions)
                + " ORDER BY occurred_at DESC, id DESC LIMIT :limit", params, this::row);
    }

    long count(AuditLogFilter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        Long count = jdbc.queryForObject("SELECT count(*) FROM admin_audit_event" + where(conditions(filter, params)),
                params, Long.class);
        return count == null ? 0 : count;
    }

    /** Hands every matching row to {@code sink}, newest first. */
    void stream(AuditLogFilter filter, Consumer<Row> sink) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        RowCallbackHandler handler = rs -> sink.accept(row(rs, 0));
        jdbc.query("SELECT " + COLUMNS + " FROM admin_audit_event" + where(conditions(filter, params))
                + " ORDER BY occurred_at DESC, id DESC", params, handler);
    }

    private static List<String> conditions(AuditLogFilter filter, MapSqlParameterSource params) {
        List<String> conditions = new ArrayList<>();
        if (filter.pattern() != null) {
            conditions.add(SEARCH_EXPRESSION + " LIKE :pattern ESCAPE '\\'");
            params.addValue("pattern", filter.pattern());
        }
        if (filter.action() != null) {
            conditions.add("action = :action");
            params.addValue("action", filter.action());
        }
        if (filter.resourceType() != null) {
            conditions.add("resource_type = :resourceType");
            params.addValue("resourceType", filter.resourceType());
        }
        if (filter.resourceId() != null) {
            conditions.add("resource_id = :resourceId");
            params.addValue("resourceId", filter.resourceId());
        }
        if (filter.actorUserId() != null) {
            conditions.add("actor_user_id = :actorUserId");
            params.addValue("actorUserId", filter.actorUserId());
        }
        if (filter.status() != null) {
            conditions.add("status = :status");
            params.addValue("status", filter.status());
        }
        if (filter.from() != null) {
            conditions.add("occurred_at >= :from");
            params.addValue("from", Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            conditions.add("occurred_at < :to");
            params.addValue("to", Timestamp.from(filter.to()));
        }
        return conditions;
    }

    private static String where(List<String> conditions) {
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    private Row row(ResultSet rs, int rowNumber) throws SQLException {
        String summary = rs.getString("change_summary");
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getObject("actor_user_id", UUID.class),
                rs.getString("actor_email"),
                rs.getString("actor_name"),
                rs.getString("action"),
                rs.getString("resource_type"),
                rs.getString("resource_id"),
                rs.getString("resource_label"),
                summary == null ? null : mapper.readTree(summary),
                rs.getString("status"),
                rs.getString("error_code"),
                rs.getString("ip"),
                rs.getString("user_agent"),
                rs.getString("origin_source"));
    }
}
