package com.flowcortex.billing.ledger;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code flowcortex.billing.ledger.apply.*}: whether the scheduled applier runs, how long it waits between
 * passes and how many accepted callbacks one pass takes. A batch size outside 1-1000 refuses startup.
 */
@ConfigurationProperties("flowcortex.billing.ledger.apply")
public record LedgerApplyProperties(Boolean enabled, Duration interval, Integer batchSize) {

    static final int DEFAULT_BATCH_SIZE = 100;
    static final int MAX_BATCH_SIZE = 1000;

    public LedgerApplyProperties {
        enabled = enabled == null || enabled;
        interval = interval == null ? Duration.ofSeconds(5) : interval;
        batchSize = batchSize == null ? DEFAULT_BATCH_SIZE : batchSize;
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "flowcortex.billing.ledger.apply.batch-size must be from 1 to " + MAX_BATCH_SIZE + ": " + batchSize);
        }
    }
}
