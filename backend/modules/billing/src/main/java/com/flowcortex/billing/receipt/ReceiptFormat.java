package com.flowcortex.billing.receipt;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * How a receipt prints money, a rate and the paid time. Money groups with dots and the time is Vietnam
 * time in both languages, so the page, the PDF and the mail read the same.
 */
final class ReceiptFormat {

    private static final DateTimeFormatter PAID_AT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private ReceiptFormat() {
    }

    /** {@code 100.000 ₫}. */
    static String vnd(long amountVnd) {
        String digits = Long.toString(Math.abs(amountVnd));
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                grouped.append('.');
            }
            grouped.append(digits.charAt(i));
        }
        return (amountVnd < 0 ? "-" : "") + grouped + " ₫";
    }

    /** A rate in basis points as a percent with the language's decimal mark: {@code 10%}, {@code 8,5%}. */
    static String percent(int rateBps, Locale locale) {
        String number = BigDecimal.valueOf(rateBps, 2).stripTrailingZeros().toPlainString();
        return ("vi".equals(locale.getLanguage()) ? number.replace('.', ',') : number) + "%";
    }

    /** {@code 01/01/2027 00:05 (GMT+7)}. */
    static String paidAt(Instant paidAt) {
        return PAID_AT.format(paidAt) + " (GMT+7)";
    }
}
