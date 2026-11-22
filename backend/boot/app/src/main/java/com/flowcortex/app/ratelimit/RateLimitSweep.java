package com.flowcortex.app.ratelimit;

import com.flowcortex.shared.ratelimit.PostgresRateLimitStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes buckets that have refilled to capacity. Instances sweeping at the same time are harmless:
 * the delete only touches rows already past their expiry.
 */
@Component
class RateLimitSweep {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitSweep.class);
    static final int BATCH_SIZE = 1000;
    static final int CONTINUE_ABOVE = 50;

    private final PostgresRateLimitStore store;

    RateLimitSweep(PostgresRateLimitStore store) {
        this.store = store;
    }

    @Scheduled(fixedDelayString = "${flowcortex.rate-limit.sweep-interval:PT10M}")
    void sweep() {
        int removed = 0;
        int batch;
        do {
            batch = store.removeExpired(BATCH_SIZE);
            removed += batch;
        } while (batch > CONTINUE_ABOVE);
        LOG.info("rate_limit_sweep removed={}", removed);
    }
}
