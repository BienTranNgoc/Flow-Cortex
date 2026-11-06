package com.flowcortex.user.passwordreset;

import com.flowcortex.user.application.RateLimit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Password reset by emailed link, bound from {@code flowcortex.auth.password-reset.*} ({@code
 * application.yml} carries the defaults and env names). {@code validity} bounds the reset link;
 * {@code perIp}/{@code perEmail} limit reset requests, {@code submitPerIp} limits new-password submissions.
 */
@Validated
@ConfigurationProperties("flowcortex.auth.password-reset")
public record PasswordResetProperties(
        boolean enabled,
        @NotNull @DefaultValue("1h") Duration validity,
        @NotNull @Valid RateLimit perIp,
        @NotNull @Valid RateLimit perEmail,
        @NotNull @Valid RateLimit submitPerIp) {

    public PasswordResetProperties {
        if (validity != null && (validity.isZero() || validity.isNegative())) {
            throw new IllegalArgumentException("flowcortex.auth.password-reset.validity must be positive, was "
                    + validity);
        }
    }
}
