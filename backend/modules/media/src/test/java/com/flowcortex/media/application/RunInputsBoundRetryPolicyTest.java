package com.flowcortex.media.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.media.api.RunInputsBound;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RunInputsBoundRetryPolicyTest {

    private final RunInputsBoundRetryPolicy policy = new RunInputsBoundRetryPolicy();

    @Test
    void appliesToItsEvent() {
        assertThat(policy.eventType()).isEqualTo(RunInputsBound.class);
    }

    @Test
    void waitsOneTwoFourThenFiveMinutes() {
        assertThat(policy.delayAfter(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(policy.delayAfter(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(policy.delayAfter(3)).isEqualTo(Duration.ofMinutes(4));
        assertThat(policy.delayAfter(4)).isEqualTo(Duration.ofMinutes(5));
        assertThat(policy.delayAfter(287)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void givesUpAfter288Attempts() {
        assertThat(policy.maxAttempts()).isEqualTo(288);
    }
}
