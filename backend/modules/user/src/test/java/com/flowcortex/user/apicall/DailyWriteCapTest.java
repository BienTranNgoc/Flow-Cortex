package com.flowcortex.user.apicall;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DailyWriteCapTest {

    private static final Instant THREE_PM = Instant.parse("2026-11-15T15:00:00Z");

    @Test
    void bothLimitsOffAdmitWithoutCounting() {
        assertThat(DailyWriteCap.needsCount(0, 0, true)).isFalse();
        assertThat(DailyWriteCap.needsCount(0, 0, false)).isFalse();
        assertThat(DailyWriteCap.decide(0, 0, true, 500, 500, THREE_PM)).isEmpty();
    }

    @Test
    void aCountIsNeededForTheTotalLimitAndForTheCreateLimitOnlyOnACreate() {
        assertThat(DailyWriteCap.needsCount(100, 0, false)).isTrue();
        assertThat(DailyWriteCap.needsCount(0, 10, true)).isTrue();
        assertThat(DailyWriteCap.needsCount(0, 10, false)).isFalse();
    }

    @Test
    void theTotalLimitReachedRefusesUntilMidnightUtc() {
        Optional<Duration> wait = DailyWriteCap.decide(100, 0, false, 100, 0, THREE_PM);

        assertThat(wait).contains(Duration.ofSeconds(32_400));
    }

    @Test
    void oneBelowTheTotalLimitAdmits() {
        assertThat(DailyWriteCap.decide(100, 0, false, 99, 0, THREE_PM)).isEmpty();
    }

    @Test
    void theCreateLimitRefusesOnlyAWorkflowCreate() {
        assertThat(DailyWriteCap.decide(0, 10, true, 50, 10, THREE_PM)).isPresent();
        assertThat(DailyWriteCap.decide(0, 10, false, 50, 10, THREE_PM)).isEmpty();
        assertThat(DailyWriteCap.decide(0, 10, true, 50, 9, THREE_PM)).isEmpty();
    }

    @Test
    void eitherLimitReachedRefuses() {
        assertThat(DailyWriteCap.decide(100, 10, true, 100, 3, THREE_PM)).isPresent();
        assertThat(DailyWriteCap.decide(100, 10, true, 40, 10, THREE_PM)).isPresent();
    }

    @Test
    void oneSecondBeforeMidnightWaitsOneSecond() {
        Optional<Duration> wait = DailyWriteCap.decide(1, 0, false, 1, 0, Instant.parse("2026-11-15T23:59:59Z"));

        assertThat(wait).contains(Duration.ofSeconds(1));
    }

    @Test
    void justAfterMidnightTheWaitIsAFullDay() {
        Optional<Duration> wait = DailyWriteCap.decide(1, 0, false, 1, 0, Instant.parse("2026-11-16T00:00:00Z"));

        assertThat(wait).contains(Duration.ofDays(1));
    }

    @Test
    void theDayStartsAtMidnightUtc() {
        assertThat(DailyWriteCap.startOfDay(Instant.parse("2026-11-15T23:59:59Z")))
                .isEqualTo(Instant.parse("2026-11-15T00:00:00Z"));
    }
}
