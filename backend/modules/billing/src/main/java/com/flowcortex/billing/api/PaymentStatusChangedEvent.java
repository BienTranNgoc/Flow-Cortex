package com.flowcortex.billing.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A payment's money status moved. Published in the transaction of the move, so a listener in another module
 * receives it after commit, at least once, and re-reads the payment for anything it needs beyond these
 * facts. Statuses are wire values ({@code pending}, {@code succeeded}, {@code failed}, {@code expired}, ...).
 * It carries no provider payload, message, contact detail or credential.
 *
 * @param kind {@code plan_purchase} or {@code topup}
 * @param itemRef the caller's reference to what was bought
 * @param amountVnd the payment amount in đồng
 * @param evidenceRef what caused the move: a callback id, or {@code query:<request reference>}
 * @param changedAt when the move was applied
 */
public record PaymentStatusChangedEvent(
        UUID transactionId,
        UUID userId,
        String kind,
        String itemRef,
        long amountVnd,
        String fromStatus,
        String toStatus,
        String evidenceRef,
        Instant changedAt) {
}
