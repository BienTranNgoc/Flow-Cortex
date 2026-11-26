package com.flowcortex.billing.receipt;

import com.flowcortex.billing.receipt.Receipt.Seller;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code flowcortex.billing.receipt.*}: the configured layer of the console setting
 * {@code payment.receipt.email_enabled} (on by default) and the seller identity a receipt prints. A blank
 * seller value is absent.
 */
@ConfigurationProperties("flowcortex.billing.receipt")
public record ReceiptProperties(Boolean emailEnabled, SellerProperties seller) {

    public ReceiptProperties {
        emailEnabled = emailEnabled == null || emailEnabled;
        seller = seller == null ? new SellerProperties(null, null, null, null) : seller;
    }

    /** The seller block as configured. */
    public record SellerProperties(String legalName, String taxCode, String address, String email) {
    }

    Seller sellerBlock() {
        return new Seller(blankToNull(seller.legalName()), blankToNull(seller.taxCode()),
                blankToNull(seller.address()), blankToNull(seller.email()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
