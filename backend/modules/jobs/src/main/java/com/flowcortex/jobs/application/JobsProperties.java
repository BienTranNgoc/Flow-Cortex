package com.flowcortex.jobs.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bound from {@code flowcortex.jobs.*}. {@code pollInterval} is how often a pass looks for due jobs (the
 * runner's {@code @Scheduled} reads the same key); with {@code runner.enabled} off the runner bean does
 * not exist, so the instance registers and runs nothing.
 */
@ConfigurationProperties("flowcortex.jobs")
record JobsProperties(@DefaultValue("5s") Duration pollInterval, @DefaultValue Runner runner) {

    JobsProperties {
        if (pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("flowcortex.jobs.poll-interval must be positive, got " + pollInterval);
        }
    }

    record Runner(@DefaultValue("true") boolean enabled) {
    }
}
