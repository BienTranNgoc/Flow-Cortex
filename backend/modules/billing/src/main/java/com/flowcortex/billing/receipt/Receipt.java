package com.flowcortex.billing.receipt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The receipt of one collected payment. Every amount is read from the split stored at collection, never
 * recomputed, so a later change of the VAT rate leaves a past receipt as it was.
 */
public record Receipt(UUID transactionId, String orderRef, String kind, Instant paidAt, String provider,
        String instrument, String providerTransactionId, Seller seller, List<Line> lines, long subtotalVnd,
        int vatRateBps, long vatAmountVnd, long totalVnd, String currency, String totalInWords) {

    public Receipt {
        lines = List.copyOf(lines);
    }

    /** The name the PDF is saved and mailed under. */
    String pdfFileName() {
        return "bien-nhan-" + orderRef + ".pdf";
    }

    /** The provider of the goods; every field is null when not configured. */
    public record Seller(String legalName, String taxCode, String address, String email) {

        boolean isSet() {
            return legalName != null;
        }
    }

    /** One purchased line; the amount is before tax. */
    public record Line(String description, int quantity, long unitPriceVnd, long amountVnd) {
    }
}
