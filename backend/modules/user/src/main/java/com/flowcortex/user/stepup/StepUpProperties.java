package com.flowcortex.user.stepup;

import com.flowcortex.user.application.RateLimit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Step-up limits bound from {@code flowcortex.auth.step-up.*} ({@code application.yml} carries the
 * defaults and env names). {@code perUser} caps password attempts per signed-in user.
 */
@Validated
@ConfigurationProperties("flowcortex.auth.step-up")
public record StepUpProperties(@NotNull @Valid RateLimit perUser) {
}
