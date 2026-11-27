package com.flowcortex.billing.reconcile;

import com.flowcortex.jobs.api.ClusterJob;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * The ladder pass over payments still pending, run on one instance at a time. The lease covers a pass's own
 * time budget and one provider call many times over, so no other instance takes over a pass still running.
 */
@Component
public class AgeingReconcileJob implements ClusterJob {

    private final PaymentReconciler reconciler;
    private final ReconcileProperties properties;

    AgeingReconcileJob(PaymentReconciler reconciler, ReconcileProperties properties) {
        this.reconciler = reconciler;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "billing.payment-reconcile-ageing";
    }

    @Override
    public Duration interval() {
        return properties.ageing().interval();
    }

    @Override
    public Duration lease() {
        return Duration.ofMinutes(5);
    }

    @Override
    public void run() {
        reconciler.ageingTick();
    }
}
