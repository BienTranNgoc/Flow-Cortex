package com.flowcortex.user.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link AuditEventWriter} backed by a {@code NamedParameterJdbcTemplate}
 * insert into {@code auth_event} (created by B1's
 * {@code V2026.10.15.001__auth_lockout_and_audit.sql}). Uses a fresh
 * transaction so an audit row is recorded even when the business
 * transaction rolls back.
 */
@Component
public class DefaultAuditEventWriter implements AuditEventWriter {

    private final NamedParameterJdbcTemplate jdbc;

    private final Clock clock;

    private final ObjectMapper objectMapper;

    public DefaultAuditEventWriter(NamedParameterJdbcTemplate jdbc, Clock clock, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(AuthEventType type, UUID userId, String ip, String userAgent, Map<String, Object> metadata) {
        String metadataJson = serialize(metadata);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("user_id", userId)
                .addValue("type", type.name())
                .addValue("ip", ip)
                .addValue("user_agent", userAgent)
                .addValue("occurred_at", java.sql.Timestamp.from(java.time.Instant.now(clock)))
                .addValue("metadata", metadataJson);
        jdbc.update("""
                INSERT INTO auth_event (id, user_id, type, ip, user_agent, occurred_at, metadata)
                VALUES (:id, :user_id, :type, :ip, :user_agent, :occurred_at, CAST(:metadata AS jsonb))
                """, params);
    }

    private String serialize(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize audit metadata", ex);
        }
    }
}
