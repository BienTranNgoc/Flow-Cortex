package com.flowcortex.billing.tax;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Counts every rate lookup; log lines name jurisdiction, category and day only, never an amount or a payer. */
@Component
class VatTelemetry {

    private static final Logger LOG = LoggerFactory.getLogger(VatTelemetry.class);
    private static final String LOOKUP = "billing.vat.rate.lookup";

    private final MeterRegistry meters;

    VatTelemetry(MeterRegistry meters) {
        this.meters = meters;
    }

    void resolved() {
        meters.counter(LOOKUP, "outcome", "resolved").increment();
    }

    void missing(String jurisdiction, String category, LocalDate day) {
        meters.counter(LOOKUP, "outcome", "missing").increment();
        LOG.warn("No VAT rate covers the day: event={} jurisdiction={} category={} day={}", LOOKUP, jurisdiction,
                category, day);
    }

    void ambiguous(String jurisdiction, String category, LocalDate day) {
        meters.counter(LOOKUP, "outcome", "ambiguous").increment();
        LOG.warn("More than one VAT rate covers the day: event={} jurisdiction={} category={} day={}", LOOKUP,
                jurisdiction, category, day);
    }

    void failed() {
        meters.counter(LOOKUP, "outcome", "failed").increment();
    }
}
