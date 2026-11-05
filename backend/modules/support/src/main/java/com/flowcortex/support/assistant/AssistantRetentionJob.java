package com.flowcortex.support.assistant;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes assistant conversations 90 days after their last message (their messages cascade, a request
 * that linked one keeps a null link) and quota rows older than two days. Runs in every instance; the
 * deletes are idempotent.
 */
@Component
public class AssistantRetentionJob {

    static final Duration CONVERSATION_RETENTION = Duration.ofDays(90);
    static final int QUOTA_DAYS_KEPT = 2;

    private static final Logger LOG = LoggerFactory.getLogger(AssistantRetentionJob.class);

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    AssistantRetentionJob(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${flowcortex.support.retention.interval:PT1H}")
    @Transactional
    public void purge() {
        int conversations = jdbcTemplate.update("DELETE FROM support_ai_conversation WHERE last_message_at < ?",
                Timestamp.from(clock.instant().minus(CONVERSATION_RETENTION)));
        LocalDate oldestKept = LocalDate.now(clock.withZone(QuotaService.DAY_ZONE)).minusDays(QUOTA_DAYS_KEPT);
        int quotaRows = jdbcTemplate.update("DELETE FROM support_quota_usage WHERE day < ?", oldestKept);
        LOG.info("support.retention outcome=purged conversations={} quota_rows={}", conversations, quotaRows);
    }
}
