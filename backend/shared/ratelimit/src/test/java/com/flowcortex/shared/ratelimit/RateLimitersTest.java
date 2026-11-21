package com.flowcortex.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class RateLimitersTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T08:00:00Z"), ZoneOffset.UTC);

    private static RateLimiters registry(RateLimitDefinition... definitions) {
        return new RateLimiters(List.of(definitions), new InMemoryRateLimitStore(CLOCK),
                new RateLimitTelemetry(new SimpleMeterRegistry(), CLOCK));
    }

    @Test
    void aNameDeclaredTwiceFailsAndNamesTheLimiter() {
        RateLimitDefinition first = new RateLimitDefinition("registration-ip", 3, Duration.ofMinutes(15));
        RateLimitDefinition second = new RateLimitDefinition("registration-ip", 5, Duration.ofHours(1));

        assertThatThrownBy(() -> registry(first, second))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("registration-ip");
    }

    @Test
    void aMalformedNameIsRefused() {
        Duration period = Duration.ofMinutes(1);

        assertThatThrownBy(() -> new RateLimitDefinition("Bad_Name", 1, period))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Bad_Name");
        assertThatThrownBy(() -> new RateLimitDefinition("1abc", 1, period))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1abc");
        String tooLong = "a".repeat(41);
        assertThatThrownBy(() -> new RateLimitDefinition(tooLong, 1, period))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(tooLong);
        assertThat(new RateLimitDefinition("a".repeat(40), 1, period).name()).hasSize(40);
    }

    @Test
    void aNonPositiveAllowanceIsRefused() {
        assertThatThrownBy(() -> new RateLimitDefinition("zero-capacity", 0, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("zero-capacity");
        assertThatThrownBy(() -> new RateLimitDefinition("zero-period", 1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("zero-period");
    }

    @Test
    void aLookupOfAnUndeclaredNameFailsAndNamesIt() {
        RateLimiters limiters = registry(new RateLimitDefinition("registration-ip", 3, Duration.ofMinutes(15)));

        assertThat(limiters.named("registration-ip").definition().capacity()).isEqualTo(3);
        assertThatThrownBy(() -> limiters.named("registraton-ip"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("registraton-ip");
    }

    @Test
    void aLimiterAdmitsItsCapacityThenRefusesWithOneUnitsRefill() {
        RateLimiter limiter = registry(new RateLimitDefinition("registration-ip", 3, Duration.ofMinutes(15)))
                .named("registration-ip");

        assertThat(limiter.tryAcquire("203.0.113.7")).isEmpty();
        assertThat(limiter.tryAcquire("203.0.113.7")).isEmpty();
        assertThat(limiter.tryAcquire("203.0.113.7")).isEmpty();
        assertThat(limiter.tryAcquire("203.0.113.7")).contains(Duration.ofMinutes(5));
        assertThat(limiter.tryAcquire("203.0.113.8")).isEmpty();
    }

    @Test
    void estimatingNeverSpends() {
        RateLimiter limiter = registry(new RateLimitDefinition("one-shot", 1, Duration.ofMinutes(1)))
                .named("one-shot");

        assertThat(limiter.estimate("203.0.113.7")).isEmpty();
        assertThat(limiter.estimate("203.0.113.7")).isEmpty();
        assertThat(limiter.tryAcquire("203.0.113.7")).isEmpty();
        assertThat(limiter.estimate("203.0.113.7")).isPresent();
    }

    @Test
    void spendingUsesTheBudgetWithoutAskingForAnAnswer() {
        RateLimiter limiter = registry(new RateLimitDefinition("failures", 2, Duration.ofMinutes(1)))
                .named("failures");

        limiter.spend("203.0.113.7");
        limiter.spend("203.0.113.7");
        limiter.spend("203.0.113.7");

        assertThat(limiter.estimate("203.0.113.7")).isPresent();
        assertThat(limiter.estimate("203.0.113.8")).isEmpty();
    }
}
