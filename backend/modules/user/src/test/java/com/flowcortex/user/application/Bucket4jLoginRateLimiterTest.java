package com.flowcortex.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

class Bucket4jLoginRateLimiterTest {

    private final Bucket4jLoginRateLimiter limiter = new Bucket4jLoginRateLimiter(
            new LoginThrottleProperties(new RegistrationProperties.Limit(3, Duration.ofMinutes(15))));

    @Test
    void anIpWithNoFailuresIsAlwaysWithinTheLimit() {
        assertThatCode(() -> limiter.requireWithinLimit("203.0.113.1")).doesNotThrowAnyException();
    }

    @Test
    void onlyRecordedFailuresSpendTheBudgetAndOnlyForThatIp() {
        for (int failure = 1; failure <= 3; failure++) {
            limiter.requireWithinLimit("203.0.113.1");
            limiter.recordFailure("203.0.113.1");
        }

        assertThatThrownBy(() -> limiter.requireWithinLimit("203.0.113.1"))
                .isInstanceOfSatisfying(LoginRateLimitedException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        assertThatCode(() -> limiter.requireWithinLimit("203.0.113.2")).doesNotThrowAnyException();
    }

    @Test
    void retryAfterIsTheWaitForOneTokenInWholeSeconds() {
        for (int failure = 1; failure <= 3; failure++) {
            limiter.recordFailure("203.0.113.3");
        }

        assertThatThrownBy(() -> limiter.requireWithinLimit("203.0.113.3"))
                .isInstanceOfSatisfying(LoginRateLimitedException.class, e -> {
                    // 3 per 15 minutes refills one attempt every 5 minutes.
                    assertThat(e.getRetryAfterSeconds()).isBetween(299L, 300L);
                    assertThat(e.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo(
                            Long.toString(e.getRetryAfterSeconds()));
                });
    }
}
