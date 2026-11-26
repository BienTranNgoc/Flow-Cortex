package com.flowcortex.billing.entitlement;

import com.flowcortex.billing.entitlement.EntitlementRepository.DuePending;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Makes a member's recorded next-cycle plan the held plan once its period has begun. The caller holds the
 * member lock and a transaction; the job and the grant both go through here so the rule has one home.
 */
@Component
class PlanActivator {

    private final EntitlementRepository repository;

    PlanActivator(EntitlementRepository repository) {
        this.repository = repository;
    }

    /**
     * Activates the member's due next-cycle plan and appends its history entry; empty when none is due, or when
     * the holding no longer records that payment as next.
     *
     * @return the tier that became the held plan
     */
    Optional<String> activateDue(UUID userId, Instant now) {
        Optional<DuePending> due = repository.findDuePending(userId, now);
        if (due.isEmpty() || repository.activate(userId, due.get(), now) != 1) {
            return Optional.empty();
        }
        DuePending activated = due.get();
        repository.appendEvent(userId, activated.transactionId(), activated.fromTierId(), activated.tierId(),
                activated.fromStatus(), "active", "renewal_plan_activated:" + activated.tierId(), now);
        return Optional.of(activated.tierId());
    }
}
