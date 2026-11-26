package com.flowcortex.billing.entitlement;

import com.flowcortex.billing.entitlement.EntitlementRepository.HeldRow;
import com.flowcortex.billing.entitlement.EntitlementRepository.PlanPurchase;
import com.flowcortex.billing.entitlement.GrantRule.Holding;
import com.flowcortex.billing.entitlement.GrantRule.Outcome;
import com.flowcortex.billing.entitlement.PlanPeriods.Period;
import com.flowcortex.billing.ledger.PlanItemRef;
import com.flowcortex.billing.ledger.SettledPlanPurchase;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Pins the period a settled plan purchase bought and grants the member's holding from it, inside the
 * transaction that moved the payment to {@code succeeded}. A purchase that cannot be granted is refused with a
 * recorded reason and returns normally, so the money move stands; any other failure propagates and rolls the
 * move back with it. A refused purchase stores no period, so it never counts toward the member's schedule.
 */
@Component
class EntitlementGrant implements SettledPlanPurchase {

    static final String PLAN_UNRECORDED = "plan_unrecorded";

    private final EntitlementRepository repository;
    private final PlanActivator activator;
    private final EntitlementTelemetry telemetry;

    EntitlementGrant(EntitlementRepository repository, PlanActivator activator, EntitlementTelemetry telemetry) {
        this.repository = repository;
        this.activator = activator;
        this.telemetry = telemetry;
    }

    @Override
    public void onSucceeded(UUID transactionId, Instant settledAt) {
        PlanPurchase purchase = repository.findPlanPurchase(transactionId)
                .orElseThrow(() -> new IllegalStateException("A payment that moved to succeeded cannot be read"));
        if (!"plan_purchase".equals(purchase.kind())) {
            return;
        }
        UUID userId = purchase.userId();
        repository.lockMember(userId);
        if (activator.activateDue(userId, settledAt).isPresent()) {
            telemetry.activated("grant");
        }
        Optional<PlanItemRef> plan = PlanItemRef.parse(purchase.itemRef())
                .filter(ref -> repository.planSold(ref.tierId(), ref.cycle()));
        if (plan.isEmpty()) {
            refuse(userId, transactionId, PLAN_UNRECORDED, settledAt);
            return;
        }
        Optional<HeldRow> held = repository.findHolding(userId);
        Optional<Holding> holding = held.map(HeldRow::holding);
        Instant heldGrantedSucceededAt = held.map(HeldRow::grantedSucceededAt).orElse(null);
        Instant scheduleEnd = PlanPeriods.later(holding.map(Holding::renewsAt).orElse(null),
                repository.furthestUnreflected(userId, heldGrantedSucceededAt));
        Period period = PlanPeriods.pin(settledAt, scheduleEnd, holding.map(Holding::mode).orElse("manual"),
                plan.get().cycle());
        Outcome outcome = GrantRule.decide(holding, period, settledAt, transactionId, heldGrantedSucceededAt);
        if (outcome instanceof GrantRule.Refused refused) {
            refuse(userId, transactionId, refused.reason(), settledAt);
            return;
        }
        if (repository.pinPeriod(transactionId, period) != 1) {
            throw new IllegalStateException("The payment already holds a period");
        }
        grant(outcome, userId, plan.get(), period, holding, transactionId, settledAt);
    }

    private void grant(Outcome outcome, UUID userId, PlanItemRef plan, Period period, Optional<Holding> holding,
            UUID transactionId, Instant now) {
        String tier = plan.tierId();
        switch (outcome) {
            case GrantRule.Open open -> {
                repository.open(userId, plan, period, transactionId, now);
                repository.appendEvent(userId, transactionId, null, tier, null, "active", "settled_payment:" + tier, now);
                telemetry.decided("granted", "none", transactionId);
            }
            case GrantRule.Move move -> {
                requireOneRow(repository.move(userId, plan, period, transactionId, now, now));
                Holding before = holding.orElseThrow();
                repository.appendEvent(userId, transactionId, before.tierId(), tier, before.status(), "active",
                        "settled_payment:" + tier, now);
                telemetry.decided("granted", "none", transactionId);
            }
            case GrantRule.RecordNext next -> {
                requireOneRow(repository.recordNext(userId, plan, transactionId, now));
                repository.appendEvent(userId, transactionId, null, tier, null, null, "renewal_plan_selected:" + tier,
                        now);
                telemetry.decided("pending_recorded", "none", transactionId);
            }
            case GrantRule.Superseded superseded -> telemetry.decided("superseded", "none", transactionId);
            case GrantRule.Refused refused -> throw new IllegalStateException("A refusal stores no period");
        }
    }

    private void refuse(UUID userId, UUID transactionId, String reason, Instant now) {
        repository.appendEvent(userId, transactionId, null, null, null, null, "refused:" + reason, now);
        telemetry.decided("refused", reason, transactionId);
    }

    private static void requireOneRow(int changed) {
        if (changed != 1) {
            throw new IllegalStateException("The holding is no longer what the grant read under the member lock");
        }
    }
}
