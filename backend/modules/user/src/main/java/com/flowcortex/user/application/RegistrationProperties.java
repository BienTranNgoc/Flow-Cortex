package com.flowcortex.user.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Self-registration controls, bound from {@code flowcortex.registration.*} ({@code application.yml}
 * carries the defaults and env names). A blank {@code ownerEmail} closes registration.
 */
@Validated
@ConfigurationProperties("flowcortex.registration")
public record RegistrationProperties(
        String ownerEmail,
        @NotNull @Valid Limit perIp,
        @NotNull @Valid Limit perEmail) {

    /** At most {@code capacity} attempts per {@code period}, refilled gradually. */
    public record Limit(@Positive int capacity, @NotNull Duration period) {

        public Limit {
            if (period != null && (period.isZero() || period.isNegative())) {
                throw new IllegalArgumentException("flowcortex.registration period must be positive, was " + period);
            }
        }
    }
}
