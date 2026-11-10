package com.flowcortex.user.application;

import com.flowcortex.jobs.api.ClusterJob;
import com.flowcortex.user.infrastructure.RefreshTokenRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the ever-growing auth tables bounded: audit rows (which hold IPs and user agents) older
 * than the configured retention, reset-link rows that were used or expired, and refresh-token rows
 * that expired or were revoked. A live, rotated-out refresh-token row stays until its session
 * expires, because presenting it again is how a stolen token is detected. Runs on one instance at a time through
 * the cluster runner; the deletes stay idempotent.
 */
@Component
public class AuthRetentionJob implements ClusterJob {

    private static final Logger LOG = LoggerFactory.getLogger(AuthRetentionJob.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuthRetentionProperties properties;
    private final Clock clock;
    private final Duration interval;

    @Autowired
    AuthRetentionJob(NamedParameterJdbcTemplate jdbc, RefreshTokenRepository refreshTokenRepository,
            AuthRetentionProperties properties, Clock clock,
            @Value("${flowcortex.auth.retention.interval}") Duration interval) {
        this.jdbc = jdbc;
        this.refreshTokenRepository = refreshTokenRepository;
        this.properties = properties;
        this.clock = clock;
        this.interval = interval;
    }

    AuthRetentionJob(NamedParameterJdbcTemplate jdbc, RefreshTokenRepository refreshTokenRepository,
            AuthRetentionProperties properties, Clock clock) {
        this(jdbc, refreshTokenRepository, properties, clock, Duration.ofHours(1));
    }

    @Override
    public String name() {
        return "user.auth-retention";
    }

    @Override
    public Duration interval() {
        return interval;
    }

    /** Transactional here too: a call from {@code run()} to {@code purge()} bypasses the proxy. */
    @Override
    @Transactional
    public void run() {
        purge();
    }

    @Transactional
    public void purge() {
        Instant now = Instant.now(clock);
        int events = jdbc.update("DELETE FROM auth_event WHERE occurred_at < :cutoff",
                new MapSqlParameterSource("cutoff", Timestamp.from(now.minus(properties.auditEvents()))));
        int tokens = refreshTokenRepository.deleteExpiredAndRevokedBefore(now);
        int resetLinks = jdbc.update("DELETE FROM email_confirmation_token WHERE purpose = 'PASSWORD_RESET'"
                + " AND (used_at < :now OR expires_at < :now)", new MapSqlParameterSource("now", Timestamp.from(now)));
        LOG.info("Auth retention: deleted {} audit events, {} refresh tokens and {} reset links", events, tokens,
                resetLinks);
    }
}
