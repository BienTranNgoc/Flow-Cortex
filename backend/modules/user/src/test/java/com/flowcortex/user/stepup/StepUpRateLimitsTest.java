package com.flowcortex.user.stepup;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.shared.ratelimit.InMemoryRateLimitStore;
import com.flowcortex.shared.ratelimit.RateLimitDefinition;
import com.flowcortex.shared.ratelimit.RateLimitTelemetry;
import com.flowcortex.shared.ratelimit.RateLimiter;
import com.flowcortex.shared.ratelimit.RateLimiters;
import com.flowcortex.user.application.RateLimit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StepUpRateLimitsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-11T10:00:00Z"), ZoneOffset.UTC);

    private static RateLimiter limiter(RateLimitDefinition definition) {
        return new RateLimiters(List.of(definition), new InMemoryRateLimitStore(CLOCK),
                new RateLimitTelemetry(new SimpleMeterRegistry(), CLOCK)).named(StepUpRateLimits.STEP_UP_USER);
    }

    @Test
    void declaresTheUserLimitFromItsDefaults() {
        RateLimitDefinition definition = new StepUpRateLimits().stepUpUser(
                new StepUpProperties(new RateLimit(5, Duration.ofMinutes(1))));

        assertThat(definition.name()).isEqualTo("step-up-user");
        assertThat(definition.capacity()).isEqualTo(5);
        assertThat(definition.period()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void sixthAttemptInThePeriodWaitsAndAnotherUserIsUnaffected() {
        RateLimiter limiter = limiter(new StepUpRateLimits().stepUpUser(
                new StepUpProperties(new RateLimit(5, Duration.ofMinutes(1)))));
        String userU = UUID.randomUUID().toString();
        String userV = UUID.randomUUID().toString();

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(limiter.tryAcquire(userU)).isEmpty();
        }

        assertThat(limiter.tryAcquire(userU)).hasValueSatisfying(wait -> assertThat(wait).isPositive());
        assertThat(limiter.tryAcquire(userV)).isEmpty();
    }

    @Test
    void aConfiguredCapacityOfTwoRefusesTheThird() {
        RateLimiter limiter = limiter(new StepUpRateLimits().stepUpUser(
                new StepUpProperties(new RateLimit(2, Duration.ofMinutes(1)))));
        String user = UUID.randomUUID().toString();

        assertThat(limiter.tryAcquire(user)).isEmpty();
        assertThat(limiter.tryAcquire(user)).isEmpty();
        assertThat(limiter.tryAcquire(user)).isPresent();
    }
}
