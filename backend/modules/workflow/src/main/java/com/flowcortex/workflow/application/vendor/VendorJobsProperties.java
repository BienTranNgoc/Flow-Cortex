package com.flowcortex.workflow.application.vendor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The vendor-job ledger switches, bound from {@code flowcortex.vendor-jobs.*} ({@code
 * application.yml} carries the defaults and env names). With {@code resumeEnabled} off, dispatch
 * sends no attach candidates while submits keep being recorded.
 */
@Validated
@ConfigurationProperties("flowcortex.vendor-jobs")
public record VendorJobsProperties(boolean resumeEnabled, @NotNull @Valid Cleanup cleanup,
        @NotNull Duration retention) {

    public VendorJobsProperties {
        requirePositive("retention", retention);
    }

    /** The scheduled sweep of rows older than the retention. */
    public record Cleanup(boolean enabled, @NotNull Duration interval) {

        public Cleanup {
            requirePositive("cleanup.interval", interval);
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value != null && (value.isZero() || value.isNegative())) {
            throw new IllegalArgumentException("flowcortex.vendor-jobs." + name + " must be positive, was " + value);
        }
    }
}
