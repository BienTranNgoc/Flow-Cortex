package com.flowcortex.billing.receipt;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The receipt of a collected payment; amounts are whole đồng and the line amount is before tax. The seller's
 * fields are null until the environment configures them.
 */
@Schema(name = "BillingReceiptResponse")
record ReceiptResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID transactionId,
        @Schema(requiredMode = RequiredMode.REQUIRED) String orderRef,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"plan_purchase", "topup"}) String kind,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant paidAt,
        @Schema(requiredMode = RequiredMode.REQUIRED) String provider,
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true,
                allowableValues = {"wallet", "domestic_card"}) String instrument,
        @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) String providerTransactionId,
        @Schema(requiredMode = RequiredMode.REQUIRED) SellerResponse seller,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<LineResponse> lines,
        @Schema(requiredMode = RequiredMode.REQUIRED) long subtotalVnd,
        @Schema(requiredMode = RequiredMode.REQUIRED) int vatRateBps,
        @Schema(requiredMode = RequiredMode.REQUIRED) long vatAmountVnd,
        @Schema(requiredMode = RequiredMode.REQUIRED) long totalVnd,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"VND"}) String currency,
        @Schema(requiredMode = RequiredMode.REQUIRED) String totalInWords) {

    static ReceiptResponse from(Receipt receipt) {
        Receipt.Seller seller = receipt.seller();
        return new ReceiptResponse(receipt.transactionId(), receipt.orderRef(), receipt.kind(), receipt.paidAt(),
                receipt.provider(), receipt.instrument(), receipt.providerTransactionId(),
                new SellerResponse(seller.legalName(), seller.taxCode(), seller.address(), seller.email()),
                receipt.lines().stream()
                        .map(line -> new LineResponse(line.description(), line.quantity(), line.unitPriceVnd(),
                                line.amountVnd()))
                        .toList(),
                receipt.subtotalVnd(), receipt.vatRateBps(), receipt.vatAmountVnd(), receipt.totalVnd(),
                receipt.currency(), receipt.totalInWords());
    }

    /** The provider of the goods. */
    @Schema(name = "BillingReceiptSeller")
    record SellerResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) String legalName,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) String taxCode,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) String address,
            @Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) String email) {
    }

    /** One purchased line. */
    @Schema(name = "BillingReceiptLine")
    record LineResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) String description,
            @Schema(requiredMode = RequiredMode.REQUIRED) int quantity,
            @Schema(requiredMode = RequiredMode.REQUIRED) long unitPriceVnd,
            @Schema(requiredMode = RequiredMode.REQUIRED) long amountVnd) {
    }
}
