package com.flowcortex.billing.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.billing.entitlement.PlanPeriodRule.Shortfall;
import org.junit.jupiter.api.Test;

class PlanPeriodRuleTest {

    @Test
    void theShortestMonthlyPeriodIsTwentyEightDaysAndTheShortestYearlyIsThreeHundredSixtyFive() {
        assertThat(PlanPeriodRule.shortestPeriodDays("monthly")).isEqualTo(28);
        assertThat(PlanPeriodRule.shortestPeriodDays("yearly")).isEqualTo(365);
    }

    @Test
    void aPeriodShorterThanGraceIsAShortfallNamingTierPeriodAndGrace() {
        assertThat(PlanPeriodRule.shortfall("trial", 5, 7)).contains(new Shortfall("trial", 5, 7));
    }

    @Test
    void aPeriodNotLongerThanGraceIsAShortfall() {
        assertThat(PlanPeriodRule.shortfall("trial", 7, 7)).isPresent();
    }

    @Test
    void aPeriodLongerThanGraceIsNoShortfall() {
        assertThat(PlanPeriodRule.shortfall("basic", 28, 7)).isEmpty();
    }
}
