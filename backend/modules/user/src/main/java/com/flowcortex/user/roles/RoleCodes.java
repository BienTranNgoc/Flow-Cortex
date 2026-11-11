package com.flowcortex.user.roles;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Derives a role code from a role name; every output matches {@code ^[A-Z][A-Z0-9_]{0,19}$}. */
public final class RoleCodes {

    /** The width of {@code role.code} and {@code app_user.role}. */
    public static final int MAX_LENGTH = 20;

    private static final String FALLBACK = "ROLE";
    /** The console routes {@code /roles/new} beside {@code /roles/:code}; a role coded NEW would be unreachable. */
    private static final Set<String> RESERVED = Set.of("NEW");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALNUM = Pattern.compile("[^A-Z0-9]+");

    private RoleCodes() {
    }

    public static String slug(String name) {
        String folded = (name == null ? "" : name).replace('đ', 'd').replace('Đ', 'D');
        folded = COMBINING_MARKS.matcher(Normalizer.normalize(folded, Normalizer.Form.NFD)).replaceAll("");
        String code = trimUnderscores(NON_ALNUM.matcher(folded.toUpperCase(Locale.ROOT)).replaceAll("_"));
        if (code.isEmpty()) {
            return FALLBACK;
        }
        if (Character.isDigit(code.charAt(0))) {
            code = "R_" + code;
        }
        return cut(code, MAX_LENGTH);
    }

    /** {@code base} cut so that {@code _n} fits in twenty characters, with no doubled underscore. */
    public static String withSuffix(String base, int n) {
        String suffix = "_" + n;
        return cut(base, MAX_LENGTH - suffix.length()) + suffix;
    }

    public static boolean isReserved(String code) {
        return RESERVED.contains(code);
    }

    private static String cut(String code, int max) {
        String cut = code.length() > max ? code.substring(0, max) : code;
        return trimTrailingUnderscores(cut);
    }

    private static String trimUnderscores(String value) {
        int start = 0;
        while (start < value.length() && value.charAt(start) == '_') {
            start++;
        }
        return trimTrailingUnderscores(value.substring(start));
    }

    private static String trimTrailingUnderscores(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '_') {
            end--;
        }
        return value.substring(0, end);
    }
}
