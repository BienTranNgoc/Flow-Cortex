package com.flowcortex.billing.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.billing.entitlement.PlanPeriods.Period;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PlanPeriodsTest {

    private static Instant at(String instant) {
        return Instant.parse(instant);
    }

    @Test
    void aMonthlyPeriodRenewsOnTheSameDayOfTheNextMonth() {
        assertThat(PlanPeriods.endAfter(at("2026-12-10T03:00:00Z"), 1)).isEqualTo(at("2027-01-10T03:00:00Z"));
    }

    @Test
    void aMonthEndClampsToTheLastDayOfTheShorterMonth() {
        assertThat(PlanPeriods.endAfter(at("2027-01-31T10:00:00Z"), 1)).isEqualTo(at("2027-02-28T10:00:00Z"));
    }

    @Test
    void aLeapFebruaryKeepsTheTwentyNinth() {
        assertThat(PlanPeriods.endAfter(at("2028-01-31T10:00:00Z"), 1)).isEqualTo(at("2028-02-29T10:00:00Z"));
    }

    @Test
    void aYearFromTheThirtyFirstKeepsTheThirtyFirst() {
        assertThat(PlanPeriods.endAfter(at("2027-01-31T10:00:00Z"), 12)).isEqualTo(at("2028-01-31T10:00:00Z"));
    }

    @Test
    void aYearFromALeapDayClampsToTheTwentyEighth() {
        assertThat(PlanPeriods.endAfter(at("2028-02-29T10:00:00Z"), 12)).isEqualTo(at("2029-02-28T10:00:00Z"));
    }

    @Test
    void aCycleIsOneOrTwelveMonths() {
        assertThat(PlanPeriods.monthsOf("monthly")).isEqualTo(1);
        assertThat(PlanPeriods.monthsOf("yearly")).isEqualTo(12);
    }

    @Test
    void anUnknownCycleOrModeIsRefused() {
        assertThatThrownBy(() -> PlanPeriods.monthsOf("weekly")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlanPeriods.graceDays("prepaid")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlanPeriods.pin(at("2027-01-01T00:00:00Z"), null, "manual", "weekly"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void graceIsZeroDaysForAManualMemberAndSevenForAutoDebit() {
        assertThat(PlanPeriods.graceDays("manual")).isZero();
        assertThat(PlanPeriods.graceDays("auto_debit")).isEqualTo(7);
    }

    @Test
    void aFirstPurchaseStartsAtSettlement() {
        Period period = PlanPeriods.pin(at("2026-12-10T03:00:00Z"), null, "manual", "monthly");

        assertThat(period.startsAt()).isEqualTo(at("2026-12-10T03:00:00Z"));
        assertThat(period.renewsAt()).isEqualTo(at("2027-01-10T03:00:00Z"));
    }

    @Test
    void payingAheadContinuesTheSchedule() {
        Period period = PlanPeriods.pin(at("2027-01-05T08:00:00Z"), at("2027-01-10T03:00:00Z"), "manual", "monthly");

        assertThat(period.startsAt()).isEqualTo(at("2027-01-10T03:00:00Z"));
        assertThat(period.renewsAt()).isEqualTo(at("2027-02-10T03:00:00Z"));
    }

    @Test
    void aManualMemberReturningAfterThePeriodEndedStartsAtSettlement() {
        Period period = PlanPeriods.pin(at("2027-01-10T03:00:01Z"), at("2027-01-10T03:00:00Z"), "manual", "monthly");

        assertThat(period.startsAt()).isEqualTo(at("2027-01-10T03:00:01Z"));
    }

    @Test
    void anAutoDebitMemberPayingInsideGraceContinuesTheSchedule() {
        Period period = PlanPeriods.pin(at("2027-01-16T00:00:00Z"), at("2027-01-10T03:00:00Z"), "auto_debit", "monthly");

        assertThat(period.startsAt()).isEqualTo(at("2027-01-10T03:00:00Z"));
    }

    @Test
    void anAutoDebitMemberPayingAfterGraceStartsAtSettlement() {
        Period period = PlanPeriods.pin(at("2027-01-17T03:00:00Z"), at("2027-01-10T03:00:00Z"), "auto_debit", "monthly");

        assertThat(period.startsAt()).isEqualTo(at("2027-01-17T03:00:00Z"));
    }

    @Test
    void theLaterOfTwoInstantsIgnoresAnAbsentOne() {
        Instant earlier = at("2027-01-10T03:00:00Z");
        Instant later = at("2027-02-10T03:00:00Z");

        assertThat(PlanPeriods.later(earlier, null)).isEqualTo(earlier);
        assertThat(PlanPeriods.later(null, later)).isEqualTo(later);
        assertThat(PlanPeriods.later(earlier, later)).isEqualTo(later);
        assertThat(PlanPeriods.later(later, earlier)).isEqualTo(later);
        assertThat(PlanPeriods.later(null, null)).isNull();
    }
}
