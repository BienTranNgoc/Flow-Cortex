package com.flowcortex.billing.receipt;

import java.util.Locale;
import org.springframework.context.MessageSource;

/** The labels of the receipt PDF in one language, read from the message bundle. */
final class ReceiptCopy {

    private static final String PREFIX = "billing.receipt.pdf.";

    private final MessageSource messages;
    private final Locale locale;

    ReceiptCopy(MessageSource messages, Locale locale) {
        this.messages = messages;
        this.locale = locale;
    }

    Locale locale() {
        return locale;
    }

    String brand() {
        return text("brand");
    }

    String title() {
        return text("title");
    }

    String orderRef() {
        return text("orderRef");
    }

    String paidAt() {
        return text("paidAt");
    }

    String method() {
        return text("method");
    }

    String transactionId(String provider) {
        return text("transactionId", providerName(provider));
    }

    String provider() {
        return text("provider");
    }

    String providerUnset() {
        return text("providerUnset");
    }

    String taxCode() {
        return text("taxCode");
    }

    String product() {
        return text("product");
    }

    String quantity() {
        return text("quantity");
    }

    String unitPrice() {
        return text("unitPrice");
    }

    String amount() {
        return text("amount");
    }

    String subtotal() {
        return text("subtotal");
    }

    String vat(int rateBps) {
        return text("vat", ReceiptFormat.percent(rateBps, locale));
    }

    String total() {
        return text("total");
    }

    String inWords() {
        return text("inWords");
    }

    String note() {
        return text("note");
    }

    /** The instrument's name when the bundle knows it, else the provider's, else the raw provider code. */
    String methodName(String provider, String instrument) {
        if (instrument != null) {
            String named = find("method." + provider + "." + instrument);
            if (named != null) {
                return named;
            }
        }
        return providerName(provider);
    }

    private String providerName(String provider) {
        String named = find("method." + provider);
        return named == null ? provider : named;
    }

    private String text(String key, Object... args) {
        return messages.getMessage(PREFIX + key, args, locale);
    }

    private String find(String key) {
        return messages.getMessage(PREFIX + key, null, null, locale);
    }
}
