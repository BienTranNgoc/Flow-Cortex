package com.flowcortex.billing.tax;

import java.time.LocalDate;

/** No VAT rate row covers the day; a rate is never guessed. */
public class VatRateUnavailableException extends RuntimeException {

    private final String jurisdiction;
    private final String category;
    private final LocalDate day;

    VatRateUnavailableException(String jurisdiction, String category, LocalDate day) {
        super("No VAT rate covers " + day + " for " + jurisdiction + "/" + category);
        this.jurisdiction = jurisdiction;
        this.category = category;
        this.day = day;
    }

    public String jurisdiction() {
        return jurisdiction;
    }

    public String category() {
        return category;
    }

    public LocalDate day() {
        return day;
    }
}
