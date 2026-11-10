package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.workflow.api.DayCount;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class DailySeriesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 8);

    @Test
    void fillsEveryDayOfTheWindowOldestFirstWithZeros() {
        List<DayCount> series = DailySeries.fill(
                List.of(new DayCount(LocalDate.of(2026, 11, 8), 4, 1), new DayCount(LocalDate.of(2026, 11, 6), 2, 0)),
                TODAY, 7);

        assertThat(series).extracting(DayCount::day).containsExactly(
                LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 4),
                LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 7),
                LocalDate.of(2026, 11, 8));
        assertThat(series).extracting(DayCount::runs).containsExactly(0L, 0L, 0L, 0L, 2L, 0L, 4L);
        assertThat(series).extracting(DayCount::failed).containsExactly(0L, 0L, 0L, 0L, 0L, 0L, 1L);
    }

    @Test
    void ignoresRowsOutsideTheWindow() {
        List<DayCount> series = DailySeries.fill(
                List.of(new DayCount(LocalDate.of(2026, 11, 1), 9, 9), new DayCount(LocalDate.of(2026, 11, 9), 9, 9)),
                TODAY, 7);

        assertThat(series).hasSize(7).allSatisfy(point -> assertThat(point.runs()).isZero());
    }

    @Test
    void thirtyDaysEndingOnTheEighthStartOnTheTenthOfTheMonthBefore() {
        List<DayCount> series = DailySeries.fill(List.of(), TODAY, 30);

        assertThat(series).hasSize(30);
        assertThat(series.getFirst().day()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(series.getLast().day()).isEqualTo(TODAY);
        assertThat(DailySeries.windowStart(TODAY, 30)).isEqualTo(Instant.parse("2026-10-09T17:00:00Z"));
    }

    @Test
    void anEmptyInputIsAllZeros() {
        assertThat(DailySeries.fill(List.of(), TODAY, 5)).allSatisfy(point -> {
            assertThat(point.runs()).isZero();
            assertThat(point.failed()).isZero();
        });
    }

    @Test
    void aRunAtHalfPastMidnightLocalTimeCountsOnTheLocalDay() {
        assertThat(DailySeries.dayOf(Instant.parse("2026-11-07T17:30:00Z"))).isEqualTo(LocalDate.of(2026, 11, 8));
        assertThat(DailySeries.dayOf(Instant.parse("2026-11-07T16:59:59Z"))).isEqualTo(LocalDate.of(2026, 11, 7));
    }
}
