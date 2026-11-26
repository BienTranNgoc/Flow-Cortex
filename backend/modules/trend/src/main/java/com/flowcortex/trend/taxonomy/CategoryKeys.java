package com.flowcortex.trend.taxonomy;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** The category key derived once from the Vietnamese name, and the candidates tried when it is taken. */
final class CategoryKeys {

    static final int MAX_LENGTH = 64;
    static final int LAST_SUFFIX = 20;

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");

    private CategoryKeys() {
    }

    /** Empty when the name holds no letter or digit. */
    static Optional<String> keyOf(String nameVi) {
        String folded = MARKS.matcher(Normalizer.normalize(nameVi.toLowerCase(Locale.ROOT), Normalizer.Form.NFD))
                .replaceAll("")
                .replace('đ', 'd');
        String joined = stripEdgeHyphens(NON_ALPHANUMERIC.matcher(folded).replaceAll("-"));
        if (joined.length() > MAX_LENGTH) {
            joined = joined.charAt(MAX_LENGTH) == '-' ? joined.substring(0, MAX_LENGTH) : cutOnHyphen(joined);
        }
        String key = stripEdgeHyphens(joined);
        return key.isEmpty() ? Optional.empty() : Optional.of(key);
    }

    /** {@code base}, then {@code base-2} to {@code base-20}, each within {@value #MAX_LENGTH} characters. */
    static List<String> candidates(String base) {
        List<String> candidates = new ArrayList<>();
        candidates.add(base);
        for (int n = 2; n <= LAST_SUFFIX; n++) {
            String suffix = "-" + n;
            String head = base.substring(0, Math.min(base.length(), MAX_LENGTH - suffix.length()));
            candidates.add(stripEdgeHyphens(head) + suffix);
        }
        return candidates;
    }

    private static String cutOnHyphen(String joined) {
        int lastHyphen = joined.lastIndexOf('-', MAX_LENGTH - 1);
        return joined.substring(0, lastHyphen > 0 ? lastHyphen : MAX_LENGTH);
    }

    private static String stripEdgeHyphens(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && text.charAt(start) == '-') {
            start++;
        }
        while (end > start && text.charAt(end - 1) == '-') {
            end--;
        }
        return text.substring(start, end);
    }
}
