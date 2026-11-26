package com.flowcortex.billing.tax;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** The calendar day a VAT rate is looked up on: the Vietnamese day, which ends at 17:00 UTC. */
final class VatDay {

    static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private VatDay() {
    }

    static LocalDate of(Instant instant) {
        return LocalDate.ofInstant(instant, ZONE);
    }

    /** The first instant of the Vietnamese day. */
    static Instant startOf(LocalDate day) {
        return day.atStartOfDay(ZONE).toInstant();
    }
}
