package com.flowcortex.billing.catalog;

import com.flowcortex.billing.catalog.TierOrder.OrderableTier;
import com.flowcortex.billing.catalog.TierOrder.TierOrderViolation;
import com.flowcortex.jobs.api.ClusterJob;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * Reports a catalogue that breaks the tier-order rule: once per instance boot, and daily as a cluster job so the
 * daily counters measure violations, not violations times replicas. It only reports: a violation never stops
 * startup and never changes a catalogue answer, and an unreadable catalogue is recorded and tried again next pass.
 */
class CatalogueCheck implements ClusterJob {

    private static final Logger LOG = LoggerFactory.getLogger(CatalogueCheck.class);

    private final Supplier<List<OrderableTier>> tiers;
    private final CatalogueTelemetry telemetry;
    private final CatalogueCheckProperties properties;

    CatalogueCheck(Supplier<List<OrderableTier>> tiers, CatalogueTelemetry telemetry,
            CatalogueCheckProperties properties) {
        this.tiers = tiers;
        this.telemetry = telemetry;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "billing.catalogue-check";
    }

    @Override
    public Duration interval() {
        return properties.interval();
    }

    @Override
    public void run() {
        run("scheduled");
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        run("startup");
    }

    /** One pass; returns the recorded outcome, or {@code disabled} when the check is switched off. */
    String run(String trigger) {
        if (!properties.enabled()) {
            return "disabled";
        }
        long started = System.nanoTime();
        String outcome;
        try {
            List<TierOrderViolation> violations = TierOrder.violations(tiers.get());
            for (TierOrderViolation violation : violations) {
                LOG.error("billing_catalogue_violation trigger={} {}", trigger, TierOrder.describe(violation));
                telemetry.recordViolation(violation.kind());
            }
            outcome = violations.isEmpty() ? "clean" : "violations";
        } catch (RuntimeException e) {
            LOG.error("billing_catalogue_check trigger={} outcome=unreadable error={}", trigger,
                    e.getClass().getSimpleName());
            outcome = "unreadable";
        }
        telemetry.recordCheck(trigger, outcome, Duration.ofNanos(System.nanoTime() - started));
        return outcome;
    }
}
