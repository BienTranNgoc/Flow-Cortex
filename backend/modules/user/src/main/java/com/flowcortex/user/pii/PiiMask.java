package com.flowcortex.user.pii;

import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Masks a member's email and name for admin surfaces. Pure: counts code points, keeps null as null,
 * and never throws.
 */
public final class PiiMask {

    private static final Pattern WORD_SEPARATOR = Pattern.compile("[\\s\\p{Z}]+");
    private static final String NAME_TAIL = "***";

    /** Which mask a submitted value is compared against. */
    public enum Kind {
        EMAIL(PiiMask::email),
        NAME(PiiMask::name);

        private final UnaryOperator<String> mask;

        Kind(UnaryOperator<String> mask) {
            this.mask = mask;
        }
    }

    private PiiMask() {
    }

    /** First and last character of the local part stay, the rest become {@code *}; the domain stays. */
    public static String email(String value) {
        if (value == null) {
            return null;
        }
        int at = value.indexOf('@');
        String local = at < 0 ? value : value.substring(0, at);
        int length = local.codePointCount(0, local.length());
        if (length <= 2) {
            return value;
        }
        int firstEnd = local.offsetByCodePoints(0, 1);
        int lastStart = local.offsetByCodePoints(local.length(), -1);
        StringBuilder masked = new StringBuilder(value.length());
        masked.append(local, 0, firstEnd);
        masked.append("*".repeat(length - 2));
        masked.append(local, lastStart, local.length());
        if (at >= 0) {
            masked.append(value, at, value.length());
        }
        return masked.toString();
    }

    /** Each word's first character plus {@code ***}, joined by one space. */
    public static String name(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder masked = new StringBuilder();
        for (String word : WORD_SEPARATOR.split(value)) {
            if (word.isEmpty()) {
                continue;
            }
            if (!masked.isEmpty()) {
                masked.append(' ');
            }
            masked.appendCodePoint(word.codePointAt(0)).append(NAME_TAIL);
        }
        return masked.toString();
    }

    /** The email rule when the label holds {@code @}, else the name rule. */
    public static String label(String value) {
        if (value == null) {
            return null;
        }
        return value.indexOf('@') >= 0 ? email(value) : name(value);
    }

    /**
     * True when {@code submitted} is the mask of {@code current} and masking changed it, so a client
     * that echoes back what it was shown is told apart from one that sends a real value.
     */
    public static boolean isMaskOf(String submitted, String current, Kind kind) {
        if (submitted == null || current == null) {
            return false;
        }
        String masked = kind.mask.apply(current);
        return !masked.equals(current) && masked.equals(submitted);
    }
}
