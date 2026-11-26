package com.flowcortex.billing.entitlement;

import java.util.List;
import java.util.function.Supplier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/**
 * After startup, reports every priced cycle of an active tier whose shortest period does not outlast the longest
 * grace window. It only reports: a shortfall never stops startup, and an unreadable catalogue is logged and left
 * unchecked.
 */
class PlanPeriodCheck {

    /** A cycle the catalogue sells for a tier. */
    record SoldCycle(String tierId, String cycle) {
    }

    private final Supplier<List<SoldCycle>> soldCycles;
    private final EntitlementTelemetry telemetry;

    PlanPeriodCheck(Supplier<List<SoldCycle>> soldCycles, EntitlementTelemetry telemetry) {
        this.soldCycles = soldCycles;
        this.telemetry = telemetry;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        run();
    }

    void run() {
        List<SoldCycle> sold;
        try {
            sold = soldCycles.get();
        } catch (RuntimeException e) {
            telemetry.catalogueUnreadable(e);
            return;
        }
        long graceDays = PlanPeriods.graceDays("auto_debit");
        for (SoldCycle soldCycle : sold) {
            long periodDays = PlanPeriodRule.shortestPeriodDays(soldCycle.cycle());
            PlanPeriodRule.shortfall(soldCycle.tierId(), periodDays, graceDays)
                    .ifPresent(shortfall -> telemetry.shortfall(soldCycle.cycle(), shortfall));
        }
    }
}
