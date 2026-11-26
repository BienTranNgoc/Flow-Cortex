package com.flowcortex.user.oauth;

import com.flowcortex.jobs.api.ClusterJob;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes spent authorization codes, clients that never got a token or whose tokens are all purged,
 * old rows of the token call log and ended idempotency reservations. Runs even with the server
 * switched off: rows written while it was on still age out. Runs on one instance at a time through
 * the cluster runner; the deletes are idempotent.
 */
@Component
public class McpOAuthRetentionJob implements ClusterJob {

    private static final Logger LOG = LoggerFactory.getLogger(McpOAuthRetentionJob.class);
    private static final Duration SPENT_CODE_GRACE = Duration.ofDays(1);
    private static final Duration UNUSED_CLIENT_AGE = Duration.ofDays(30);

    private final JdbcTemplate jdbc;
    private final McpOAuthProperties properties;
    private final Duration callRetention;
    private final Clock clock;

    @Autowired
    McpOAuthRetentionJob(JdbcTemplate jdbc, McpOAuthProperties properties, Clock clock,
            @Value("${flowcortex.auth.api-token-calls.retention}") Duration callRetention) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
        this.callRetention = callRetention;
    }

    @Override
    public String name() {
        return "user.mcp-oauth-retention";
    }

    @Override
    public Duration interval() {
        return properties.retentionInterval();
    }

    @Override
    @Transactional
    public void run() {
        Instant now = Instant.now(clock);
        Timestamp spentBefore = Timestamp.from(now.minus(SPENT_CODE_GRACE));
        int codes = jdbc.update("DELETE FROM oauth_authorization_code WHERE expires_at < ? OR redeemed_at < ?",
                spentBefore, spentBefore);
        int clients = jdbc.update("DELETE FROM oauth_client c WHERE c.created_at < ? AND NOT EXISTS"
                + " (SELECT 1 FROM oauth_issued_token t WHERE t.client_id = c.client_id)",
                Timestamp.from(now.minus(UNUSED_CLIENT_AGE)));
        int calls = jdbc.update("DELETE FROM api_token_call WHERE occurred_at < ?",
                Timestamp.from(now.minus(callRetention)));
        int reservations = jdbc.update("DELETE FROM api_token_idempotency WHERE expires_at < ?",
                Timestamp.from(now));
        LOG.info("MCP OAuth retention: deleted {} codes, {} clients, {} token call rows and {} idempotency rows",
                codes, clients, calls, reservations);
    }
}
