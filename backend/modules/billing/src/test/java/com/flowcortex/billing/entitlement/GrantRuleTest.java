package com.flowcortex.billing.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.billing.entitlement.GrantRule.Holding;
import com.flowcortex.billing.entitlement.GrantRule.Outcome;
import com.flowcortex.billing.entitlement.PlanPeriods.Period;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GrantRuleTest {

    private static final UUID PAYMENT = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID GRANTED_FROM = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID OTHER = UUID.fromString("00000000-0000-4000-8000-000000000003");
    private static final Instant SETTLED = Instant.parse("2027-02-01T00:00:00Z");

    private static Holding holding(Optional<UUID> pending) {
        return new Holding("basic", "monthly", "active", "manual", Instant.parse("2027-01-01T00:00:00Z"),
                Instant.parse("2027-02-01T00:00:00Z"), GRANTED_FROM, pending);
    }

    private static Period starting(Instant startsAt) {
        return new Period(startsAt, PlanPeriods.endAfter(startsAt, 1));
    }

    private static Outcome decide(Optional<Holding> holding, Period period, Instant grantedSucceededAt) {
        return GrantRule.decide(holding, period, SETTLED, PAYMENT, grantedSucceededAt);
    }

    @Test
    void aMemberWithNoHoldingGetsOne() {
        assertThat(decide(Optional.empty(), starting(SETTLED), null)).isEqualTo(new GrantRule.Open());
    }

    @Test
    void aPeriodStartingAtSettlementMovesTheHoldingWhenItsGrantSucceededEarlier() {
        Outcome outcome = decide(Optional.of(holding(Optional.empty())), starting(SETTLED),
                Instant.parse("2027-01-31T23:59:59Z"));

        assertThat(outcome).isEqualTo(new GrantRule.Move());
    }

    @Test
    void aHoldingGrantedFromAPaymentThatSucceededAtOrAfterThisOneIsNotMoved() {
        assertThat(decide(Optional.of(holding(Optional.empty())), starting(SETTLED), Instant.parse("2027-02-01T00:00:00Z")))
                .isEqualTo(new GrantRule.Superseded());
        assertThat(decide(Optional.of(holding(Optional.empty())), starting(SETTLED), Instant.parse("2027-02-01T00:00:01Z")))
                .isEqualTo(new GrantRule.Superseded());
    }

    @Test
    void aPeriodStartingAfterSettlementIsRecordedAsNext() {
        Outcome outcome = decide(Optional.of(holding(Optional.empty())), starting(Instant.parse("2027-02-10T03:00:00Z")),
                Instant.parse("2027-01-01T00:00:00Z"));

        assertThat(outcome).isEqualTo(new GrantRule.RecordNext());
    }

    @Test
    void aPeriodStartingAfterSettlementWhileAnotherPaymentIsNextIsRefused() {
        Outcome outcome = decide(Optional.of(holding(Optional.of(OTHER))), starting(Instant.parse("2027-02-10T03:00:00Z")),
                Instant.parse("2027-01-01T00:00:00Z"));

        assertThat(outcome).isEqualTo(new GrantRule.Refused("pending_plan_occupied"));
    }
}
