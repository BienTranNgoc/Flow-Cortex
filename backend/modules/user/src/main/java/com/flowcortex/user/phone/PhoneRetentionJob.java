package com.flowcortex.user.phone;

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
 * Keeps the phone tables that grow with every request bounded: send attempts (they hold IPs) and
 * outbox messages (they hold numbers and codes). Runs on one instance at a time through the cluster
 * runner; the deletes stay idempotent.
 */
@Component
public class PhoneRetentionJob implements ClusterJob {

    private static final Logger LOG = LoggerFactory.getLogger(PhoneRetentionJob.class);

    private final JdbcTemplate jdbc;
    private final PhoneRetentionProperties properties;
    private final Clock clock;
    private final Duration interval;

    @Autowired
    PhoneRetentionJob(JdbcTemplate jdbc, PhoneRetentionProperties properties, Clock clock,
            @Value("${flowcortex.phone-verification.retention.interval}") Duration interval) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
        this.interval = interval;
    }

    @Override
    public String name() {
        return "user.phone-retention";
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
        int attempts = jdbc.update("DELETE FROM phone_send_attempt WHERE created_at < ?",
                Timestamp.from(now.minus(properties.sendAttempts())));
        int messages = jdbc.update("DELETE FROM sms_outbox WHERE created_at < ?",
                Timestamp.from(now.minus(properties.smsOutbox())));
        LOG.info("Phone retention: deleted {} send attempts and {} outbox messages", attempts, messages);
    }
}
