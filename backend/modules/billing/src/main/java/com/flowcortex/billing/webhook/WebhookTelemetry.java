package com.flowcortex.billing.webhook;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Counts and times provider callbacks by disposition and reason. Reasons come from a fixed vocabulary, and no
 * tag or log line carries a value taken from a callback.
 */
@Component
class WebhookTelemetry {

    static final String ACCEPTED = "accepted";
    static final String DUPLICATE = "duplicate";
    static final String REJECTED = "rejected";
    static final String REFUSED_SOURCE = "refused_source";
    static final String DISABLED = "disabled";
    static final String NO_REASON = "none";

    /** The {@code side} tag for a callback whose signature did not match ours. */
    private static final String INBOUND_MISMATCH = "inbound_mismatch";
    private static final Logger LOG = LoggerFactory.getLogger(WebhookTelemetry.class);

    private final MeterRegistry meters;

    WebhookTelemetry(MeterRegistry meters) {
        this.meters = meters;
    }

    /** One handled callback: counted by disposition and reason, its handling time recorded. */
    void handled(String provider, String disposition, String reason, Duration elapsed) {
        Counter.builder("payment.webhook.callback")
                .description("Provider callbacks by disposition and reason")
                .tag("provider", provider)
                .tag("disposition", disposition)
                .tag("reason", reason == null ? NO_REASON : reason)
                .register(meters)
                .increment();
        Timer.builder("payment.webhook.callback.duration")
                .description("Time spent handling provider callbacks")
                .tag("provider", provider)
                .register(meters)
                .record(elapsed);
    }

    void signatureMismatch(String provider) {
        Counter.builder("payment.provider.signature.failure")
                .description("Signatures a provider or we refused")
                .tag("provider", provider)
                .tag("side", INBOUND_MISMATCH)
                .register(meters)
                .increment();
    }

    void rejectedRowFailed(String provider, String reason) {
        LOG.warn("payment_webhook_rejected_row_failed provider={} reason={}", provider, reason);
    }

    void writeFailed(String provider, Exception failure) {
        LOG.error("payment_webhook_write_failed provider={} cause={}", provider, failure.getClass().getSimpleName());
    }

    /** The row id is the only handle on the stored bytes; the body values stay out of the log. */
    void outOfBounds(String provider, String rowId) {
        LOG.error("payment_webhook_event_out_of_bounds provider={} row={}", provider, rowId);
    }

    /** A verified callback for a reference another system minted; nothing from its body is logged. */
    void referenceNotOurs(String provider, String rowId) {
        LOG.warn("payment_webhook_reference_not_ours provider={} reason={} row={}", provider, "reference_not_ours", rowId);
    }

    void unreadableBody(String provider) {
        LOG.warn("payment_webhook_body_unreadable provider={}", provider);
    }
}
