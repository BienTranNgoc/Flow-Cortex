package com.flowcortex.billing.receipt;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Counts every receipt delivery outcome as {@code billing.receipt.delivery{outcome}}; the tag is a fixed word. */
@Component
class ReceiptTelemetry {

    private static final String COUNTER = "billing.receipt.delivery";

    private final MeterRegistry meters;

    ReceiptTelemetry(MeterRegistry meters) {
        this.meters = meters;
    }

    void sent() {
        count("sent");
    }

    void retry() {
        count("retry");
    }

    void failed() {
        count("failed");
    }

    void noAddress() {
        count("no_address");
    }

    void paused() {
        count("paused");
    }

    private void count(String outcome) {
        meters.counter(COUNTER, "outcome", outcome).increment();
    }
}
