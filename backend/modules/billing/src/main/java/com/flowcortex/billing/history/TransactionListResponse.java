package com.flowcortex.billing.history;

import com.flowcortex.billing.history.TransactionHistoryReader.Page;
import com.flowcortex.billing.history.TransactionHistoryReader.Row;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One newest-first page of the member's payments; {@code meta.total} counts every payment the filter matches. */
record TransactionListResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) List<TransactionRow> data,
        @Schema(requiredMode = RequiredMode.REQUIRED) TransactionListMeta meta) {

    /** One payment of the list. */
    record TransactionRow(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"plan_purchase", "topup"}) String kind,
            @Schema(requiredMode = RequiredMode.REQUIRED) String description,
            @Schema(requiredMode = RequiredMode.REQUIRED) long amountVnd,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"pending", "succeeded", "failed",
                "expired", "partially_refunded", "refunded", "disputed"}) String status,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant openedAt,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) Instant succeededAt) {

        static TransactionRow from(Row row) {
            return new TransactionRow(row.id(), row.kind(), row.description(), row.amountVnd(), row.status(),
                    row.openedAt(), row.succeededAt());
        }
    }

    /** Where the page sits: {@code page} counts from 0. */
    record TransactionListMeta(
            @Schema(requiredMode = RequiredMode.REQUIRED) long total,
            @Schema(requiredMode = RequiredMode.REQUIRED) int page,
            @Schema(requiredMode = RequiredMode.REQUIRED) int size) {
    }

    static TransactionListResponse from(Page page, HistoryQuery query) {
        return new TransactionListResponse(page.rows().stream().map(TransactionRow::from).toList(),
                new TransactionListMeta(page.total(), query.page(), query.size()));
    }
}
