package com.flowcortex.media.infrastructure;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Resubmits every failed event publication of the application, not only media ones: a job the
 * broker refused goes to the broker again, and an after-commit listener that failed — the
 * workflow module's run advance, for one — runs again. Outstanding publications are never
 * republished on restart (that would dispatch a job twice), so this is the only retry. It gives
 * up after the tenth attempt; a step left behind is closed by its queue deadline.
 */
@Component
class FailedPublicationResubmitter {

    private static final ResubmissionOptions OPTIONS = ResubmissionOptions.defaults()
            .withMinAge(Duration.ofMinutes(1))
            .withBatchSize(100)
            .withFilter(publication -> publication.getCompletionAttempts() < 10);

    private final FailedEventPublications failed;

    FailedPublicationResubmitter(FailedEventPublications failed) {
        this.failed = failed;
    }

    @Scheduled(fixedDelay = 60, initialDelay = 60, timeUnit = TimeUnit.SECONDS)
    void resubmit() {
        failed.resubmit(OPTIONS);
    }
}
