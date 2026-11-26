package com.flowcortex.trash.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TrashLimitsTest {

    private static final Duration RETENTION = Duration.ofDays(30);

    @Test
    void retentionOf1And365DaysIsValid() {
        assertThat(TrashLimits.checkRetention(Duration.ofDays(1))).isEmpty();
        assertThat(TrashLimits.checkRetention(Duration.ofDays(30))).isEmpty();
        assertThat(TrashLimits.checkRetention(Duration.ofDays(365))).isEmpty();
    }

    @Test
    void retentionOf0Or366DaysOr36HoursIsInvalid() {
        for (Duration invalid : new Duration[] {Duration.ZERO, Duration.ofDays(366), Duration.ofHours(36)}) {
            assertThat(TrashLimits.checkRetention(invalid))
                    .hasValueSatisfying(m -> assertThat(m).contains("flowcortex.trash.retention"));
        }
        assertThat(TrashLimits.checkRetention(Duration.ofDays(-1))).isPresent();
        assertThat(TrashLimits.checkRetention(Duration.ofDays(1).plusNanos(1))).isPresent();
    }

    @Test
    void leadOf1DayIsValid() {
        assertThat(TrashLimits.checkLead(Duration.ofDays(1), RETENTION)).isEmpty();
        assertThat(TrashLimits.checkLead(Duration.ofDays(3), RETENTION)).isEmpty();
    }

    @Test
    void leadEqualToRetentionIsInvalid() {
        assertThat(TrashLimits.checkLead(RETENTION, RETENTION))
                .hasValueSatisfying(m -> assertThat(m).contains("flowcortex.trash.reminder-lead"));
        assertThat(TrashLimits.checkLead(Duration.ofDays(31), RETENTION)).isPresent();
    }

    @Test
    void leadOfRetentionMinusOneDayIsValid() {
        assertThat(TrashLimits.checkLead(Duration.ofDays(29), RETENTION)).isEmpty();
        assertThat(TrashLimits.checkLead(Duration.ofDays(29).plusSeconds(1), RETENTION)).isPresent();
    }

    @Test
    void leadBelowOneDayIsInvalid() {
        assertThat(TrashLimits.checkLead(Duration.ofHours(23), RETENTION))
                .hasValueSatisfying(m -> assertThat(m).contains("flowcortex.trash.reminder-lead"));
        assertThat(TrashLimits.checkLead(Duration.ZERO, RETENTION)).isPresent();
    }
}
