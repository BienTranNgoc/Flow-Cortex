package com.flowcortex.billing.entitlement;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code flowcortex.billing.entitlement.activation.*}: whether the next-cycle activation job runs, the wait
 * between passes and how many holdings one pass takes. A batch size outside 1-1000 refuses startup.
 */
@ConfigurationProperties("flowcortex.billing.entitlement.activation")
public record EntitlementActivationProperties(Boolean enabled, Duration interval, Integer batchSize) {

    static final int DEFAULT_BATCH_SIZE = 100;
    static final int MAX_BATCH_SIZE = 1000;

    public EntitlementActivationProperties {
        enabled = enabled == null || enabled;
        interval = interval == null ? Duration.ofMinutes(5) : interval;
        batchSize = batchSize == null ? DEFAULT_BATCH_SIZE : batchSize;
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("flowcortex.billing.entitlement.activation.batch-size must be from 1 to "
                    + MAX_BATCH_SIZE + ": " + batchSize);
        }
    }
}
