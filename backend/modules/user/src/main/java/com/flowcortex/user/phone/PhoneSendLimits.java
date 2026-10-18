package com.flowcortex.user.phone;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Send-code quotas, bound from {@code flowcortex.phone-verification.limits.*} ({@code
 * application.yml} carries the defaults and env names). Daily figures are over the last 24 hours.
 */
@Validated
@ConfigurationProperties("flowcortex.phone-verification.limits")
public record PhoneSendLimits(
        @NotEmpty List<@Pattern(regexp = "\\d{1,3}") String> allowedCountryCodes,
        @Positive int userSendsPerDay,
        @Positive int userNumbersPerDay,
        @NotNull @Valid IpLimit perIp,
        @Positive int numberSendsPerDay,
        @Positive int globalSendsPerDay) {

    public PhoneSendLimits {
        allowedCountryCodes = allowedCountryCodes == null ? null : List.copyOf(allowedCountryCodes);
    }

    /** At most {@code capacity} requests per client IP per {@code period}, refilled gradually. */
    public record IpLimit(@Positive int capacity, @NotNull Duration period) {

        public IpLimit {
            if (period != null && (period.isZero() || period.isNegative())) {
                throw new IllegalArgumentException(
                        "flowcortex.phone-verification.limits.per-ip.period must be positive, was " + period);
            }
        }
    }
}
