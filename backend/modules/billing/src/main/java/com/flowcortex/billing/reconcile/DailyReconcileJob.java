package com.flowcortex.billing.reconcile;

import com.flowcortex.jobs.api.ClusterJob;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * The backstop pass over payments whatever their status, run on one instance at a time in short batches so it
 * never holds the cluster runner for long; a payment is asked about again a day after it was last checked.
 */
@Component
public class DailyReconcileJob implements ClusterJob {

    private final PaymentReconciler reconciler;
    private final ReconcileProperties properties;

    DailyReconcileJob(PaymentReconciler reconciler, ReconcileProperties properties) {
        this.reconciler = reconciler;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "billing.payment-reconcile-daily";
    }

    @Override
    public Duration interval() {
        return properties.daily().interval();
    }

    @Override
    public Duration lease() {
        return Duration.ofMinutes(5);
    }

    @Override
    public void run() {
        reconciler.dailyTick();
    }
}
