package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** The bounded edit distance behind the edit percentage. */
class EditDistanceTest {

    @Test
    void oneWordChanged() {
        assertThat(EditDistance.percent("Hello world", "Hello Hanoi")).isEqualByComparingTo("45.45");
    }

    @Test
    void unchangedTextIsZero() {
        assertThat(EditDistance.percent("same", "same")).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void twoEmptyTextsAreZero() {
        assertThat(EditDistance.percent("", "")).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void emptyAgainstTextIsAHundred() {
        assertThat(EditDistance.percent("", "abc")).isEqualTo(new BigDecimal("100.00"));
    }

    @Test
    void classicPairRoundsHalfUp() {
        assertThat(EditDistance.distance("kitten", "sitting")).isEqualTo(3);
        assertThat(EditDistance.percent("kitten", "sitting")).isEqualTo(new BigDecimal("42.86"));
    }

    @Test
    void largeRewriteUsesTheLongerMiddle() {
        String edge = "p".repeat(100);
        String original = edge + "a".repeat(6_000) + edge;
        String edited = edge + "b".repeat(7_000) + edge;

        assertThat(EditDistance.distance(original, edited)).isEqualTo(7_000);
        assertThat(EditDistance.percent(original, edited)).isEqualTo(new BigDecimal("97.22"));
    }

    @Test
    void middlesOfExactlyFiveThousandAreMeasuredExactly() {
        String original = "a".repeat(2_500) + "b".repeat(2_500);
        String edited = "a".repeat(2_500) + "c".repeat(2_500);

        assertThat(EditDistance.distance(original, edited)).isEqualTo(2_500);
    }

    @Test
    void commonPrefixAndSuffixDoNotCount() {
        assertThat(EditDistance.distance("abcXdef", "abcYdef")).isEqualTo(1);
        assertThat(EditDistance.distance("aaa", "aaaa")).isEqualTo(1);
    }
}
