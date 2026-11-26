package com.flowcortex.billing.ledger;

import com.flowcortex.billing.api.PaymentStatusChangedEvent;
import com.flowcortex.jobs.api.PublicationRetryPolicy;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Eight attempts in all, the next one a minute doubling up to an hour after the previous began, so a
 * listener that is down for a couple of hours still gets the event. Giving up loses an effect of a payment, so
 * it is critical, and the log line names the payment.
 */
@Component
class PaymentStatusChangedRetryPolicy implements PublicationRetryPolicy {

    private static final int MAX_ATTEMPTS = 8;
    private static final Duration FIRST_DELAY = Duration.ofMinutes(1);
    private static final Duration LONGEST_DELAY = Duration.ofMinutes(60);
    private static final int MAX_DOUBLINGS = 6;

    @Override
    public Class<?> eventType() {
        return PaymentStatusChangedEvent.class;
    }

    @Override
    public int maxAttempts() {
        return MAX_ATTEMPTS;
    }

    @Override
    public Duration delayAfter(int attempts) {
        int doublings = Math.min(Math.max(attempts, 1) - 1, MAX_DOUBLINGS);
        Duration delay = FIRST_DELAY.multipliedBy(1L << doublings);
        return delay.compareTo(LONGEST_DELAY) > 0 ? LONGEST_DELAY : delay;
    }

    @Override
    public boolean critical() {
        return true;
    }

    @Override
    public String describe(Object event) {
        PaymentStatusChangedEvent change = (PaymentStatusChangedEvent) event;
        return "transaction=" + change.transactionId() + " to=" + change.toStatus() + " evidence="
                + change.evidenceRef();
    }
}
