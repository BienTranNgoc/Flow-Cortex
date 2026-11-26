package com.flowcortex.billing.receipt;

import java.time.Duration;

/** What to do with a receipt mail whose send failed: try again later, or stop after the sixth attempt. */
sealed interface ReceiptRetry {

    int MAX_ATTEMPTS = 6;
    Duration DELAY = Duration.ofMinutes(30);

    /** Due again after {@code delay}. */
    record RetryAt(Duration delay) implements ReceiptRetry {
    }

    /** No further attempt. */
    record GiveUp() implements ReceiptRetry {
    }

    /** The decision after {@code attempts} sends, the one just failed included. */
    static ReceiptRetry after(int attempts) {
        if (attempts < 1) {
            throw new IllegalArgumentException("A failure follows at least one attempt: " + attempts);
        }
        return attempts < MAX_ATTEMPTS ? new RetryAt(DELAY) : new GiveUp();
    }
}
