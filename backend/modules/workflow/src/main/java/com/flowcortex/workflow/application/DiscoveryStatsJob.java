package com.flowcortex.workflow.application;

import com.flowcortex.jobs.api.ClusterJob;
import com.flowcortex.workflow.infrastructure.WorkflowDiscoveryStats;
import com.flowcortex.workflow.infrastructure.WorkflowDiscoveryStats.Counts;
import com.flowcortex.workflow.infrastructure.WorkflowDiscoveryStats.Row;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Refreshes the windowed figures behind the trending and popular orders: one pass reads every catalogue workflow's
 * runners, saves and members, stores them with the trending score, and drops the rows of workflows that left the
 * catalogue. Runs on one instance at a time through the cluster runner; the upsert and the delete are idempotent, so a
 * pass that overlaps a takeover is safe.
 */
@Component
class DiscoveryStatsJob implements ClusterJob {

    private static final Logger LOG = LoggerFactory.getLogger(DiscoveryStatsJob.class);

    private final WorkflowDiscoveryStats stats;
    private final Clock clock;
    private final Duration interval;

    DiscoveryStatsJob(WorkflowDiscoveryStats stats, Clock clock,
            @Value("${flowcortex.workflow.discovery.stats-interval-ms:600000}") long intervalMs) {
        this.stats = stats;
        this.clock = clock;
        this.interval = Duration.ofMillis(intervalMs);
    }

    @Override
    public String name() {
        return "workflow.discovery-stats";
    }

    @Override
    public Duration interval() {
        return interval;
    }

    @Override
    public void run() {
        Instant started = clock.instant();
        List<Counts> counted = stats.compute(started);
        List<Row> rows = counted.stream().map(c -> new Row(c.workflowId(), c.runners7d(), c.saves7d(),
                c.members30d(), DiscoveryScores.trending(c.runners7d(), c.saves7d()))).toList();
        stats.upsert(rows, started);
        int removed = stats.removeOutsideCatalogue();
        LOG.info("discovery stats: updated={} removed={} tookMs={}", rows.size(), removed,
                Duration.between(started, clock.instant()).toMillis());
    }
}
