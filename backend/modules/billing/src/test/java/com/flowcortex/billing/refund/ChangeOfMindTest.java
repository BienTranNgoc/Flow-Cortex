package com.flowcortex.billing.refund;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.billing.refund.ChangeOfMind.Facts;
import com.flowcortex.billing.refund.ChangeOfMind.Refusal;
import com.flowcortex.billing.refund.ChangeOfMind.Right;
import com.flowcortex.billing.refund.ChangeOfMind.Verdict;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChangeOfMindTest {

    private static final Instant PAID = Instant.parse("2026-12-01T10:00:00Z");
    private static final TurnStanding FREE = TurnStanding.of(List.of(), PAID, 2, 6, true);

    private static Facts plan(String itemRef, Instant now) {
        return facts("plan_purchase", itemRef, "succeeded", PAID, now);
    }

    private static Facts topup(Instant now) {
        return facts("topup", "pkg-50k", "succeeded", PAID, now);
    }

    private static Facts facts(String kind, String itemRef, String status, Instant paidAt, Instant now) {
        return new Facts(UUID.randomUUID(), 199_000, "VND", kind, status, paidAt, itemRef, false, false, FREE, now);
    }

    @Test
    void monthlyPlanInsideTheWindowIsPermitted() {
        Right right = ChangeOfMind.judge(plan("basic:monthly", Instant.parse("2026-12-02T09:00:00Z")));

        assertThat(right.verdict()).isEqualTo(Verdict.PERMITTED);
        assertThat(right.refusal()).isNull();
        assertThat(right.windowDays()).isEqualTo(3);
        assertThat(right.deadlineAt()).isEqualTo(Instant.parse("2026-12-04T10:00:00Z"));
        assertThat(right.daysRemaining()).isEqualTo(3);
        assertThat(right.cycle()).isEqualTo("monthly");
    }

    @Test
    void yearlyPlanHasASevenDayWindow() {
        Right right = ChangeOfMind.judge(plan("pro:yearly", Instant.parse("2026-12-07T10:00:00Z")));

        assertThat(right.windowDays()).isEqualTo(7);
        assertThat(right.daysRemaining()).isEqualTo(1);
        assertThat(right.verdict()).isEqualTo(Verdict.PERMITTED);
        assertThat(right.cycle()).isEqualTo("yearly");
    }

    @Test
    void theDeadlineInstantItselfIsInsideTheWindow() {
        Right right = ChangeOfMind.judge(topup(Instant.parse("2026-12-04T10:00:00Z")));

        assertThat(right.verdict()).isEqualTo(Verdict.PERMITTED);
        assertThat(right.daysRemaining()).isZero();
        assertThat(right.cycle()).isEqualTo("monthly");
        assertThat(right.windowDays()).isEqualTo(3);
    }

    @Test
    void oneMillisecondAfterTheDeadlineTheWindowHasElapsed() {
        Right right = ChangeOfMind.judge(topup(Instant.parse("2026-12-04T10:00:00.001Z")));

        assertThat(right.verdict()).isEqualTo(Verdict.REFUSED);
        assertThat(right.refusal()).isEqualTo(Refusal.WINDOW_ELAPSED);
        assertThat(right.deadlineAt()).isEqualTo(Instant.parse("2026-12-04T10:00:00Z"));
        assertThat(right.daysRemaining()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "failed", "expired", "disputed"})
    void aPaymentThatIsNotSettledIsRefused(String status) {
        Right right = ChangeOfMind.judge(facts("topup", "pkg-50k", status, PAID, PAID.plus(Duration.ofHours(1))));

        assertThat(right.refusal()).isEqualTo(Refusal.NOT_SETTLED);
    }

    @Test
    void aSucceededPaymentWithoutAPaidTimeIsNotSettledAndHasNoDeadline() {
        Right right = ChangeOfMind.judge(facts("topup", "pkg-50k", "succeeded", null, PAID));

        assertThat(right.refusal()).isEqualTo(Refusal.NOT_SETTLED);
        assertThat(right.deadlineAt()).isNull();
        assertThat(right.daysRemaining()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"refunded", "partially_refunded"})
    void aRefundedPaymentIsAlreadyRefundedWhateverTheWindowSays(String status) {
        Right right = ChangeOfMind.judge(facts("topup", "pkg-50k", status, PAID, PAID.plus(Duration.ofHours(1))));

        assertThat(right.refusal()).isEqualTo(Refusal.ALREADY_REFUNDED);
    }

    @Test
    void anApprovedRequestMeansTheMoneyIsAlreadyOnItsWay() {
        Facts inWindow = facts("plan_purchase", "basic:monthly", "succeeded", PAID, PAID.plus(Duration.ofHours(1)));
        Facts approved = new Facts(inWindow.transactionId(), inWindow.amountVnd(), inWindow.currency(), inWindow.kind(),
                inWindow.status(), inWindow.paidAt(), inWindow.itemRef(), false, true, FREE, inWindow.now());

        assertThat(ChangeOfMind.judge(approved).refusal()).isEqualTo(Refusal.ALREADY_REFUNDED);
    }

    @Test
    void aRejectedRequestClosesTheRight() {
        Facts base = topup(PAID.plus(Duration.ofHours(2)));
        Facts rejected = new Facts(base.transactionId(), base.amountVnd(), base.currency(), base.kind(), base.status(),
                base.paidAt(), base.itemRef(), true, false, FREE, base.now());

        assertThat(ChangeOfMind.judge(rejected).refusal()).isEqualTo(Refusal.PREVIOUSLY_REJECTED);
    }

    @Test
    void noFreeTurnRefusesAPaymentThatIsOtherwiseInsideTheWindow() {
        Facts base = topup(PAID.plus(Duration.ofHours(2)));
        TurnStanding used = TurnStanding.of(List.of(PAID.minus(Duration.ofDays(30)),
                PAID.minus(Duration.ofDays(10))), base.now(), 2, 6, true);
        Facts spent = new Facts(base.transactionId(), base.amountVnd(), base.currency(), base.kind(), base.status(),
                base.paidAt(), base.itemRef(), false, false, used, base.now());

        assertThat(ChangeOfMind.judge(spent).refusal()).isEqualTo(Refusal.TURN_LIMIT_REACHED);
    }

    @Test
    void refusalsAreAnsweredInTheDeclaredOrder() {
        Instant now = PAID.plus(Duration.ofDays(30));
        TurnStanding used = TurnStanding.of(List.of(now.minus(Duration.ofDays(2)),
                now.minus(Duration.ofDays(1))), now, 2, 6, true);

        Facts refundedAndRejected = new Facts(UUID.randomUUID(), 1, "VND", "topup", "refunded", PAID, "pkg-50k",
                true, false, used, now);
        Facts rejectedAndElapsed = new Facts(UUID.randomUUID(), 1, "VND", "topup", "succeeded", PAID, "pkg-50k",
                true, false, used, now);
        Facts elapsedAndSpent = new Facts(UUID.randomUUID(), 1, "VND", "topup", "succeeded", PAID, "pkg-50k",
                false, false, used, now);

        assertThat(ChangeOfMind.judge(refundedAndRejected).refusal()).isEqualTo(Refusal.ALREADY_REFUNDED);
        assertThat(ChangeOfMind.judge(rejectedAndElapsed).refusal()).isEqualTo(Refusal.PREVIOUSLY_REJECTED);
        assertThat(ChangeOfMind.judge(elapsedAndSpent).refusal()).isEqualTo(Refusal.WINDOW_ELAPSED);
    }

    @Test
    void theRightCarriesThePaymentFactsItWasJudgedFrom() {
        Facts facts = plan("basic:monthly", PAID.plus(Duration.ofHours(1)));

        Right right = ChangeOfMind.judge(facts);

        assertThat(right.transactionId()).isEqualTo(facts.transactionId());
        assertThat(right.amountVnd()).isEqualTo(199_000);
        assertThat(right.currency()).isEqualTo("VND");
        assertThat(right.kind()).isEqualTo("plan_purchase");
    }
}
