package com.flowcortex.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

class Bucket4jRegistrationRateLimiterTest {

    private final Bucket4jRegistrationRateLimiter limiter = new Bucket4jRegistrationRateLimiter(
            new RegistrationProperties(null,
                    new RegistrationProperties.Limit(3, Duration.ofHours(1)),
                    new RegistrationProperties.Limit(2, Duration.ofHours(1))));

    @Test
    void theFourthAttemptFromOneIpIsRefusedWhileAnotherIpIsNot() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            limiter.acquire("203.0.113.1", "user" + attempt + "@example.com");
        }

        assertThatThrownBy(() -> limiter.acquire("203.0.113.1", "user4@example.com"))
                .isInstanceOfSatisfying(RegistrationRateLimitedException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        assertThatCode(() -> limiter.acquire("203.0.113.2", "user5@example.com")).doesNotThrowAnyException();
    }

    @Test
    void theEmailLimitIgnoresLetterCaseAndSurroundingWhitespaceAcrossIps() {
        limiter.acquire("203.0.113.10", "Person@Example.com");
        limiter.acquire("203.0.113.11", "  person@example.COM ");

        assertThatThrownBy(() -> limiter.acquire("203.0.113.12", "PERSON@EXAMPLE.COM"))
                .isInstanceOf(RegistrationRateLimitedException.class);
        assertThatCode(() -> limiter.acquire("203.0.113.13", "other@example.com")).doesNotThrowAnyException();
    }

    @Test
    void anIpOverItsLimitDoesNotSpendTheEmailsBudget() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            limiter.acquire("203.0.113.20", "filler" + attempt + "@example.com");
        }
        for (int attempt = 1; attempt <= 2; attempt++) {
            assertThatThrownBy(() -> limiter.acquire("203.0.113.20", "victim@example.com"))
                    .isInstanceOf(RegistrationRateLimitedException.class);
        }

        limiter.acquire("203.0.113.21", "victim@example.com");
        limiter.acquire("203.0.113.22", "victim@example.com");
    }

    @Test
    void retryAfterIsTheWaitForOneTokenInWholeSeconds() {
        limiter.acquire("203.0.113.30", "wait@example.com");
        limiter.acquire("203.0.113.31", "wait@example.com");

        // 2 per hour refills one attempt every 30 minutes.
        assertThatThrownBy(() -> limiter.acquire("203.0.113.32", "wait@example.com"))
                .isInstanceOfSatisfying(RegistrationRateLimitedException.class, e -> {
                    assertThat(e.getRetryAfterSeconds()).isBetween(1790L, 1800L);
                    assertThat(e.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                            .isEqualTo(Long.toString(e.getRetryAfterSeconds()));
                });
    }

    @Test
    void retryAfterRoundsUpAndIsNeverZero() {
        assertThat(new RegistrationRateLimitedException(Duration.ofMillis(1500)).getRetryAfterSeconds()).isEqualTo(2);
        assertThat(new RegistrationRateLimitedException(Duration.ZERO).getRetryAfterSeconds()).isEqualTo(1);
    }
}
