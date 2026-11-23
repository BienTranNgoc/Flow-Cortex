package com.flowcortex.workflow.application;

import com.flowcortex.workflow.domain.ReviewTaskStatus;
import java.time.Instant;

/**
 * What to do with a decision that arrives for a review task, as one ordered table where "decided"
 * means {@code approved} or {@code rejected} (never {@code expired}):
 *
 * <ol>
 *   <li>decided, same key and same kind as recorded: {@link Verdict#REPLAY}
 *   <li>decided otherwise: {@link Verdict#CONFLICT}
 *   <li>{@code expired}, or pending at or after {@code expiresAt}: {@link Verdict#EXPIRED}
 *   <li>pending: {@link Verdict#APPLY}
 * </ol>
 *
 * Pure: the caller loads the task and the clock and acts on the verdict.
 */
final class ReviewDecisionReplay {

    private ReviewDecisionReplay() {}

    enum Kind {
        APPROVE,
        ANSWERS,
        REJECT
    }

    enum Verdict {
        APPLY,
        REPLAY,
        CONFLICT,
        EXPIRED
    }

    /**
     * @param recordedKind what decided the task ({@link #recordedKind}), unused while undecided
     * @param decisionKey the key stored with the decision, or null
     * @param requestKey the trimmed {@code Idempotency-Key} of this request, or null when none was sent
     */
    static Verdict judge(ReviewTaskStatus status, Kind recordedKind, String decisionKey, Kind requestKind,
            String requestKey, Instant now, Instant expiresAt) {
        if (status == ReviewTaskStatus.APPROVED || status == ReviewTaskStatus.REJECTED) {
            boolean sameDecision = requestKey != null && requestKey.equals(decisionKey) && requestKind == recordedKind;
            return sameDecision ? Verdict.REPLAY : Verdict.CONFLICT;
        }
        if (status == ReviewTaskStatus.EXPIRED || !now.isBefore(expiresAt)) {
            return Verdict.EXPIRED;
        }
        return Verdict.APPLY;
    }

    /** The kind of decision a task's stored status and answers record; null while it is undecided or expired. */
    static Kind recordedKind(ReviewTaskStatus status, String storedAnswers) {
        return switch (status) {
            case APPROVED -> storedAnswers == null ? Kind.APPROVE : Kind.ANSWERS;
            case REJECTED -> Kind.REJECT;
            case PENDING, EXPIRED -> null;
        };
    }
}
