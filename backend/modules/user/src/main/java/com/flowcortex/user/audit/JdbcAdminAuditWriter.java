package com.flowcortex.user.audit;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Inserts one {@code admin_audit_event} row in its own transaction. The actor's email and name are
 * snapshotted best-effort, and any failure is logged and swallowed so recording never fails the
 * admin's request.
 */
@Component
public class JdbcAdminAuditWriter {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcAdminAuditWriter.class);
    private static final String ORIGIN_HTTP = "http";

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public JdbcAdminAuditWriter(NamedParameterJdbcTemplate jdbc, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void write(AdminAuditEntry entry) {
        try {
            Actor actor = lookupActor(entry.actorUserId());
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("actor_user_id", entry.actorUserId())
                    .addValue("actor_email", actor.email())
                    .addValue("actor_name", actor.name())
                    .addValue("action", entry.action())
                    .addValue("resource_type", entry.resourceType())
                    .addValue("resource_id", entry.resourceId())
                    .addValue("resource_label", entry.resourceLabel())
                    .addValue("change_summary",
                            entry.changeSummary() == null ? null : jsonMapper.writeValueAsString(entry.changeSummary()))
                    .addValue("status", entry.status().column())
                    .addValue("error_code", entry.errorCode())
                    .addValue("ip", entry.ip())
                    .addValue("user_agent", entry.userAgent())
                    .addValue("origin_source", ORIGIN_HTTP)
                    .addValue("occurred_at", Timestamp.from(Instant.now(clock)));
            transaction.executeWithoutResult(status -> jdbc.update("""
                    INSERT INTO admin_audit_event (id, actor_user_id, actor_email, actor_name, action,
                        resource_type, resource_id, resource_label, change_summary, status, error_code,
                        ip, user_agent, origin_source, occurred_at)
                    VALUES (:id, :actor_user_id, :actor_email, :actor_name, :action,
                        :resource_type, :resource_id, :resource_label, CAST(:change_summary AS jsonb),
                        :status, :error_code, :ip, :user_agent, :origin_source, :occurred_at)
                    """, params));
        } catch (RuntimeException ex) {
            LOG.error("Admin audit row for {} on {} was not recorded", entry.action(), entry.resourceId(), ex);
        }
    }

    private Actor lookupActor(UUID actorUserId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT email, name FROM app_user WHERE id = :id", Map.of("id", actorUserId));
            if (rows == null || rows.isEmpty()) {
                return Actor.UNKNOWN;
            }
            Map<String, Object> row = rows.get(0);
            return new Actor((String) row.get("email"), (String) row.get("name"));
        } catch (RuntimeException ex) {
            LOG.warn("Admin audit actor snapshot for {} failed; the row is written without it", actorUserId, ex);
            return Actor.UNKNOWN;
        }
    }

    private record Actor(String email, String name) {
        static final Actor UNKNOWN = new Actor(null, null);
    }
}
