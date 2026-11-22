package com.flowcortex.asset.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryPlanTest {

    @Test
    void firstFailureWaitsOneMinute() {
        assertThat(RetryPlan.after(1)).isEqualTo(new RetryPlan.Pending(Duration.ofMinutes(1)));
    }

    @Test
    void failuresTwoThreeFourWaitTwoFourFiveMinutes() {
        assertThat(RetryPlan.after(2)).isEqualTo(new RetryPlan.Pending(Duration.ofMinutes(2)));
        assertThat(RetryPlan.after(3)).isEqualTo(new RetryPlan.Pending(Duration.ofMinutes(4)));
        assertThat(RetryPlan.after(4)).isEqualTo(new RetryPlan.Pending(Duration.ofMinutes(5)));
    }

    @Test
    void waitIsCappedAtFiveMinutes() {
        assertThat(RetryPlan.after(50)).isEqualTo(new RetryPlan.Pending(Duration.ofMinutes(5)));
    }

    @Test
    void failure287IsStillPending() {
        assertThat(RetryPlan.after(287)).isEqualTo(new RetryPlan.Pending(Duration.ofMinutes(5)));
    }

    @Test
    void failure288IsStuck() {
        assertThat(RetryPlan.after(288)).isEqualTo(RetryPlan.Stuck.INSTANCE);
        assertThat(RetryPlan.after(1000)).isEqualTo(RetryPlan.Stuck.INSTANCE);
    }
}
