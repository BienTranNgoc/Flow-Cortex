package com.flowcortex.billing.history;

import com.flowcortex.billing.history.TransactionHistoryReader.Detail;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One payment of the member with the facts the provider reported and its timeline. {@code outcomeCode} is the
 * provider's code when the payment ended failed or expired, else null.
 */
record TransactionDetailResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"plan_purchase", "topup"}) String kind,
        @Schema(requiredMode = RequiredMode.REQUIRED) String description,
        @Schema(requiredMode = RequiredMode.REQUIRED) long amountVnd,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"pending", "succeeded", "failed",
            "expired", "partially_refunded", "refunded", "disputed"}) String status,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant openedAt,
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) Instant succeededAt,
        @Schema(requiredMode = RequiredMode.REQUIRED) String orderRef,
        @Schema(requiredMode = RequiredMode.REQUIRED) String provider,
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true,
                allowableValues = {"wallet", "domestic_card"}) String instrument,
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) String providerPayType,
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) String providerTransactionId,
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) String outcomeCode,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<TransactionTimelineEvent> timeline) {

    /** One line of the timeline; {@code code} is the provider's code for a failure or an expiry. */
    record TransactionTimelineEvent(
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant at,
            @Schema(requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"opened", "paid", "payment_failed", "payment_expired"}) String kind,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) String code) {
    }

    static TransactionDetailResponse from(Detail detail) {
        return new TransactionDetailResponse(detail.id(), detail.kind(), detail.description(), detail.amountVnd(),
                detail.status(), detail.openedAt(), detail.succeededAt(), detail.orderRef(), detail.provider(),
                detail.instrument(), detail.providerPayType(), detail.providerTransactionId(), detail.outcomeCode(),
                detail.timeline().stream()
                        .map(event -> new TransactionTimelineEvent(event.at(), event.kind(), event.code()))
                        .toList());
    }
}
