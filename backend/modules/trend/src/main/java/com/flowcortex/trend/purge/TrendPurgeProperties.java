package com.flowcortex.trend.purge;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How often the template purge runs, how long a deleted template waits before it is purged, and how
 * many templates one pass takes; {@code application.yml} carries the defaults and env names.
 * Grace must stay above the longest media URL lifetime plus the longest run that reads template media.
 */
@Validated
@ConfigurationProperties("flowcortex.trend.purge")
public record TrendPurgeProperties(@NotNull Duration interval, @NotNull Duration grace, int batchSize) {

    public TrendPurgeProperties {
        requirePositive(interval, "interval");
        requirePositive(grace, "grace");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("flowcortex.trend.purge.batch-size must be positive, was " + batchSize);
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value != null && (value.isZero() || value.isNegative())) {
            throw new IllegalArgumentException("flowcortex.trend.purge." + name + " must be positive, was " + value);
        }
    }
}
