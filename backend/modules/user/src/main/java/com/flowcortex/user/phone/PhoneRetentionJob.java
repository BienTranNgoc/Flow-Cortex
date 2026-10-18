package com.flowcortex.user.phone;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the phone tables that grow with every request bounded: send attempts (they hold IPs) and
 * outbox messages (they hold numbers and codes). Runs in every instance; the deletes are idempotent.
 */
@Component
public class PhoneRetentionJob {

    private static final Logger LOG = LoggerFactory.getLogger(PhoneRetentionJob.class);

    private final JdbcTemplate jdbc;
    private final PhoneRetentionProperties properties;
    private final Clock clock;

    PhoneRetentionJob(JdbcTemplate jdbc, PhoneRetentionProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${flowcortex.phone-verification.retention.interval}")
    @Transactional
    public void purge() {
        Instant now = Instant.now(clock);
        int attempts = jdbc.update("DELETE FROM phone_send_attempt WHERE created_at < ?",
                Timestamp.from(now.minus(properties.sendAttempts())));
        int messages = jdbc.update("DELETE FROM sms_outbox WHERE created_at < ?",
                Timestamp.from(now.minus(properties.smsOutbox())));
        LOG.info("Phone retention: deleted {} send attempts and {} outbox messages", attempts, messages);
    }
}
