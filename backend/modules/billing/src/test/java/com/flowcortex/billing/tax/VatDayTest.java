package com.flowcortex.billing.tax;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class VatDayTest {

    @Test
    void theLastSecondBeforeVietnamMidnightIsStillTheOldDay() {
        assertThat(VatDay.of(Instant.parse("2026-12-31T16:59:59Z"))).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    void vietnamMidnightStartsTheNewDay() {
        assertThat(VatDay.of(Instant.parse("2026-12-31T17:00:00Z"))).isEqualTo(LocalDate.of(2027, 1, 1));
    }

    @Test
    void anInstantIsReadOnTheVietnameseCalendarWhateverItsOffset() {
        assertThat(VatDay.of(Instant.parse("2026-07-01T00:00:00+07:00"))).isEqualTo(LocalDate.of(2026, 7, 1));
    }
}
