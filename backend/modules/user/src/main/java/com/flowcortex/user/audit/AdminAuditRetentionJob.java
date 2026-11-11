package com.flowcortex.user.audit;

import com.flowcortex.jobs.api.ClusterJob;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clears the client address and user agent of admin audit rows older than the configured retention.
 * Rows stay. Runs on one instance at a time through the cluster runner; the update is idempotent, so
 * a late finish after a lease takeover changes nothing.
 */
@Component
@EnableConfigurationProperties(AdminAuditRetentionProperties.class)
public class AdminAuditRetentionJob implements ClusterJob {

    private static final Logger LOG = LoggerFactory.getLogger(AdminAuditRetentionJob.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final AdminAuditRetentionProperties properties;
    private final Clock clock;

    AdminAuditRetentionJob(NamedParameterJdbcTemplate jdbc, AdminAuditRetentionProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "user.admin-audit-origin-retention";
    }

    @Override
    public Duration interval() {
        return properties.retentionInterval();
    }

    @Override
    @Transactional
    public void run() {
        Instant cutoff = Instant.now(clock).minus(properties.originRetention());
        int cleared = jdbc.update("UPDATE admin_audit_event SET ip = NULL, user_agent = NULL"
                + " WHERE occurred_at < :cutoff AND (ip IS NOT NULL OR user_agent IS NOT NULL)",
                new MapSqlParameterSource("cutoff", Timestamp.from(cutoff)));
        LOG.info("Admin audit retention: cleared the client origin of {} rows", cleared);
    }
}
