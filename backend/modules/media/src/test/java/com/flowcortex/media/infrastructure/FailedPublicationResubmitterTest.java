package com.flowcortex.media.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;

/** Failed job publications are handed to the broker again: not too early, in bounded batches, a bounded number of times. */
class FailedPublicationResubmitterTest {

    private final FailedEventPublications failed = mock(FailedEventPublications.class);

    private ResubmissionOptions resubmitOnce() {
        new FailedPublicationResubmitter(failed).resubmit();
        ArgumentCaptor<ResubmissionOptions> options = ArgumentCaptor.forClass(ResubmissionOptions.class);
        verify(failed).resubmit(options.capture());
        return options.getValue();
    }

    private static EventPublication attempted(int times) {
        EventPublication publication = mock(EventPublication.class);
        when(publication.getCompletionAttempts()).thenReturn(times);
        return publication;
    }

    @Test
    void resubmitsPublicationsThatFailedAtLeastAMinuteAgoInBatchesOfAHundred() {
        ResubmissionOptions options = resubmitOnce();

        assertThat(options.getMinAge()).isEqualTo(Duration.ofMinutes(1));
        assertThat(options.getBatchSize()).isEqualTo(100);
    }

    @Test
    void stopsResubmittingAPublicationAfterItsTenthAttempt() {
        ResubmissionOptions options = resubmitOnce();

        assertThat(options.getFilter().test(attempted(9))).isTrue();
        assertThat(options.getFilter().test(attempted(10))).isFalse();
    }
}
