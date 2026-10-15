package com.flowcortex.user.application;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How long auth audit rows are kept, bound from {@code flowcortex.auth.retention.*} ({@code
 * application.yml} carries the defaults and env names).
 */
@Validated
@ConfigurationProperties("flowcortex.auth.retention")
public record AuthRetentionProperties(@NotNull Duration auditEvents) {

    public AuthRetentionProperties {
        if (auditEvents != null && (auditEvents.isZero() || auditEvents.isNegative())) {
            throw new IllegalArgumentException("flowcortex.auth.retention.audit-events must be positive, was "
                    + auditEvents);
        }
    }
}
