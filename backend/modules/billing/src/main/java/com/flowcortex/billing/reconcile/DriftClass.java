package com.flowcortex.billing.reconcile;

/** How our record and the provider's account of a payment disagree. */
enum DriftClass {
    MISSED_CONFIRMATION("missed_confirmation"),
    FALSE_POSITIVE("false_positive"),
    AMOUNT_MISMATCH("amount_mismatch"),
    NEVER_ARRIVED("never_arrived");

    private final String wire;

    DriftClass(String wire) {
        this.wire = wire;
    }

    String wire() {
        return wire;
    }
}
