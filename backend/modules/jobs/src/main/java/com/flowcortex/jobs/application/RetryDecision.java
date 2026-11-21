package com.flowcortex.jobs.application;

import com.flowcortex.jobs.api.PublicationRetryPolicy;
import java.time.Instant;
import org.springframework.modulith.events.EventPublication;

/** What to do with a failed publication now: resubmit it, leave it for later, or give it up. */
public enum RetryDecision {

    DUE,
    WAIT,
    EXHAUSTED;

    /**
     * @param attempts          attempts made so far (1 after the first)
     * @param lastAttemptStart  when the latest attempt began
     */
    public static RetryDecision of(PublicationRetryPolicy policy, int attempts, Instant lastAttemptStart, Instant now) {
        if (attempts >= policy.maxAttempts()) {
            return EXHAUSTED;
        }
        return now.isBefore(lastAttemptStart.plus(policy.delayAfter(attempts))) ? WAIT : DUE;
    }

    /** Decides for a stored publication: the latest attempt began at its last resubmission, else at its publication. */
    public static RetryDecision of(PublicationRetryPolicy policy, EventPublication publication, Instant now) {
        Instant lastResubmission = publication.getLastResubmissionDate();
        Instant lastAttemptStart = lastResubmission != null ? lastResubmission : publication.getPublicationDate();
        return of(policy, publication.getCompletionAttempts(), lastAttemptStart, now);
    }
}
