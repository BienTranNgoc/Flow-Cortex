package com.flowcortex.billing.refund;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * The change-of-mind verdict of one payment: a pure rule over the facts its caller read. The first matching
 * refusal wins, in the order of {@link Refusal}; the deadline and the days left are answered even when the
 * right is refused, so a member who missed the window is told which one.
 */
final class ChangeOfMind {

    static final int MONTHLY_PLAN_WINDOW_DAYS = 3;
    static final int YEARLY_PLAN_WINDOW_DAYS = 7;
    static final int TOPUP_WINDOW_DAYS = 3;

    private static final String PLAN_PURCHASE = "plan_purchase";
    private static final String YEARLY_SUFFIX = ":yearly";
    private static final long SECONDS_PER_DAY = 86_400;

    private ChangeOfMind() {
    }

    enum Verdict {
        PERMITTED, REFUSED;

        String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The refusals in the order they are checked; the wire names are {@link #value()}. */
    enum Refusal {
        ALREADY_REFUNDED, NOT_SETTLED, PREVIOUSLY_REJECTED, WINDOW_ELAPSED, TURN_LIMIT_REACHED;

        String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * @param status the payment's status as stored
     * @param paidAt when the money was collected, null before
     * @param itemRef a plan purchase's {@code <tier>:<cycle>} reference
     * @param hasRejectedRequest whether a request on this payment was ever rejected
     * @param hasApprovedRequest whether a request on this payment is approved or auto-approved
     */
    record Facts(UUID transactionId, long amountVnd, String currency, String kind, String status, Instant paidAt,
            String itemRef, boolean hasRejectedRequest, boolean hasApprovedRequest, TurnStanding turns,
            Instant now) {
    }

    record Right(UUID transactionId, long amountVnd, String currency, String kind, String cycle, int windowDays,
            Instant deadlineAt, int daysRemaining, Verdict verdict, Refusal refusal) {
    }

    static Right judge(Facts facts) {
        boolean plan = PLAN_PURCHASE.equals(facts.kind());
        boolean yearly = plan && facts.itemRef().endsWith(YEARLY_SUFFIX);
        int windowDays = yearly ? YEARLY_PLAN_WINDOW_DAYS : plan ? MONTHLY_PLAN_WINDOW_DAYS : TOPUP_WINDOW_DAYS;
        Instant deadline = facts.paidAt() == null ? null : facts.paidAt().plus(Duration.ofDays(windowDays));
        Refusal refusal = refusal(facts, deadline);
        return new Right(facts.transactionId(), facts.amountVnd(), facts.currency(), facts.kind(),
                yearly ? "yearly" : "monthly", windowDays, deadline, daysRemaining(deadline, facts.now()),
                refusal == null ? Verdict.PERMITTED : Verdict.REFUSED, refusal);
    }

    private static Refusal refusal(Facts facts, Instant deadline) {
        String status = facts.status();
        if ("partially_refunded".equals(status) || "refunded".equals(status) || facts.hasApprovedRequest()) {
            return Refusal.ALREADY_REFUNDED;
        }
        if (!"succeeded".equals(status) || deadline == null) {
            return Refusal.NOT_SETTLED;
        }
        if (facts.hasRejectedRequest()) {
            return Refusal.PREVIOUSLY_REJECTED;
        }
        if (facts.now().isAfter(deadline)) {
            return Refusal.WINDOW_ELAPSED;
        }
        if (!facts.turns().free()) {
            return Refusal.TURN_LIMIT_REACHED;
        }
        return null;
    }

    /** {@code max(0, ceil((deadline - now) / 24 h))}; 0 without a deadline. */
    private static int daysRemaining(Instant deadline, Instant now) {
        if (deadline == null || !deadline.isAfter(now)) {
            return 0;
        }
        Duration left = Duration.between(now, deadline);
        long wholeDays = left.getSeconds() / SECONDS_PER_DAY;
        boolean partial = left.getSeconds() % SECONDS_PER_DAY != 0 || left.getNano() != 0;
        return (int) (wholeDays + (partial ? 1 : 0));
    }
}
