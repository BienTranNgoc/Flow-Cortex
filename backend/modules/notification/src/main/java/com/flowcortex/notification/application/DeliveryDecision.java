package com.flowcortex.notification.application;

/** What happens to one delivery: one table, one outcome. A gone recipient outranks a preference. */
final class DeliveryDecision {

    enum Outcome { STORE, SUPPRESSED, RECIPIENT_GONE }

    private DeliveryDecision() {
    }

    static Outcome decide(boolean recipientExists, boolean inAppEnabled) {
        if (!recipientExists) {
            return Outcome.RECIPIENT_GONE;
        }
        return inAppEnabled ? Outcome.STORE : Outcome.SUPPRESSED;
    }
}
