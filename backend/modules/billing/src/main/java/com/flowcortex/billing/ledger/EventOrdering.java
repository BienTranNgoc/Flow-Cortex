package com.flowcortex.billing.ledger;

import java.time.Instant;
import java.util.Optional;

/** Orders provider events by the provider's own clock; ours is never mixed in. */
public final class EventOrdering {

    private EventOrdering() {
    }

    /**
     * Why an event cannot be applied after what the payment already recorded, or empty when it is in order.
     *
     * @param stated the timestamp the event states, or null
     * @param recorded the provider timestamp the payment holds, or null when none was recorded yet
     */
    public static Optional<String> refusal(Instant stated, Instant recorded) {
        if (recorded == null) {
            return Optional.empty();
        }
        if (stated == null) {
            return Optional.of("unordered_event");
        }
        if (stated.isBefore(recorded)) {
            return Optional.of("stale_event");
        }
        return Optional.empty();
    }
}
