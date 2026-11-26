package com.flowcortex.billing.entitlement;

import com.flowcortex.billing.entitlement.PlanPeriods.Period;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Decides which door a settled plan purchase takes onto the member's holding. Pure: the repository's writes
 * repeat the guards in their statements, this rule only says which write to attempt.
 */
final class GrantRule {

    static final String PENDING_PLAN_OCCUPIED = "pending_plan_occupied";

    private GrantRule() {
    }

    /** The member's holding as read under the member lock. */
    record Holding(String tierId, String cycle, String status, String mode, Instant startedAt, Instant renewsAt,
            UUID grantedFrom, Optional<UUID> pendingTransactionId) {
    }

    sealed interface Outcome permits Open, Move, RecordNext, Superseded, Refused {
    }

    /** The member has no holding: open one from this purchase. */
    record Open() implements Outcome {
    }

    /** The purchased period has begun: move the holding onto it. */
    record Move() implements Outcome {
    }

    /** The purchased period begins later: keep the held plan and record this purchase as the next one. */
    record RecordNext() implements Outcome {
    }

    /** The holding already reflects a payment that succeeded at or after this one: write nothing. */
    record Superseded() implements Outcome {
    }

    /** The purchase cannot be granted; nothing is written to the holding. */
    record Refused(String reason) implements Outcome {
    }

    /**
     * @param heldGrantedSucceededAt when the payment the holding was granted from succeeded; read only when there
     *         is a holding
     */
    static Outcome decide(Optional<Holding> holding, Period period, Instant settledAt, UUID transactionId,
            Instant heldGrantedSucceededAt) {
        if (holding.isEmpty()) {
            return new Open();
        }
        if (period.startsAt().isAfter(settledAt)) {
            return holding.get().pendingTransactionId().filter(pending -> !pending.equals(transactionId)).isPresent()
                    ? new Refused(PENDING_PLAN_OCCUPIED)
                    : new RecordNext();
        }
        return heldGrantedSucceededAt != null && !heldGrantedSucceededAt.isBefore(settledAt)
                ? new Superseded()
                : new Move();
    }
}
