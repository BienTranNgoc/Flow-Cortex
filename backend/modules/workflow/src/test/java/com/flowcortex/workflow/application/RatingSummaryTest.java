package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RatingSummaryTest {

    @Test
    void threeRatingsGiveTheMeanToTwoDecimals() {
        RatingSummary summary = RatingSummary.of(Map.of(5, 1L, 4, 2L));

        assertThat(summary.average()).isEqualByComparingTo(new BigDecimal("4.33"));
        assertThat(summary.average().scale()).isEqualTo(2);
        assertThat(summary.total()).isEqualTo(3);
        assertThat(summary.distribution())
                .containsExactly(
                        Map.entry("1", 0L),
                        Map.entry("2", 0L),
                        Map.entry("3", 0L),
                        Map.entry("4", 2L),
                        Map.entry("5", 1L));
    }

    @Test
    void halfUpRounding() {
        RatingSummary summary = RatingSummary.of(Map.of(5, 1L, 4, 7L));

        assertThat(summary.average()).isEqualByComparingTo(new BigDecimal("4.13"));
    }

    @Test
    void noReviewsGiveANullAverageAndAZeroFilledDistribution() {
        RatingSummary summary = RatingSummary.of(Map.of());

        assertThat(summary.average()).isNull();
        assertThat(summary.total()).isZero();
        assertThat(summary.distribution())
                .containsExactly(
                        Map.entry("1", 0L),
                        Map.entry("2", 0L),
                        Map.entry("3", 0L),
                        Map.entry("4", 0L),
                        Map.entry("5", 0L));
    }
}
