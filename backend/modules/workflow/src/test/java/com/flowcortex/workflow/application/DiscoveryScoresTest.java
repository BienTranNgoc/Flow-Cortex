package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DiscoveryScoresTest {

    @Test
    void trendingIsThreePerRunnerAndFivePerSave() {
        assertThat(DiscoveryScores.trending(4, 1)).isEqualTo(17);
        assertThat(DiscoveryScores.trending(2, 3)).isEqualTo(21);
    }

    @Test
    void zeroIsZero() {
        assertThat(DiscoveryScores.trending(0, 0)).isZero();
    }

    @Test
    void saturatesAtIntegerMax() {
        assertThat(DiscoveryScores.trending(Integer.MAX_VALUE, Integer.MAX_VALUE)).isEqualTo(Integer.MAX_VALUE);
        assertThat(DiscoveryScores.trending(0, Integer.MAX_VALUE)).isEqualTo(Integer.MAX_VALUE);
    }
}
