package com.flowcortex.credit.domain;

import com.flowcortex.credit.api.PricingUnit;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One effective-dated price row. Its key is (ref, unit, provider, model, dimensions); {@code dimensions} and
 * {@code model} are null when the key has none. A row is active from {@code effectiveFrom} up to, not including,
 * {@code effectiveTo} (open-ended when null).
 */
public record RateCard(
        UUID id,
        String ref,
        PricingUnit unit,
        String provider,
        String model,
        Map<String, Object> dimensions,
        BigDecimal creditsPerUnit,
        BigDecimal markupPct,
        Instant effectiveFrom,
        Instant effectiveTo) {

    public boolean activeAt(Instant at) {
        return !effectiveFrom.isAfter(at) && (effectiveTo == null || at.isBefore(effectiveTo));
    }

    public boolean hasDimensions() {
        return dimensions != null && !dimensions.isEmpty();
    }

    /** Same price key, whatever the window; no model equals an empty model and no dimensions equal none. */
    public boolean sameKey(RateCard other) {
        return ref.equals(other.ref)
                && unit == other.unit
                && provider.equals(other.provider)
                && Objects.equals(model, other.model)
                && dimensionsEqual(dimensions, other.dimensions);
    }

    /** Dimension equality after JSON parsing: numbers compare by value, so {@code 720} equals {@code 720.0}. */
    public static boolean dimensionsEqual(Map<String, ?> a, Map<String, ?> b) {
        return valueEquals(a == null ? Map.of() : a, b == null ? Map.of() : b);
    }

    private static boolean valueEquals(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString())) == 0;
        }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (Map.Entry<?, ?> entry : x.entrySet()) {
                if (!y.containsKey(entry.getKey()) || !valueEquals(entry.getValue(), y.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (int i = 0; i < x.size(); i++) {
                if (!valueEquals(x.get(i), y.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(a, b);
    }
}
