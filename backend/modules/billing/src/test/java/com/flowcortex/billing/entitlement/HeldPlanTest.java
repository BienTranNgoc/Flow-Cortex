package com.flowcortex.billing.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.billing.entitlement.GrantRule.Holding;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HeldPlanTest {

    private static final UUID PAYMENT = UUID.fromString("00000000-0000-4000-8000-000000000001");

    private static Instant at(String instant) {
        return Instant.parse(instant);
    }

    private static Holding holding(String tier, String status, String mode, String starts, String renews) {
        return new Holding(tier, "monthly", status, mode, at(starts), at(renews), PAYMENT, Optional.empty());
    }

    private static HeldPlan.NextPlan next(String tier, String starts, String renews) {
        return new HeldPlan.NextPlan(tier, "monthly", at(starts), at(renews));
    }

    @Test
    void noHoldingIsFreeWithNoPeriod() {
        HeldPlan plan = HeldPlan.of(Optional.empty(), Optional.empty(), at("2027-01-01T00:00:00Z"));

        assertThat(plan.tierId()).isEqualTo("free");
        assertThat(plan.held()).isFalse();
        assertThat(plan.cycle()).isNull();
        assertThat(plan.mode()).isNull();
        assertThat(plan.startsAt()).isNull();
        assertThat(plan.renewsAt()).isNull();
        assertThat(plan.next()).isEmpty();
    }

    @Test
    void aRunningPeriodIsHeldAndTheNextPlanIsShownWhileItHasNotBegun() {
        Holding basic = holding("basic", "active", "manual", "2026-12-10T03:00:00Z", "2027-01-10T03:00:00Z");
        HeldPlan.NextPlan advanced = next("advanced", "2027-01-10T03:00:00Z", "2027-02-10T03:00:00Z");

        HeldPlan plan = HeldPlan.of(Optional.of(basic), Optional.of(advanced), at("2026-12-20T00:00:00Z"));

        assertThat(plan.tierId()).isEqualTo("basic");
        assertThat(plan.held()).isTrue();
        assertThat(plan.cycle()).isEqualTo("monthly");
        assertThat(plan.mode()).isEqualTo("manual");
        assertThat(plan.startsAt()).isEqualTo(at("2026-12-10T03:00:00Z"));
        assertThat(plan.renewsAt()).isEqualTo(at("2027-01-10T03:00:00Z"));
        assertThat(plan.next()).contains(advanced);
    }

    @Test
    void aManualPeriodThatEndedIsFree() {
        Holding basic = holding("basic", "active", "manual", "2026-12-10T03:00:00Z", "2027-01-10T03:00:00Z");

        assertThat(HeldPlan.of(Optional.of(basic), Optional.empty(), at("2027-01-10T03:00:00Z")).tierId())
                .isEqualTo("free");
        assertThat(HeldPlan.of(Optional.of(basic), Optional.empty(), at("2027-01-10T02:59:59Z")).tierId())
                .isEqualTo("basic");
    }

    @Test
    void anAutoDebitHoldingStaysHeldInsideGraceAndIsFreeAfterIt() {
        Holding basic = holding("basic", "active", "auto_debit", "2027-01-10T03:00:00Z", "2027-02-10T03:00:00Z");
        Holding renewedLate = holding("basic", "active", "auto_debit", "2026-12-10T03:00:00Z", "2027-01-10T03:00:00Z");

        assertThat(HeldPlan.of(Optional.of(renewedLate), Optional.empty(), at("2027-01-15T00:00:00Z")).tierId())
                .isEqualTo("basic");
        assertThat(HeldPlan.of(Optional.of(renewedLate), Optional.empty(), at("2027-01-17T03:00:00Z")).tierId())
                .isEqualTo("free");
        assertThat(HeldPlan.of(Optional.of(basic), Optional.empty(), at("2027-01-15T00:00:00Z")).held()).isTrue();
    }

    @Test
    void aNextCyclePlanThatHasBegunIsTheHeldPlanAndIsNotShownAsNext() {
        Holding basic = holding("basic", "active", "manual", "2026-12-10T03:00:00Z", "2027-01-10T03:00:00Z");
        HeldPlan.NextPlan advanced = next("advanced", "2027-01-10T03:00:00Z", "2027-02-10T03:00:00Z");

        HeldPlan plan = HeldPlan.of(Optional.of(basic), Optional.of(advanced), at("2027-01-10T03:01:00Z"));

        assertThat(plan.tierId()).isEqualTo("advanced");
        assertThat(plan.held()).isTrue();
        assertThat(plan.startsAt()).isEqualTo(at("2027-01-10T03:00:00Z"));
        assertThat(plan.renewsAt()).isEqualTo(at("2027-02-10T03:00:00Z"));
        assertThat(plan.mode()).isEqualTo("manual");
        assertThat(plan.next()).isEmpty();
    }

    @Test
    void aNextCyclePlanThatEndedWithNothingElseIsFree() {
        Holding basic = holding("basic", "active", "manual", "2026-12-10T03:00:00Z", "2027-01-10T03:00:00Z");
        HeldPlan.NextPlan advanced = next("advanced", "2027-01-10T03:00:00Z", "2027-02-10T03:00:00Z");

        HeldPlan plan = HeldPlan.of(Optional.of(basic), Optional.of(advanced), at("2027-02-10T03:00:00Z"));

        assertThat(plan.tierId()).isEqualTo("free");
        assertThat(plan.next()).isEmpty();
    }

    @Test
    void aHoldingAtAnyStatusButActiveIsFree() {
        Holding grace = holding("basic", "grace", "manual", "2026-12-10T03:00:00Z", "2027-01-10T03:00:00Z");

        assertThat(HeldPlan.of(Optional.of(grace), Optional.empty(), at("2026-12-20T00:00:00Z")).tierId())
                .isEqualTo("free");
    }
}
