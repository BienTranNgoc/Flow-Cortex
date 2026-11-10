package com.flowcortex.asset.application;

import com.flowcortex.asset.domain.RegistrationRow;
import com.flowcortex.asset.domain.RetryPlan;
import com.flowcortex.asset.infrastructure.AssetRegistrationStore;
import com.flowcortex.jobs.api.ClusterJob;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Takes up the registrations the live path left behind (a failed attempt waiting for its retry, a hand-off the
 * pool rejected) and deletes finished rows past their retention. Runs on one instance at a time through the
 * cluster runner; the row lease keeps a pass that overlaps a takeover or a live claim safe.
 */
@Component
public class AssetRegistrationSweep implements ClusterJob {

    static final int DUE_BATCH = 50;
    static final int CLEANUP_BATCH = 1000;
    /** No new row is started past this, so the last row of a pass is still inside the lease it was claimed with. */
    static final Duration PASS_BUDGET = RetryPlan.LEASE.minusMinutes(1);

    private static final Logger LOG = LoggerFactory.getLogger(AssetRegistrationSweep.class);

    private final AssetRegistrationStore queue;
    private final AssetRegistry registry;
    private final Duration interval;
    private final Clock clock;

    @Autowired
    AssetRegistrationSweep(AssetRegistrationStore queue, AssetRegistry registry,
            @Value("${flowcortex.asset.registration.sweep-interval}") Duration interval, Clock clock) {
        this.queue = queue;
        this.registry = registry;
        this.interval = interval;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "asset.registration-sweep";
    }

    @Override
    public Duration interval() {
        return interval;
    }

    /** Both stages always run; a stage that fails is rethrown after the other, so the runner records it. */
    @Override
    public void run() {
        RuntimeException failure = null;
        int claimed = 0;
        int deleted = 0;
        try {
            claimed = processDue();
        } catch (RuntimeException e) {
            failure = e;
        }
        try {
            deleted = queue.cleanup(CLEANUP_BATCH);
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        LOG.info("asset.sweep claimed={} deleted={}", claimed, deleted);
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Works through the claimed rows one by one and stops early, handing the rest back untried, when the object
     * store was unavailable for a row (it is down for the others too) or the pass budget is spent.
     */
    private int processDue() {
        Instant deadline = clock.instant().plus(PASS_BUDGET);
        List<RegistrationRow> due = queue.claimDue(DUE_BATCH);
        String stopReason = null;
        int processed = 0;
        while (processed < due.size() && stopReason == null) {
            if (!clock.instant().isBefore(deadline)) {
                stopReason = "budget_spent";
                break;
            }
            RegistrationRow row = due.get(processed++);
            try {
                if (registry.process(row)) {
                    stopReason = AssetRegistry.STORE_UNAVAILABLE;
                }
            } catch (RuntimeException e) {
                // The row keeps its lease and is taken again once it lapses.
                LOG.error("asset.sweep outcome=process_failed source={} runId={}", row.sourceType().column(),
                        row.sourceId(), e);
            }
        }
        if (processed < due.size()) {
            release(due.subList(processed, due.size()), processed, stopReason);
        }
        return due.size();
    }

    private void release(List<RegistrationRow> untried, int processed, String stopReason) {
        int released = 0;
        try {
            released = queue.release(untried);
        } catch (RuntimeException e) {
            // The leases lapse by themselves, so the rows are only delayed.
            LOG.error("asset.sweep outcome=release_failed untried={}", untried.size(), e);
        }
        LOG.warn("asset.sweep outcome=stopped reason={} processed={} untried={} released={}", stopReason, processed,
                untried.size(), released);
    }
}
