package com.flowcortex.credit.api;

import java.math.BigDecimal;

/**
 * What one call consumes. Every field is optional: an omitted field means the unit's default quantity
 * (see {@link PricingUnit#units(RateQuantity)}).
 */
public record RateQuantity(
        Long requests,
        Long numImages,
        Long widthPx,
        Long heightPx,
        BigDecimal sec,
        Long chars,
        Long tokensIn,
        Long tokensOut,
        BigDecimal megapixelSeconds) {

    /** The quantity of a call that states nothing: one of everything countable, nothing metered. */
    public static final RateQuantity DEFAULT = new RateQuantity(null, null, null, null, null, null, null, null, null);
}
