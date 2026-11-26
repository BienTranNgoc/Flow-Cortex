package com.flowcortex.billing.entitlement;

import com.flowcortex.jobs.api.ClusterJob;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Makes each member's recorded next-cycle plan the held plan once its period has begun, earliest period first.
 * Each member is activated in a transaction of its own under the member lock, so one failing member never
 * holds back the others.
 */
@Component
@EnableConfigurationProperties(EntitlementActivationProperties.class)
public class PlanActivation implements ClusterJob {

    private final EntitlementRepository repository;
    private final PlanActivator activator;
    private final EntitlementTelemetry telemetry;
    private final EntitlementActivationProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;

    PlanActivation(EntitlementRepository repository, PlanActivator activator, EntitlementTelemetry telemetry,
            EntitlementActivationProperties properties, Clock clock, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.activator = activator;
        this.telemetry = telemetry;
        this.properties = properties;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public String name() {
        return "billing.plan-activation";
    }

    @Override
    public Duration interval() {
        return properties.interval();
    }

    @Override
    public void run() {
        runAt(clock.instant());
    }

    /** One pass as of {@code now}; returns how many members were activated, zero while switched off. */
    public int runAt(Instant now) {
        if (!properties.enabled()) {
            return 0;
        }
        int activated = 0;
        for (UUID userId : repository.dueHoldings(now, properties.batchSize())) {
            try {
                if (Boolean.TRUE.equals(transactions.execute(status -> activate(userId, now)))) {
                    activated++;
                }
            } catch (RuntimeException e) {
                telemetry.activationFailed(e);
            }
        }
        return activated;
    }

    private boolean activate(UUID userId, Instant now) {
        repository.lockMember(userId);
        if (activator.activateDue(userId, now).isEmpty()) {
            return false;
        }
        telemetry.activated("job");
        return true;
    }
}
