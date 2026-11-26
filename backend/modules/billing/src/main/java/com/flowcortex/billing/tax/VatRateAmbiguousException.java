package com.flowcortex.billing.tax;

import java.time.LocalDate;

/** More than one VAT rate row covers the day; the timeline is broken and nothing is chosen between them. */
public class VatRateAmbiguousException extends RuntimeException {

    private final String jurisdiction;
    private final String category;
    private final LocalDate day;

    VatRateAmbiguousException(String jurisdiction, String category, LocalDate day) {
        super("More than one VAT rate covers " + day + " for " + jurisdiction + "/" + category);
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
