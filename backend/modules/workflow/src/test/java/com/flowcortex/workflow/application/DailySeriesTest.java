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
                List.of(new DayCount(LocalDate.of(2026, 11, 6), 4, 1), new DayCount(TODAY, 2, 2)), TODAY, 3);

        assertThat(series).containsExactly(new DayCount(LocalDate.of(2026, 11, 6), 4, 1),
                new DayCount(LocalDate.of(2026, 11, 7), 0, 0), new DayCount(TODAY, 2, 2));
    }

    @Test
    void ignoresRowsOutsideTheWindow() {
        List<DayCount> series = DailySeries.fill(List.of(new DayCount(LocalDate.of(2026, 11, 5), 9, 9),
                new DayCount(TODAY.plusDays(1), 9, 9)), TODAY, 3);

        assertThat(series).hasSize(3).allSatisfy(point -> assertThat(point.runs()).isZero());
    }

    @Test
    void thirtyDaysEndingOnTheEighthStartOnTheTenthOfTheMonthBefore() {
        List<DayCount> series = DailySeries.fill(List.of(), TODAY, 30);

        assertThat(series).hasSize(30);
        assertThat(series.get(0).day()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(series.get(29).day()).isEqualTo(TODAY);
    }

    @Test
    void anEmptyInputIsAllZeros() {
        assertThat(DailySeries.fill(List.of(), TODAY, 7)).hasSize(7)
                .allSatisfy(point -> assertThat(point.runs() + point.failed()).isZero());
    }

    @Test
    void aRunAtHalfPastMidnightLocalTimeCountsOnTheLocalDay() {
        Instant halfPastMidnightLocal = Instant.parse("2026-11-07T17:30:00Z");

        assertThat(DailySeries.dayOf(halfPastMidnightLocal)).isEqualTo(LocalDate.of(2026, 11, 8));
    }

    @Test
    void windowStartIsTheLocalMidnightOfTheFirstDay() {
        assertThat(DailySeries.windowStart(TODAY, 3)).isEqualTo(Instant.parse("2026-11-05T17:00:00Z"));
    }

    @Test
    void daysListsTheWindowOldestFirst() {
        assertThat(DailySeries.days(TODAY, 3)).containsExactly(LocalDate.of(2026, 11, 6),
                LocalDate.of(2026, 11, 7), TODAY);
    }
}
