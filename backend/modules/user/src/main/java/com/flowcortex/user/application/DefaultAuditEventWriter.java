package com.flowcortex.user.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
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
 * Default {@link AuditEventWriter}: a {@code NamedParameterJdbcTemplate} insert into {@code
 * auth_event}. The insert commits in its own transaction, so a row survives the rollback of the
 * request that wrote it, and a failure to write is logged and swallowed — an audit outage never
 * fails a login. The template (not an annotation) owns the transaction so a failing commit is
 * caught here too.
 */
@Component
public class DefaultAuditEventWriter implements AuditEventWriter {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultAuditEventWriter.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public DefaultAuditEventWriter(NamedParameterJdbcTemplate jdbc, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void write(AuthEventType type, UUID userId, String ip, String userAgent, Map<String, Object> metadata) {
        try {
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("user_id", userId)
                    .addValue("type", type.name())
                    .addValue("ip", ip)
                    .addValue("user_agent", userAgent)
                    .addValue("occurred_at", Timestamp.from(Instant.now(clock)))
                    .addValue("metadata", serialize(metadata));
            transaction.executeWithoutResult(status -> jdbc.update("""
                    INSERT INTO auth_event (id, user_id, type, ip, user_agent, occurred_at, metadata)
                    VALUES (:id, :user_id, :type, :ip, :user_agent, :occurred_at, CAST(:metadata AS jsonb))
                    """, params));
        } catch (RuntimeException ex) {
            LOG.warn("Auth audit event {} was not recorded", type, ex);
        }
    }

    private String serialize(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        return jsonMapper.writeValueAsString(metadata);
    }
}
