package com.flowcortex.billing.reconcile;

import com.flowcortex.billing.reconcile.ReconcileRepository.Measurement;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * What the reconciliation reports. Tags are fixed words; log lines carry payment ids, classes, resolutions,
 * evidence references and exception class names, and never an amount, a user id, a provider message or a
 * payload value.
 */
@Component
class ReconcileTelemetry {

    private static final Logger LOG = LoggerFactory.getLogger(ReconcileTelemetry.class);

    private final MeterRegistry meters;
    private final AtomicLong unresolved = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();
    private final AtomicLong escalated = new AtomicLong();

    ReconcileTelemetry(MeterRegistry meters) {
        this.meters = meters;
        Gauge.builder("payment.reconcile.unresolved", unresolved, AtomicLong::get).register(meters);
        Gauge.builder("payment.reconcile.unresolved.oldest_age_seconds", oldestAgeSeconds, AtomicLong::get)
                .register(meters);
        Gauge.builder("payment.reconcile.escalated", escalated, AtomicLong::get).register(meters);
    }

    void measured(Measurement measurement) {
        unresolved.set(measurement.unresolved());
        oldestAgeSeconds.set(measurement.oldestAgeSeconds());
        escalated.set(measurement.escalated());
    }

    void measureFailed(Throwable error) {
        LOG.error("Reconcile measurement failed: event=payment.reconcile.measure_failed error={}",
                error.getClass().getSimpleName());
    }

    /** A pass could not read what it was to work on, so it handled nothing. */
    void passFailed(String pass, Throwable error) {
        LOG.error("Reconcile pass failed: event=payment.reconcile.pass_failed pass={} error={}", pass,
                error.getClass().getSimpleName());
    }

    /** Counts one handled item; {@code outcome} is the row's outcome, or {@code failed}. */
    void item(String pass, String outcome) {
        meters.counter("payment.reconcile.item", "pass", pass, "outcome", outcome).increment();
    }

    /** An item whose transaction failed: nothing of it was kept. */
    void itemFailed(String pass, UUID paymentId, Throwable error) {
        item(pass, "failed");
        LOG.error("Reconcile item failed: event=payment.reconcile.item_failed pass={} payment_id={} error={}", pass,
                paymentId, error.getClass().getSimpleName());
    }

    /** A disagreement that was recorded: counted, and an ERROR when it needs a person, else a WARN. */
    void drift(String pass, UUID paymentId, DriftClass driftClass, String resolution, String evidenceRef,
            boolean needsPerson) {
        meters.counter("payment.reconcile.drift", "class", driftClass.wire(), "resolution", resolution).increment();
        if (needsPerson) {
            LOG.error("Reconcile drift needs a person: event=payment.reconcile.drift pass={} payment_id={} class={}"
                    + " resolution={} evidence={}", pass, paymentId, driftClass.wire(), resolution, evidenceRef);
        } else {
            LOG.warn("Reconcile drift: event=payment.reconcile.drift pass={} payment_id={} class={}"
                    + " resolution={} evidence={}", pass, paymentId, driftClass.wire(), resolution, evidenceRef);
        }
    }

    /** A payment handed to a person because it stayed unresolved to the provider's 31-day window. */
    void ceilingEscalation(UUID paymentId) {
        meters.counter("payment.reconcile.escalation", "pass", "ageing", "reason", "ceiling").increment();
        LOG.error("Reconcile escalation: event=payment.reconcile.escalation reason=ceiling payment_id={}",
                paymentId);
    }

    /** A payment handed to a person because of a disagreement; its ERROR line is the drift line. */
    void discrepancyEscalation(String pass) {
        meters.counter("payment.reconcile.escalation", "pass", pass, "reason", "discrepancy").increment();
    }

    void pass(String pass, String outcome, Duration elapsed) {
        Timer.builder("payment.reconcile.pass").tag("pass", pass).tag("outcome", outcome).register(meters)
                .record(elapsed);
    }
}
