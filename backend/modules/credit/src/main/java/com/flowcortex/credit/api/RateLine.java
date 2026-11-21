package com.flowcortex.credit.api;

import java.util.Map;

/**
 * One priced line: the full rate-card key plus the quantity consumed. {@code modelKey} and
 * {@code dimensions} are null when the key has none.
 */
public record RateLine(
        String rateCardRef,
        PricingUnit pricingUnit,
        String providerKey,
        String modelKey,
        Map<String, Object> dimensions,
        RateQuantity quantity) {

    public RateLine {
        if (rateCardRef == null || rateCardRef.isBlank()) {
            throw new IllegalArgumentException("rateCardRef must not be blank");
        }
        if (providerKey == null || providerKey.isBlank()) {
            throw new IllegalArgumentException("providerKey must not be blank");
        }
        if (pricingUnit == null) {
            throw new IllegalArgumentException("pricingUnit must not be null");
        }
        if (quantity == null) {
            throw new IllegalArgumentException("quantity must not be null");
        }
        dimensions = dimensions == null ? null : Map.copyOf(dimensions);
    }
}
