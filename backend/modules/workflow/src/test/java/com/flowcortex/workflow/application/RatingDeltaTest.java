package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class RatingDeltaTest {

    @Test
    void firstReviewAddsOneAndItsStars() {
        assertThat(RatingDelta.of(null, 4)).contains(new RatingDelta(1, 4));
    }

    @Test
    void replaceAddsTheDifference() {
        assertThat(RatingDelta.of(5, 2)).contains(new RatingDelta(0, -3));
        assertThat(RatingDelta.of(2, 5)).contains(new RatingDelta(0, 3));
    }

    @Test
    void deleteRemovesOneAndItsStars() {
        assertThat(RatingDelta.of(3, null)).contains(new RatingDelta(-1, -3));
    }

    @Test
    void nothingBeforeOrAfterIsNone() {
        assertThat(RatingDelta.of(null, null)).isEqualTo(Optional.empty());
    }

    @Test
    void sameStarsIsNone() {
        assertThat(RatingDelta.of(4, 4)).isEqualTo(Optional.empty());
    }
}
