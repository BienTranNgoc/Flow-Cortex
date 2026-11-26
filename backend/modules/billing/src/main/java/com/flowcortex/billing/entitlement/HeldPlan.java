package com.flowcortex.billing.entitlement;

import com.flowcortex.billing.entitlement.GrantRule.Holding;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The plan a member holds at an instant, derived from the stored holding. The raw columns outlive the period
 * they describe, so every reader goes through this rule instead of trusting them.
 *
 * <p>{@code tierId} is {@code free} when no paid plan is held, and then {@code cycle}, {@code mode},
 * {@code startsAt} and {@code renewsAt} are null; {@code next} is the recorded next-cycle plan while its
 * period has not begun.
 */
record HeldPlan(String tierId, boolean held, String cycle, String mode, Instant startsAt, Instant renewsAt,
        Optional<NextPlan> next) {

    static final String FREE = "free";

    /** The recorded next-cycle plan with the period its payment stored. */
    record NextPlan(String tierId, String cycle, Instant startsAt, Instant renewsAt) {
    }

    /**
     * In order: the next-cycle plan when its period has begun and not ended; the holding when it is active and
     * the instant is before its renewal plus the grace of its billing mode; otherwise free.
     */
    static HeldPlan of(Optional<Holding> holding, Optional<NextPlan> next, Instant now) {
        String mode = holding.map(Holding::mode).orElse("manual");
        Optional<NextPlan> notBegun = next.filter(plan -> plan.startsAt().isAfter(now));
        if (next.isPresent() && !next.get().startsAt().isAfter(now) && now.isBefore(next.get().renewsAt())) {
            NextPlan begun = next.get();
            return new HeldPlan(begun.tierId(), true, begun.cycle(), mode, begun.startsAt(), begun.renewsAt(),
                    Optional.empty());
        }
        if (holding.isPresent() && "active".equals(holding.get().status())
                && now.isBefore(holding.get().renewsAt().plus(Duration.ofDays(PlanPeriods.graceDays(mode))))) {
            Holding held = holding.get();
            return new HeldPlan(held.tierId(), true, held.cycle(), held.mode(), held.startedAt(), held.renewsAt(),
                    notBegun);
        }
        return new HeldPlan(FREE, false, null, null, null, null, notBegun);
    }
}
