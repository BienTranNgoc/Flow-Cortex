package com.flowcortex.jobs.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowcortex.jobs.api.PublicationRetryPolicy;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.EventPublication;

/** Whether a failed publication is resubmitted now, later or never, from its attempts and last start. */
class RetryDecisionTest {

    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");

    private static final PublicationRetryPolicy DEFAULT = new DefaultPublicationRetryPolicy();

    private static final PublicationRetryPolicy GROWING = new PublicationRetryPolicy() {
        private final int[] minutes = {1, 5, 15, 60, 240};

        @Override
        public Class<?> eventType() {
            return String.class;
        }

        @Override
        public int maxAttempts() {
            return 6;
        }

        @Override
        public Duration delayAfter(int attempts) {
            return Duration.ofMinutes(minutes[attempts - 1]);
        }
    };

    @Test
    void aPublicationWithNineAttemptsThatFailedTwoMinutesAgoIsDueUnderTheDefault() {
        assertThat(RetryDecision.of(DEFAULT, 9, NOW.minus(Duration.ofMinutes(2)), NOW)).isEqualTo(RetryDecision.DUE);
    }

    @Test
    void aPublicationWithTenAttemptsIsExhaustedUnderTheDefault() {
        assertThat(RetryDecision.of(DEFAULT, 10, NOW.minus(Duration.ofHours(1)), NOW))
                .isEqualTo(RetryDecision.EXHAUSTED);
    }

    @Test
    void aPublicationThatFailedThirtySecondsAgoWaitsUnderTheDefault() {
        assertThat(RetryDecision.of(DEFAULT, 1, NOW.minus(Duration.ofSeconds(30)), NOW)).isEqualTo(RetryDecision.WAIT);
    }

    @Test
    void eachDelayOfAGrowingPolicyWaitsUntilItIsReachedAndThenIsDue() {
        long[] minutes = {1, 5, 15, 60, 240};
        for (int attempts = 1; attempts <= 5; attempts++) {
            Duration delay = Duration.ofMinutes(minutes[attempts - 1]);
            assertThat(RetryDecision.of(GROWING, attempts, NOW.minus(delay).plusSeconds(1), NOW))
                    .as("attempt %d one second early", attempts).isEqualTo(RetryDecision.WAIT);
            assertThat(RetryDecision.of(GROWING, attempts, NOW.minus(delay), NOW))
                    .as("attempt %d at the delay", attempts).isEqualTo(RetryDecision.DUE);
        }
    }

    @Test
    void theLastResubmissionIsTheReferenceWhenSetAndThePublicationDateWhenNull() {
        EventPublication neverResubmitted = publication(1, NOW.minus(Duration.ofMinutes(2)), null);
        EventPublication justResubmitted = publication(2, NOW.minus(Duration.ofHours(1)), NOW.minus(Duration.ofSeconds(10)));

        assertThat(RetryDecision.of(DEFAULT, neverResubmitted, NOW)).isEqualTo(RetryDecision.DUE);
        assertThat(RetryDecision.of(DEFAULT, justResubmitted, NOW)).isEqualTo(RetryDecision.WAIT);
    }

    private static EventPublication publication(int attempts, Instant published, Instant lastResubmission) {
        EventPublication publication = mock(EventPublication.class);
        when(publication.getCompletionAttempts()).thenReturn(attempts);
        when(publication.getPublicationDate()).thenReturn(published);
        when(publication.getLastResubmissionDate()).thenReturn(lastResubmission);
        return publication;
    }

    @Test
    void theSixthAttemptOfAGrowingPolicyIsExhausted() {
        assertThat(RetryDecision.of(GROWING, 6, NOW.minus(Duration.ofDays(1)), NOW)).isEqualTo(RetryDecision.EXHAUSTED);
    }
}
