package com.flowcortex.credit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StepCostsTest {

    private final StepCosts costs = new StepCosts(Map.of("echo", 10L, "fail", 50L, "APPROVAL_CHECKPOINT", 0L));

    @Test
    void theEstimateIsTheSumOfEachStepsCost() {
        assertThat(costs.estimate(List.of("echo", "fail"))).isEqualTo(60);
        assertThat(costs.estimate(List.of("echo", "APPROVAL_CHECKPOINT", "echo"))).isEqualTo(20);
        assertThat(costs.estimate(List.of())).isZero();
    }

    @Test
    void everyRegisteredTypeWithACostPassesTheStartupCheck() {
        assertThatCode(() -> costs.requirePriced(Set.of("echo", "fail", "APPROVAL_CHECKPOINT")))
                .doesNotThrowAnyException();
    }

    @Test
    void aRegisteredTypeWithoutACostFailsTheStartupCheckNamingIt() {
        assertThatThrownBy(() -> costs.requirePriced(Set.of("echo", "render_video", "probe")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flowcortex.credits.step-costs")
                .hasMessageContaining("probe")
                .hasMessageContaining("render_video");
    }

    @Test
    void aTypeWithoutACostHasNoPrice() {
        assertThatThrownBy(() -> costs.costOf("render_video"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("render_video");
    }

    @Test
    void aNegativeCostFailsBinding() {
        assertThatThrownBy(() -> new StepCosts(Map.of("echo", -1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("echo");
    }

    @Test
    void aMissingCostValueFailsBinding() {
        Map<String, Long> withNull = new HashMap<>();
        withNull.put("echo", null);

        assertThatThrownBy(() -> new StepCosts(withNull))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("echo");
    }

    @Test
    void noCostsConfiguredAtAllMeansNothingIsPriced() {
        assertThatThrownBy(() -> new StepCosts(null).requirePriced(Set.of("echo")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("echo");
    }
}
