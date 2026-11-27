package com.flowcortex.workflow.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The edit percentage of a text edit. A full Levenshtein over 100,000 characters per side is 10^10 cells, so the
 * common prefix and suffix are cut first (edits are usually local) and the matrix is computed only when both
 * remaining middles are small; beyond that the longer middle's length is an upper bound.
 */
final class EditDistance {

    /** The longest middle, in characters, that is measured exactly (25 million cells at most). */
    static final int EXACT_LIMIT = 5_000;

    private EditDistance() {
    }

    static int distance(String a, String b) {
        int limit = Math.min(a.length(), b.length());
        int prefix = 0;
        while (prefix < limit && a.charAt(prefix) == b.charAt(prefix)) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < limit - prefix && a.charAt(a.length() - 1 - suffix) == b.charAt(b.length() - 1 - suffix)) {
            suffix++;
        }
        int la = a.length() - prefix - suffix;
        int lb = b.length() - prefix - suffix;
        if (la == 0 || lb == 0 || la > EXACT_LIMIT || lb > EXACT_LIMIT) {
            return Math.max(la, lb);
        }
        return levenshtein(a.substring(prefix, prefix + la), b.substring(prefix, prefix + lb));
    }

    /** Distance over the longer length times 100, two decimals, half up; zero when both texts are empty. */
    static BigDecimal percent(String original, String edited) {
        int longer = Math.max(original.length(), edited.length());
        if (longer == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return BigDecimal.valueOf(distance(original, edited)).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(longer), 2, RoundingMode.HALF_UP);
    }

    private static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
