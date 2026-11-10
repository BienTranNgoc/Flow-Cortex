package com.flowcortex.workflow.application.vendor;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes ledger rows older than the retention. Runs in every instance; the delete is idempotent,
 * so concurrent sweeps both succeed.
 */
@Component
@ConditionalOnProperty(prefix = "flowcortex.vendor-jobs.cleanup", name = "enabled", matchIfMissing = true)
public class VendorTaskCleanupJob {

    private static final Logger LOG = LoggerFactory.getLogger(VendorTaskCleanupJob.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final VendorJobsProperties properties;
    private final Clock clock;

    VendorTaskCleanupJob(NamedParameterJdbcTemplate jdbc, VendorJobsProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${flowcortex.vendor-jobs.cleanup.interval}")
    @Transactional
    public void sweep() {
        Instant cutoff = clock.instant().minus(properties.retention());
        int deleted = jdbc.update("DELETE FROM vendor_task WHERE created_at < :cutoff",
                new MapSqlParameterSource("cutoff", Timestamp.from(cutoff)));
        LOG.info("vendor_job outcome=sweep deleted={}", deleted);
    }
}
