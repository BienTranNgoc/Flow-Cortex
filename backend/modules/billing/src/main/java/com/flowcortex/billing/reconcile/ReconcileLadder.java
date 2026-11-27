package com.flowcortex.billing.reconcile;

import java.time.Duration;
import java.time.Instant;

/**
 * When a payment still unresolved is asked about again: the gap doubles from the floor after every ask and
 * never exceeds four days, and the provider's request identifiers stop being valid after 31 days.
 */
final class ReconcileLadder {

    static final Duration CEILING = Duration.ofDays(31);
    static final Duration MAX_GAP = Duration.ofDays(4);
    static final int MAX_DOUBLINGS = 20;

    private ReconcileLadder() {
    }

    static Instant firstAskAt(Instant opened, Duration floor) {
        return opened.plus(floor);
    }

    /** The wait after the {@code asks}-th ask: {@code floor × 2^asks}, at most {@link #MAX_GAP}. */
    static Duration intervalAfter(int asks, Duration floor) {
        if (asks < 0) {
            throw new IllegalArgumentException("asks must not be negative: " + asks);
        }
        if (asks > MAX_DOUBLINGS) {
            return MAX_GAP;
        }
        Duration gap = floor.multipliedBy(1L << asks);
        return gap.compareTo(MAX_GAP) > 0 ? MAX_GAP : gap;
    }

    static Instant nextAskAt(Instant lastAsk, int asks, Duration floor) {
        return lastAsk.plus(intervalAfter(asks, floor));
    }

    static boolean pastCeiling(Instant opened, Instant now) {
        return !now.isBefore(opened.plus(CEILING));
    }
}
