package com.flowcortex.workflow.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The rating figures of one workflow: the mean to two decimals (null without reviews), the review
 * count, and the count per star value {@code "1"} to {@code "5"}, zero included.
 */
public record RatingSummary(BigDecimal average, long total, Map<String, Long> distribution) {

    /** Folds counts per star value; values outside 1-5 are ignored. */
    public static RatingSummary of(Map<Integer, Long> counts) {
        Map<String, Long> distribution = new LinkedHashMap<>();
        long total = 0;
        long weighted = 0;
        for (int star = 1; star <= 5; star++) {
            long count = counts.getOrDefault(star, 0L);
            distribution.put(String.valueOf(star), count);
            total += count;
            weighted += count * star;
        }
        BigDecimal average = total == 0
                ? null
                : BigDecimal.valueOf(weighted).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        return new RatingSummary(average, total, Collections.unmodifiableMap(distribution));
    }
}
