package com.flowcortex.user.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Per-IP budget of failed password logins, bound from {@code flowcortex.login.*} ({@code
 * application.yml} carries the defaults and env names).
 */
@Validated
@ConfigurationProperties("flowcortex.login")
public record LoginThrottleProperties(@NotNull @Valid RegistrationProperties.Limit failuresPerIp) {
}
