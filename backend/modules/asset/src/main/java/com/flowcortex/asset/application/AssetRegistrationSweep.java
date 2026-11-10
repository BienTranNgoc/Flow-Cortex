package com.flowcortex.asset.application;

import com.flowcortex.asset.domain.RegistrationRow;
import com.flowcortex.asset.infrastructure.AssetRegistrationStore;
import com.flowcortex.jobs.api.ClusterJob;
import java.time.Duration;
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

    private static final Logger LOG = LoggerFactory.getLogger(AssetRegistrationSweep.class);

    private final AssetRegistrationStore queue;
    private final AssetRegistry registry;
    private final Duration interval;

    @Autowired
    AssetRegistrationSweep(AssetRegistrationStore queue, AssetRegistry registry,
            @Value("${flowcortex.asset.registration.sweep-interval}") Duration interval) {
        this.queue = queue;
        this.registry = registry;
        this.interval = interval;
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

    private int processDue() {
        List<RegistrationRow> due = queue.claimDue(DUE_BATCH);
        for (RegistrationRow row : due) {
            try {
                registry.process(row);
            } catch (RuntimeException e) {
                // The row keeps its lease and is taken again once it lapses.
                LOG.error("asset.sweep outcome=process_failed source={} runId={}", row.sourceType().column(),
                        row.sourceId(), e);
            }
        }
        return due.size();
    }
}
