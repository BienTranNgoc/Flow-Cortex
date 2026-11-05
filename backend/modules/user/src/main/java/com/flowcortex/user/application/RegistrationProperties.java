package com.flowcortex.user.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
        @NotNull @Valid RateLimit perIp,
        @NotNull @Valid RateLimit perEmail) {
}
