package com.flowcortex.user.audit;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How long admin audit rows keep their client address and user agent, and how often the sweep runs;
 * {@code application.yml} carries the defaults and env names.
 */
@Validated
@ConfigurationProperties("flowcortex.admin-audit")
public record AdminAuditRetentionProperties(@NotNull Duration originRetention, @NotNull Duration retentionInterval) {

    public AdminAuditRetentionProperties {
        requirePositive(originRetention, "origin-retention");
        requirePositive(retentionInterval, "retention-interval");
    }

    private static void requirePositive(Duration value, String name) {
        if (value != null && (value.isZero() || value.isNegative())) {
            throw new IllegalArgumentException("flowcortex.admin-audit." + name + " must be positive, was " + value);
        }
    }
}
