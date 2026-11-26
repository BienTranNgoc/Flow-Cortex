package com.flowcortex.entity.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** The handle of an entity: its format and its generation from a name. */
public final class Handles {

    public static final int MAX_LENGTH = 48;

    static final String FALLBACK = "thuc-the";

    private static final Pattern FORMAT = Pattern.compile("[a-z0-9-]{2,48}");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern OTHER_RUNS = Pattern.compile("[^a-z0-9]+");

    private Handles() {
    }

    public static boolean valid(String handle) {
        return handle != null && FORMAT.matcher(handle).matches();
    }

    /** A valid handle for the name that is not in {@code taken}: the base, then {@code -2}, {@code -3} and so on. */
    public static String generate(String name, Set<String> taken) {
        String base = base(name);
        if (!taken.contains(base)) {
            return base;
        }
        for (int number = 2;; number++) {
            String suffix = "-" + number;
            String candidate = trimTrailingHyphens(base.substring(0, Math.min(base.length(), MAX_LENGTH - suffix.length())))
                    + suffix;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
    }

    private static String base(String name) {
        String withoutBar = name == null ? "" : Normalizer.normalize(name, Normalizer.Form.NFC)
                .replace('đ', 'd').replace('Đ', 'D');
        String unmarked = COMBINING_MARKS.matcher(Normalizer.normalize(withoutBar, Normalizer.Form.NFD)).replaceAll("");
        String slug = OTHER_RUNS.matcher(unmarked.toLowerCase(Locale.ROOT)).replaceAll("-");
        String trimmed = trimHyphens(slug);
        if (trimmed.isEmpty()) {
            return FALLBACK;
        }
        String cut = trimTrailingHyphens(trimmed.substring(0, Math.min(trimmed.length(), MAX_LENGTH)));
        return cut.length() == 1 ? cut + "-1" : cut;
    }

    private static String trimHyphens(String text) {
        int start = 0;
        while (start < text.length() && text.charAt(start) == '-') {
            start++;
        }
        return trimTrailingHyphens(text.substring(start));
    }

    private static String trimTrailingHyphens(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '-') {
            end--;
        }
        return text.substring(0, end);
    }
}
