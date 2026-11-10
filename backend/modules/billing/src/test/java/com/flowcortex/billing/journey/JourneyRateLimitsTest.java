package com.flowcortex.billing.journey;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.shared.ratelimit.InMemoryRateLimitStore;
import com.flowcortex.shared.ratelimit.RateLimitDefinition;
import com.flowcortex.shared.ratelimit.RateLimitTelemetry;
import com.flowcortex.shared.ratelimit.RateLimiter;
import com.flowcortex.shared.ratelimit.RateLimiters;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

class JourneyRateLimitsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T08:00:00Z"), ZoneOffset.UTC);

    private static RateLimiter limiter() {
        RateLimitDefinition definition = new JourneyRateLimits().paymentJourneyMemberLimit();
        return new RateLimiters(List.of(definition), new InMemoryRateLimitStore(CLOCK),
                new RateLimitTelemetry(new SimpleMeterRegistry(), CLOCK)).named(JourneyRateLimits.NAME);
    }

    @Test
    void theDeclaredLimitIsThirtyPerMinuteUnderItsName() {
        RateLimitDefinition definition = new JourneyRateLimits().paymentJourneyMemberLimit();

        assertThat(definition.name()).isEqualTo("payment-journey-member");
        assertThat(definition.capacity()).isEqualTo(30);
        assertThat(definition.period()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void theThirtyFirstRequestInAMinuteIsRefusedWithAtLeastOneSecondToWait() {
        RateLimiter limiter = limiter();
        for (int i = 0; i < 30; i++) {
            assertThat(limiter.tryAcquire("member-a")).as("request %d", i + 1).isEmpty();
        }

        Optional<Duration> wait = limiter.tryAcquire("member-a");

        assertThat(wait).isPresent();
        assertThat(JourneyRateLimitedException.of(wait.get()).getRetryAfterSeconds()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void anotherMemberKeepsItsOwnBudget() {
        RateLimiter limiter = limiter();
        for (int i = 0; i < 31; i++) {
            limiter.tryAcquire("member-a");
        }

        assertThat(limiter.tryAcquire("member-b")).isEmpty();
    }

    @Test
    void theWaitIsRoundedUpToWholeSecondsAndNeverBelowOne() {
        assertThat(JourneyRateLimitedException.of(Duration.ofMillis(1)).getRetryAfterSeconds()).isEqualTo(1);
        assertThat(JourneyRateLimitedException.of(Duration.ofMillis(1200)).getRetryAfterSeconds()).isEqualTo(2);
        assertThat(JourneyRateLimitedException.of(Duration.ZERO).getRetryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void theAnswerIsA429WithRetryAfterAndItsMessageKey() {
        JourneyRateLimitedException e = JourneyRateLimitedException.of(Duration.ofMillis(1200));

        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(e.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
        assertThat(e.getDetailMessageCode()).isEqualTo("billing.journeyRateLimited");
    }
}
