package com.flowcortex.billing.tax;

/** Splits a tax-inclusive amount into base and tax in whole đồng; the tax is the remainder, so the two sum to the gross. */
public final class TaxSplit {

    private static final long BPS_UNIT = 10_000;

    /** The base (price without tax) and the tax of a gross amount. */
    public record Split(long baseVnd, long taxVnd) {
    }

    private TaxSplit() {
    }

    /**
     * {@code base = round-half-up(gross × 10,000 ÷ (10,000 + rateBps))}, {@code tax = gross − base}.
     *
     * @throws IllegalArgumentException for a negative gross or rate
     * @throws ArithmeticException when the arithmetic overflows a {@code long}
     */
    public static Split inclusive(long grossVnd, int rateBps) {
        if (grossVnd < 0 || rateBps < 0) {
            throw new IllegalArgumentException("The gross amount and the rate must not be negative");
        }
        long divisor = Math.addExact(BPS_UNIT, rateBps);
        long numerator = Math.multiplyExact(Math.multiplyExact(2L, grossVnd), BPS_UNIT);
        long base = Math.addExact(numerator, divisor) / Math.multiplyExact(2L, divisor);
        return new Split(base, grossVnd - base);
    }
}
