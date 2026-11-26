package com.flowcortex.support.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bound from {@code flowcortex.support.sla}: the configured layer of the five SLA settings and the pace of the
 * alert sweep. A warning must be below its limit, or startup fails naming both keys; a console write cannot be
 * refused on that ground, so the SLA level reads a warning at or above its limit as "never due soon".
 */
@Validated
@ConfigurationProperties("flowcortex.support.sla")
public record SupportSlaProperties(
        boolean alertsEnabled,
        @Min(1) @Max(720) int newHours,
        @Min(1) @Max(720) int newWarnHours,
        @Min(1) @Max(90) int inProgressDays,
        @Min(1) @Max(90) int inProgressWarnDays,
        @NotNull Duration sweepInterval) {

    public SupportSlaProperties {
        if (newWarnHours >= newHours) {
            throw new IllegalArgumentException("support.sla.new_warn_hours (" + newWarnHours
                    + ") must be below support.sla.new_hours (" + newHours + ")");
        }
        if (inProgressWarnDays >= inProgressDays) {
            throw new IllegalArgumentException("support.sla.in_progress_warn_days (" + inProgressWarnDays
                    + ") must be below support.sla.in_progress_days (" + inProgressDays + ")");
        }
    }
}
