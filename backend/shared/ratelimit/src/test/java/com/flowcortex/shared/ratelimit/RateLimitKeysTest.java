package com.flowcortex.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RateLimitKeysTest {

    private static final Duration PERIOD = Duration.ofHours(1);

    @Test
    void theKeyIsTheLimiterNameAndASha256Digest() {
        RateLimitDefinition definition = new RateLimitDefinition("registration-email", 3, PERIOD);

        assertThat(RateLimitKeys.storageKey(definition, "an@example.com"))
                .matches("^registration-email:[0-9a-f]{64}$")
                .doesNotContain("@");
    }

    @Test
    void theSameIdentityUnderAnotherAllowanceGetsAnotherKey() {
        RateLimitDefinition three = new RateLimitDefinition("registration-ip", 3, PERIOD);
        RateLimitDefinition five = new RateLimitDefinition("registration-ip", 5, PERIOD);
        RateLimitDefinition longer = new RateLimitDefinition("registration-ip", 3, Duration.ofHours(2));

        assertThat(RateLimitKeys.storageKey(three, "203.0.113.7"))
                .isEqualTo(RateLimitKeys.storageKey(three, "203.0.113.7"))
                .isNotEqualTo(RateLimitKeys.storageKey(five, "203.0.113.7"))
                .isNotEqualTo(RateLimitKeys.storageKey(longer, "203.0.113.7"))
                .isNotEqualTo(RateLimitKeys.storageKey(three, "203.0.113.8"));
    }

    @Test
    void theLogIdentityIsTheFirst12HexCharactersOfTheDigest() {
        RateLimitDefinition definition = new RateLimitDefinition("password-reset-email", 3, PERIOD);

        String logIdentity = RateLimitKeys.logIdentity(definition, "an@example.com");

        assertThat(logIdentity).matches("^[0-9a-f]{12}$");
        assertThat(RateLimitKeys.storageKey(definition, "an@example.com"))
                .startsWith("password-reset-email:" + logIdentity);
    }
}
