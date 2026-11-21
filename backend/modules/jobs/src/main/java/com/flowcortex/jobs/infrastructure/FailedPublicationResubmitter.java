package com.flowcortex.jobs.infrastructure;

import com.flowcortex.jobs.api.ClusterJob;
import com.flowcortex.jobs.api.PublicationRetryPolicy;
import com.flowcortex.jobs.application.PublicationRetryPolicies;
import com.flowcortex.jobs.application.RetryDecision;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.stereotype.Component;

/**
 * Resubmits every failed event publication of the application, not only media ones: a job the broker
 * refused goes to the broker again, and an after-commit listener that failed runs again. Outstanding
 * publications are never republished on restart (that would dispatch a job twice), so this is the only
 * retry. Each event type's {@link PublicationRetryPolicy} says when a publication is due and when it is
 * given up and deleted.
 *
 * <p>A pass reads up to {@value #READ_LIMIT} failed rows, because the registry's read has no order and
 * filters in memory: rows still waiting or already exhausted would otherwise fill a smaller read and hide a
 * due one. At most {@value #RESUBMIT_LIMIT} due rows are resubmitted per pass.
 */
@Component
public class FailedPublicationResubmitter implements ClusterJob {

    static final int READ_LIMIT = 1000;
    static final int RESUBMIT_LIMIT = 100;

    private static final Logger LOG = LoggerFactory.getLogger(FailedPublicationResubmitter.class);

    private final FailedEventPublications failed;
    private final EventPublicationRepository publications;
    private final PublicationRetryPolicies policies;
    private final Clock clock;

    @Autowired
    FailedPublicationResubmitter(FailedEventPublications failed, EventPublicationRepository publications,
            PublicationRetryPolicies policies) {
        this(failed, publications, policies, Clock.systemUTC());
    }

    FailedPublicationResubmitter(FailedEventPublications failed, EventPublicationRepository publications,
            PublicationRetryPolicies policies, Clock clock) {
        this.failed = failed;
        this.publications = publications;
        this.policies = policies;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "jobs.publication-resubmitter";
    }

    @Override
    public Duration interval() {
        return Duration.ofSeconds(60);
    }

    @Override
    public void run() {
        Instant now = clock.instant();
        AtomicInteger accepted = new AtomicInteger();
        Map<UUID, Exhausted> exhausted = new LinkedHashMap<>();

        failed.resubmit(ResubmissionOptions.defaults()
                .withMinAge(Duration.ofMinutes(1))
                .withBatchSize(READ_LIMIT)
                .withFilter(publication -> {
                    PublicationRetryPolicy policy = policies.policyFor(publication.getEvent().getClass());
                    return switch (RetryDecision.of(policy, publication, now)) {
                        case DUE -> accepted.incrementAndGet() <= RESUBMIT_LIMIT;
                        case EXHAUSTED -> {
                            exhausted.put(publication.getIdentifier(), Exhausted.of(publication));
                            yield false;
                        }
                        case WAIT -> false;
                    };
                }));

        if (exhausted.isEmpty()) {
            return;
        }
        publications.deletePublications(List.copyOf(exhausted.keySet()));
        exhausted.forEach((id, row) -> LOG.warn("outbox outcome=gave_up publication={} event={} attempts={}",
                id, row.event(), row.attempts()));
    }

    private record Exhausted(String event, int attempts) {

        static Exhausted of(EventPublication publication) {
            return new Exhausted(publication.getEvent().getClass().getSimpleName(),
                    publication.getCompletionAttempts());
        }
    }
}
