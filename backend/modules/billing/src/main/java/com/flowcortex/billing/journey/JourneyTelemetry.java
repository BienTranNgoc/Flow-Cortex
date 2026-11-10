package com.flowcortex.billing.journey;

import com.flowcortex.billing.journey.PaymentJourneyEvent.PayerReturned;
import com.flowcortex.billing.journey.PaymentJourneyEvent.PaymentOpened;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The journey stream. No method takes a principal, an address or raw bytes, so a line cannot name the payer;
 * every logged value is a minted shape, an identifier checked by pattern or a fixed word, so none can carry
 * a newline or caller text.
 */
@Component
class JourneyTelemetry {

    private static final Logger LOG = LoggerFactory.getLogger(JourneyTelemetry.class);

    private final Counter refused;

    JourneyTelemetry(MeterRegistry meters) {
        this.refused = Counter.builder("payment.journey.refused")
                .description("Journey bodies refused whole with 400")
                .register(meters);
    }

    void opened(PaymentOpened event) {
        LOG.info("Payment journey: event=payment.journey.opened order_ref={} transaction_id={} plan_id={}"
                        + " package_id={} replayed={}",
                event.orderRef(), event.transactionId(), event.planId(), event.packageId(), event.replayed());
    }

    void returned(PayerReturned event) {
        LOG.info("Payment journey: event=payment.journey.returned order_ref={} order_ref_origin={} outcome={}",
                event.orderRef(), event.origin().value(), event.outcome().value());
    }

    void refused() {
        refused.increment();
        LOG.warn("Payment journey: event=payment.journey.refused");
    }
}
