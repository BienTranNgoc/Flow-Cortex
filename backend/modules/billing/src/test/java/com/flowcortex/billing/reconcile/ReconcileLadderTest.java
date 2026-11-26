package com.flowcortex.billing.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReconcileLadderTest {

    private static final Duration FLOOR = Duration.ofMinutes(15);
    private static final Instant TEN = Instant.parse("2026-11-24T10:00:00Z");

    @Test
    void theFirstAskIsOneFloorAfterTheOpening() {
        assertThat(ReconcileLadder.firstAskAt(TEN, FLOOR)).isEqualTo(Instant.parse("2026-11-24T10:15:00Z"));
    }

    @Test
    void theGapDoublesFromTheRecordedAskTime() {
        assertThat(ReconcileLadder.nextAskAt(Instant.parse("2026-11-24T10:15:00Z"), 1, FLOOR))
                .isEqualTo(Instant.parse("2026-11-24T10:45:00Z"));
        assertThat(ReconcileLadder.nextAskAt(Instant.parse("2026-11-24T10:45:00Z"), 2, FLOOR))
                .isEqualTo(Instant.parse("2026-11-24T11:45:00Z"));
    }

    @Test
    void theGapIsCappedAtFourDays() {
        Instant dayThree = Instant.parse("2026-11-27T00:00:00Z");

        assertThat(ReconcileLadder.nextAskAt(dayThree, 9, FLOOR)).isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));
    }

    @Test
    void aHugeCountUsesTheCapWithoutOverflow() {
        assertThat(ReconcileLadder.intervalAfter(40, FLOOR)).isEqualTo(Duration.ofDays(4));
        assertThat(ReconcileLadder.intervalAfter(Integer.MAX_VALUE, FLOOR)).isEqualTo(Duration.ofDays(4));
    }

    @Test
    void aShorterFloorDoublesFromItself() {
        assertThat(ReconcileLadder.intervalAfter(1, Duration.ofSeconds(30))).isEqualTo(Duration.ofSeconds(60));
        assertThat(ReconcileLadder.intervalAfter(3, Duration.ofSeconds(30))).isEqualTo(Duration.ofSeconds(240));
    }

    @Test
    void theWindowClosesThirtyOneDaysAfterTheOpening() {
        Instant window = TEN.plus(Duration.ofDays(31));

        assertThat(ReconcileLadder.pastCeiling(TEN, window.minusSeconds(1))).isFalse();
        assertThat(ReconcileLadder.pastCeiling(TEN, window)).isTrue();
    }

    @Test
    void aNegativeCountIsRefused() {
        assertThatThrownBy(() -> ReconcileLadder.intervalAfter(-1, FLOOR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReconcileLadder.nextAskAt(TEN, -1, FLOOR))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
