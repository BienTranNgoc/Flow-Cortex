package com.flowcortex.user.pat;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bound from {@code flowcortex.auth.personal-access-tokens.*} ({@code application.yml} carries the
 * defaults and env names). {@code enabled} is the kill switch.
 */
@Validated
@ConfigurationProperties("flowcortex.auth.personal-access-tokens")
record PersonalAccessTokenProperties(boolean enabled, @Positive int maxLivePerUser, @NotNull Duration defaultLifetime) {

    PersonalAccessTokenProperties {
        if (defaultLifetime != null && (defaultLifetime.isZero() || defaultLifetime.isNegative())) {
            throw new IllegalArgumentException(
                    "flowcortex.auth.personal-access-tokens.default-lifetime must be positive, was " + defaultLifetime);
        }
    }
}
