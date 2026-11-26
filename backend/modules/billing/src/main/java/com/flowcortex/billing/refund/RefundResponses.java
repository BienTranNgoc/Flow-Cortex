package com.flowcortex.billing.refund;

import com.flowcortex.billing.refund.ChangeOfMind.Right;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;

/** The answers of the member refund routes. Instants are ISO-8601 UTC. */
public final class RefundResponses {

    private RefundResponses() {
    }

    /** The change-of-mind verdict of one payment; the deadline and the days left are answered when refused too. */
    public record RightView(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID transactionId,
            @Schema(requiredMode = RequiredMode.REQUIRED) long amountVnd,
            @Schema(requiredMode = RequiredMode.REQUIRED) String currency,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"plan_purchase", "topup"}) String kind,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"monthly", "yearly"}) String cycle,
            @Schema(requiredMode = RequiredMode.REQUIRED) int windowDays,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) Instant deadlineAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) int daysRemaining,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"permitted", "refused"}) String verdict,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, allowableValues = {"already_refunded",
                "not_settled", "previously_rejected", "window_elapsed", "turn_limit_reached"}) String refusal) {

        static RightView of(Right right) {
            return new RightView(right.transactionId(), right.amountVnd(), right.currency(), right.kind(),
                    right.cycle(), right.windowDays(), right.deadlineAt(), right.daysRemaining(),
                    right.verdict().value(), right.refusal() == null ? null : right.refusal().value());
        }
    }

    /** Where the member stands against the turn limit; {@code nextTurnAt} is set only while no turn is free. */
    public record TurnsView(
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean enabled,
            @Schema(requiredMode = RequiredMode.REQUIRED) int used,
            @Schema(requiredMode = RequiredMode.REQUIRED) int limit,
            @Schema(requiredMode = RequiredMode.REQUIRED) int windowMonths,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) Instant nextTurnAt) {

        static TurnsView of(TurnStanding standing) {
            return new TurnsView(standing.enabled(), standing.used(), standing.limit(), standing.windowMonths(),
                    standing.nextTurnAt());
        }
    }

    /**
     * One request as the member reads it. {@code processingAt} is the decision time of an approved request and
     * {@code rejectedAt} with {@code rejectionReason} that of a rejected one.
     */
    public record RefundRequestView(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = RequiredMode.REQUIRED) String code,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"pending", "auto_approved", "approved",
                "rejected", "cancelled"}) String status,
            @Schema(requiredMode = RequiredMode.REQUIRED) String reason,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant requestedAt,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) Instant decidedAt,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) String decisionNote,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) Instant processingAt,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) Instant rejectedAt,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) String rejectionReason) {
    }

    /** The right over one of the member's payments, their turn standing, their latest request on it. */
    public record RefundEligibilityResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) RightView right,
            @Schema(requiredMode = RequiredMode.REQUIRED) TurnsView turns,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) Instant paidAt,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) Integer daysElapsed,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) RefundRequestView request,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean cancellable) {
    }

    /** The verdict of the member's latest plan purchase together with its latest request. */
    public record ChangeOfMindRightView(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID transactionId,
            @Schema(requiredMode = RequiredMode.REQUIRED) long amountVnd,
            @Schema(requiredMode = RequiredMode.REQUIRED) String currency,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"plan_purchase", "topup"}) String kind,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"monthly", "yearly"}) String cycle,
            @Schema(requiredMode = RequiredMode.REQUIRED) int windowDays,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) Instant deadlineAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) int daysRemaining,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"permitted", "refused"}) String verdict,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, allowableValues = {"already_refunded",
                "not_settled", "previously_rejected", "window_elapsed", "turn_limit_reached"}) String refusal,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) RefundRequestView request) {

        static ChangeOfMindRightView of(RightView right, RefundRequestView request) {
            return new ChangeOfMindRightView(right.transactionId(), right.amountVnd(), right.currency(),
                    right.kind(), right.cycle(), right.windowDays(), right.deadlineAt(), right.daysRemaining(),
                    right.verdict(), right.refusal(), request);
        }
    }

    /** What the plan-page offer is drawn from; {@code right} and {@code turns} are null with the surface off. */
    public record ChangeOfMindResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean surfaceEnabled,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean available,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) ChangeOfMindRightView right,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) TurnsView turns) {
    }

    /** The latest request on a payment; {@code request} is null when there is none. */
    public record LatestRefundRequestResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) RefundRequestView request) {
    }
}
