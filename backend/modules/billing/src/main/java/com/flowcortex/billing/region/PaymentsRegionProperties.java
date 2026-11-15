package com.flowcortex.billing.region;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code flowcortex.billing.region-gate.*}: the gate mode (parsed tolerantly, see
 * {@link PaymentsRegionGateMode#parse}) and the pace of the registry refresh. Read once at startup.
 */
@ConfigurationProperties("flowcortex.billing.region-gate")
public record PaymentsRegionProperties(String mode, Duration refreshInterval, Duration refreshInitialDelay) {

    public PaymentsRegionProperties {
        refreshInterval = refreshInterval == null ? Duration.ofHours(24) : refreshInterval;
        refreshInitialDelay = refreshInitialDelay == null ? Duration.ofMinutes(10) : refreshInitialDelay;
    }
}
