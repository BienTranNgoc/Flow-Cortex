package com.flowcortex.user.pat;

import com.flowcortex.user.application.RateLimit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Per-token request budgets, bound from {@code flowcortex.auth.personal-access-tokens.per-minute|per-day}. */
@Validated
@ConfigurationProperties("flowcortex.auth.personal-access-tokens")
record PatRateLimitProperties(@NotNull @Valid RateLimit perMinute, @NotNull @Valid RateLimit perDay) {
}
