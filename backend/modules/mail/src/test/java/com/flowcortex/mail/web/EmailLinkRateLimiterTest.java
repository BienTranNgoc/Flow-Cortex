package com.flowcortex.mail.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.mail.application.MailProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class EmailLinkRateLimiterTest {

    private final EmailLinkRateLimiter limiter =
            new EmailLinkRateLimiter(new MailProperties.RateLimit(10, Duration.ofSeconds(60)));

    @Test
    void tenCallsAreAdmittedAndTheEleventhIsRefusedWithARetryAfter() {
        for (int call = 0; call < 10; call++) {
            assertThat(limiter.tryAcquire("token-hash", "203.0.113.7")).isEmpty();
        }

        assertThat(limiter.tryAcquire("token-hash", "203.0.113.7")).hasValueSatisfying(retryAfter ->
                assertThat(retryAfter).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(60)));
    }

    @Test
    void anotherTokenFromTheSameAddressIsAdmitted() {
        for (int call = 0; call < 11; call++) {
            limiter.tryAcquire("token-hash", "203.0.113.7");
        }

        assertThat(limiter.tryAcquire("other-token-hash", "203.0.113.7")).isEmpty();
        assertThat(limiter.tryAcquire("token-hash", "203.0.113.8")).isEmpty();
    }
}
