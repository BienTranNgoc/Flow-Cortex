package com.flowcortex.billing.tax;

/** A request to set the rate in force from today; a null or blank {@code sourceNote} is stored as no note. */
public record PublishVatRate(String jurisdiction, String category, int rateBps, String sourceNote) {
}
