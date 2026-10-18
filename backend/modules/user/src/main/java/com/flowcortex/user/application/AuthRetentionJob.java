package com.flowcortex.user.application;

import com.flowcortex.user.infrastructure.RefreshTokenRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the two ever-growing auth tables bounded: audit rows (which hold IPs and user agents)
 * older than the configured retention, and refresh-token rows that expired or were revoked. A
 * live, rotated-out token row stays until its session expires, because presenting it again is how
 * a stolen token is detected. Runs in every instance; the deletes are idempotent.
 */
@Component
public class AuthRetentionJob {

    private static final Logger LOG = LoggerFactory.getLogger(AuthRetentionJob.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuthRetentionProperties properties;
    private final Clock clock;

    AuthRetentionJob(NamedParameterJdbcTemplate jdbc, RefreshTokenRepository refreshTokenRepository,
            AuthRetentionProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.refreshTokenRepository = refreshTokenRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${flowcortex.auth.retention.interval}")
    @Transactional
    public void purge() {
        Instant now = Instant.now(clock);
        int events = jdbc.update("DELETE FROM auth_event WHERE occurred_at < :cutoff",
                new MapSqlParameterSource("cutoff", Timestamp.from(now.minus(properties.auditEvents()))));
        int tokens = refreshTokenRepository.deleteExpiredAndRevokedBefore(now);
        LOG.info("Auth retention: deleted {} audit events and {} refresh tokens", events, tokens);
    }
}
