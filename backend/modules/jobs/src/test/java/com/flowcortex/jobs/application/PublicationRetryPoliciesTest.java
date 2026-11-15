package com.flowcortex.jobs.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.jobs.api.PublicationRetryPolicy;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** One policy per event type, with the default for a type that has none. */
class PublicationRetryPoliciesTest {

    private static PublicationRetryPolicy policy(Class<?> type, int maxAttempts) {
        return new PublicationRetryPolicy() {
            @Override
            public Class<?> eventType() {
                return type;
            }

            @Override
            public int maxAttempts() {
                return maxAttempts;
            }

            @Override
            public Duration delayAfter(int attempts) {
                return Duration.ofMinutes(1);
            }
        };
    }

    @Test
    void twoPoliciesForOneEventTypeRefuseToStartAndTheMessageNamesTheType() {
        List<PublicationRetryPolicy> policies = List.of(policy(String.class, 6), policy(String.class, 3));

        assertThatThrownBy(() -> new PublicationRetryPolicies(policies))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("java.lang.String");
    }

    @Test
    void anEventTypeWithoutAPolicyGetsTheDefaultOfTenAttemptsAndOneMinute() {
        PublicationRetryPolicy own = policy(Integer.class, 6);
        PublicationRetryPolicies policies = new PublicationRetryPolicies(List.of(own));

        assertThat(policies.policyFor(Integer.class)).isSameAs(own);
        PublicationRetryPolicy fallback = policies.policyFor(Long.class);
        assertThat(fallback.maxAttempts()).isEqualTo(10);
        assertThat(fallback.delayAfter(3)).isEqualTo(Duration.ofMinutes(1));
    }
}
