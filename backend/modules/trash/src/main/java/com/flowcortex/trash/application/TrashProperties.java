package com.flowcortex.trash.application;

import com.flowcortex.trash.domain.TrashLimits;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Startup settings of the Trash. An out-of-range value fails the context naming its property, instead of being
 * clamped, so a bad deploy is caught at start.
 *
 * @param enabled when false the routes answer 503 and the sweep and reminder passes do nothing; recording still works
 * @param retention how long after its delete an entry waits before the sweep purges it, whole days from 1 to 365
 * @param reminderLead how long before expiry a reminding entry is reported, from 1 day to the retention minus 1 day
 */
@ConfigurationProperties("flowcortex.trash")
public record TrashProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("P30D") Duration retention,
        @DefaultValue("P3D") Duration reminderLead,
        @DefaultValue("PT1H") Duration sweepInterval,
        @DefaultValue("PT1H") Duration reminderInterval) {

    public TrashProperties {
        TrashLimits.checkRetention(retention).ifPresent(message -> {
            throw new IllegalArgumentException(message);
        });
        TrashLimits.checkLead(reminderLead, retention).ifPresent(message -> {
            throw new IllegalArgumentException(message);
        });
        requirePositive("flowcortex.trash.sweep-interval", sweepInterval);
        requirePositive("flowcortex.trash.reminder-interval", reminderInterval);
    }

    private static void requirePositive(String property, Duration value) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(property + " must be longer than zero, was " + value);
        }
    }
}
