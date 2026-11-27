package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class ScheduleClockTest {

    private static final ZoneId HCM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private static Instant at(String instant) {
        return Instant.parse(instant);
    }

    @Test
    void dailyLaterToday() {
        Instant next = ScheduleClock.next("daily", null, null, LocalTime.of(8, 0), HCM, at("2026-11-16T00:00:00Z"));

        assertThat(next).isEqualTo(at("2026-11-16T01:00:00Z"));
    }

    @Test
    void dailyExactlyNowIsTomorrow() {
        Instant next = ScheduleClock.next("daily", null, null, LocalTime.of(8, 0), HCM, at("2026-11-16T01:00:00Z"));

        assertThat(next).isEqualTo(at("2026-11-17T01:00:00Z"));
    }

    @Test
    void dailyPassedIsTomorrow() {
        Instant next = ScheduleClock.next("daily", null, null, LocalTime.of(8, 0), HCM, at("2026-11-16T02:00:00Z"));

        assertThat(next).isEqualTo(at("2026-11-17T01:00:00Z"));
    }

    @Test
    void weeklySameWeekdayLaterToday() {
        // Now is Monday 07:00 in Ho Chi Minh City.
        Instant next = ScheduleClock.next("weekly", 1, null, LocalTime.of(7, 15), HCM, at("2026-11-16T00:00:00Z"));

        assertThat(ScheduleClock.local(next, HCM)).isEqualTo(OffsetDateTime.parse("2026-11-16T07:15:00+07:00"));
    }

    @Test
    void weeklyWeekdayPassedIsNextWeek() {
        // Now is Tuesday 07:00 in Ho Chi Minh City.
        Instant next = ScheduleClock.next("weekly", 1, null, LocalTime.of(7, 15), HCM, at("2026-11-17T00:00:00Z"));

        assertThat(ScheduleClock.local(next, HCM)).isEqualTo(OffsetDateTime.parse("2026-11-23T07:15:00+07:00"));
    }

    @Test
    void weeklyLaterWeekdayIsInTheSameIsoWeek() {
        // Now is Monday 07:00; Sunday closes the same ISO week.
        Instant next = ScheduleClock.next("weekly", 7, null, LocalTime.of(7, 15), HCM, at("2026-11-16T00:00:00Z"));

        assertThat(ScheduleClock.local(next, HCM)).isEqualTo(OffsetDateTime.parse("2026-11-22T07:15:00+07:00"));
    }

    @Test
    void monthlyDayPassedIsNextMonth() {
        // Now is the 20th, 10:00 in Ho Chi Minh City.
        Instant next = ScheduleClock.next("monthly", null, 15, LocalTime.of(10, 0), HCM, at("2026-11-20T03:00:00Z"));

        assertThat(ScheduleClock.local(next, HCM)).isEqualTo(OffsetDateTime.parse("2026-12-15T10:00:00+07:00"));
    }

    @Test
    void monthlyDayLaterThisMonth() {
        // Now is the 10th, 10:00 in Ho Chi Minh City.
        Instant next = ScheduleClock.next("monthly", null, 15, LocalTime.of(10, 0), HCM, at("2026-11-10T03:00:00Z"));

        assertThat(ScheduleClock.local(next, HCM)).isEqualTo(OffsetDateTime.parse("2026-11-15T10:00:00+07:00"));
    }

    @Test
    void skippedLocalTimeShiftsForwardByTheGap() {
        // Noon in Berlin the day before the clocks go forward: 02:00-03:00 does not exist the next day.
        Instant next = ScheduleClock.next("daily", null, null, LocalTime.of(2, 30), BERLIN, at("2027-03-27T11:00:00Z"));

        assertThat(ScheduleClock.local(next, BERLIN)).isEqualTo(OffsetDateTime.parse("2027-03-28T03:30:00+02:00"));
    }

    @Test
    void repeatedLocalTimeTakesTheEarlierInstant() {
        // Noon in Berlin the day before the clocks go back: 02:00-03:00 happens twice the next day.
        Instant next = ScheduleClock.next("daily", null, null, LocalTime.of(2, 30), BERLIN, at("2026-10-24T10:00:00Z"));

        assertThat(next).isEqualTo(at("2026-10-25T00:30:00Z"));
        assertThat(ScheduleClock.local(next, BERLIN)).isEqualTo(OffsetDateTime.parse("2026-10-25T02:30:00+02:00"));
    }

    @Test
    void nextRunLocalIsRenderedInTheScheduleZone() {
        OffsetDateTime local = ScheduleClock.local(at("2026-11-17T01:00:00Z"), HCM);

        assertThat(local).hasToString("2026-11-17T08:00+07:00");
    }

    @Test
    void aRuleWithoutItsDayOrWithAnUnknownFrequencyIsRefused() {
        Instant now = at("2026-11-16T00:00:00Z");

        assertThatThrownBy(() -> ScheduleClock.next("weekly", null, null, LocalTime.of(8, 0), HCM, now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScheduleClock.next("monthly", null, null, LocalTime.of(8, 0), HCM, now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScheduleClock.next("hourly", null, null, LocalTime.of(8, 0), HCM, now))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseZoneAcceptsRegionIdsAndUtcOnly() {
        assertThat(ScheduleClock.parseZone("Asia/Ho_Chi_Minh")).isEqualTo(HCM);
        assertThat(ScheduleClock.parseZone("UTC")).isEqualTo(ZoneId.of("UTC"));

        for (String refused : new String[] {"+07:00", "UTC+7", "Mars/Olympus", "asia/ho_chi_minh", "", " ", null}) {
            assertThatThrownBy(() -> ScheduleClock.parseZone(refused))
                    .as("zone %s", refused)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
