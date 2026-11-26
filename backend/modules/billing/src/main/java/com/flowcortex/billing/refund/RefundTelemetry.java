package com.flowcortex.billing.refund;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Counts each outcome of the request and cancel routes. A line names the request id and its code and never the
 * reason, which is free text the member wrote, nor an email or an amount.
 */
@Component
class RefundTelemetry {

    private static final Logger LOG = LoggerFactory.getLogger(RefundTelemetry.class);
    private static final String COUNTER = "billing.refund.request";
    private static final String NO_REFUSAL = "none";

    private final MeterRegistry meters;

    RefundTelemetry(MeterRegistry meters) {
        this.meters = meters;
    }

    void created(UUID id, String code) {
        count("created", NO_REFUSAL);
        LOG.info("refund request created id={} code={}", id, code);
    }

    void existing() {
        count("existing", NO_REFUSAL);
    }

    void refused(String refusal) {
        count("refused", refusal);
    }

    void cancelled(UUID id, String code) {
        count("cancelled", NO_REFUSAL);
        LOG.info("refund request cancelled id={} code={}", id, code);
    }

    void cancelRefused() {
        count("cancel_refused", NO_REFUSAL);
    }

    private void count(String outcome, String refusal) {
        Counter.builder(COUNTER)
                .description("Outcomes of member refund requests and cancels")
                .tag("outcome", outcome)
                .tag("refusal", refusal)
                .register(meters)
                .increment();
    }
}
