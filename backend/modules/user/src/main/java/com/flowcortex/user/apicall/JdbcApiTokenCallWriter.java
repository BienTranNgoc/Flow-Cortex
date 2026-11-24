package com.flowcortex.user.apicall;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Inserts one {@code api_token_call} row in its own transaction and reads the day's counts the cap
 * works from. A failed insert is logged with the method and route only and swallowed, so recording
 * never changes the caller's response.
 */
@Component
class JdbcApiTokenCallWriter {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcApiTokenCallWriter.class);

    /** What the caps count: the owner's rows since 00:00 UTC that ran the handler, and how many were workflow creates. */
    record DayCounts(long total, long workflowCreates) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    JdbcApiTokenCallWriter(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    void write(ApiTokenCall call) {
        try {
            transaction.executeWithoutResult(status -> jdbc.update("""
                    INSERT INTO api_token_call (id, user_id, token_id, method, route, outcome, http_status,
                        error_code, duration_ms, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), call.userId(), call.tokenId(), call.method(), call.route(),
                    call.outcome().column(), call.httpStatus(), call.errorCode(), (int) call.durationMs(),
                    Timestamp.from(call.occurredAt())));
        } catch (RuntimeException ex) {
            LOG.error("API token call row for {} {} was not recorded", call.method(), call.route(), ex);
        }
    }

    DayCounts countSince(UUID userId, Instant dayStart) {
        return jdbc.queryForObject("""
                SELECT count(*), count(*) FILTER (WHERE method = 'POST' AND route = ?)
                FROM api_token_call
                WHERE user_id = ? AND occurred_at >= ? AND outcome IN ('ok', 'error')
                """, (row, index) -> new DayCounts(row.getLong(1), row.getLong(2)), ApiTokenCallRoute.WORKFLOWS_PATH,
                userId, Timestamp.from(dayStart));
    }
}
