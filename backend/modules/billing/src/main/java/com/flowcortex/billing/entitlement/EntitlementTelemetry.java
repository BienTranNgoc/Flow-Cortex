package com.flowcortex.billing.entitlement;

import com.flowcortex.billing.entitlement.PlanPeriodRule.Shortfall;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * What the plan grant reports. Tags are fixed words and no line carries a member id, a tier of a member or an
 * amount. Grant and activation outcomes are recorded after the surrounding transaction commits, so an attempt
 * the ledger rolls back and retries counts and logs nothing.
 */
@Component
class EntitlementTelemetry {

    private static final Logger LOG = LoggerFactory.getLogger(EntitlementTelemetry.class);

    private final MeterRegistry meters;

    EntitlementTelemetry(MeterRegistry meters) {
        this.meters = meters;
    }

    /** One grant outcome; a refusal is also logged at ERROR with the payment id. */
    void decided(String outcome, String reason, UUID paymentId) {
        afterCommit(() -> {
            meters.counter("billing.entitlement.decision", "outcome", outcome, "reason", reason).increment();
            if ("refused".equals(outcome)) {
                LOG.error("billing_entitlement_refused payment_id={} reason={}", paymentId, reason);
            }
        });
    }

    /** One next-cycle plan made the held plan, by {@code job} or by the paying member's {@code grant}. */
    void activated(String trigger) {
        afterCommit(() -> meters.counter("billing.entitlement.activation", "trigger", trigger).increment());
    }

    void shortfall(String cycle, Shortfall shortfall) {
        meters.counter("billing.plan_period.shortfall", "cycle", cycle).increment();
        LOG.error("billing_plan_period_shortfall tier={} cycle={} period_days={} grace_days={}", shortfall.tierId(),
                cycle, shortfall.periodDays(), shortfall.graceDays());
    }

    /** One member's activation failed; the pass goes on and the member is tried again next pass. */
    void activationFailed(RuntimeException failure) {
        LOG.error("billing_plan_activation outcome=failed error={}", failure.getClass().getSimpleName());
    }

    void catalogueUnreadable(RuntimeException failure) {
        LOG.error("billing_plan_period_check outcome=unreadable error={}", failure.getClass().getSimpleName());
    }

    private static void afterCommit(Runnable record) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            record.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                record.run();
            }
        });
    }
}
