package com.flowcortex.support.assistant;

import com.flowcortex.jobs.api.ClusterJob;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes assistant conversations 90 days after their last message (their messages cascade, a request
 * that linked one keeps a null link) and quota rows older than two days. Runs on one instance at a time
 * through the cluster runner; the deletes stay idempotent.
 */
@Component
public class AssistantRetentionJob implements ClusterJob {

    static final Duration CONVERSATION_RETENTION = Duration.ofDays(90);
    static final int QUOTA_DAYS_KEPT = 2;

    private static final Logger LOG = LoggerFactory.getLogger(AssistantRetentionJob.class);

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final Duration interval;

    AssistantRetentionJob(JdbcTemplate jdbcTemplate, Clock clock,
            @Value("${flowcortex.support.retention.interval:PT1H}") Duration interval) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.interval = interval;
    }

    @Override
    public String name() {
        return "support.assistant-retention";
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
        int conversations = jdbcTemplate.update("DELETE FROM support_ai_conversation WHERE last_message_at < ?",
                Timestamp.from(clock.instant().minus(CONVERSATION_RETENTION)));
        LocalDate oldestKept = LocalDate.now(clock.withZone(QuotaService.DAY_ZONE)).minusDays(QUOTA_DAYS_KEPT);
        int quotaRows = jdbcTemplate.update("DELETE FROM support_quota_usage WHERE day < ?", oldestKept);
        LOG.info("support.retention outcome=purged conversations={} quota_rows={}", conversations, quotaRows);
    }
}
