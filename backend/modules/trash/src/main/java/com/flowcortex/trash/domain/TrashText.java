package com.flowcortex.trash.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Names as the Trash stores and searches them. */
public final class TrashText {

    /** Matches the {@code name} column check, counted in code points. */
    static final int MAX_NAME_CODE_POINTS = 200;

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("[\\s\\p{Z}]+");

    private TrashText() {
    }

    /** NFC, {@code đ} to {@code d}, diacritics removed, lower case, whitespace runs as one space, trimmed; "" for null. */
    public static String key(String text) {
        if (text == null) {
            return "";
        }
        String composed = Normalizer.normalize(text, Normalizer.Form.NFC).replace('đ', 'd').replace('Đ', 'D');
        String stripped = COMBINING_MARKS.matcher(Normalizer.normalize(composed, Normalizer.Form.NFD)).replaceAll("");
        return WHITESPACE_RUN.matcher(stripped.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    /** The name to store: NFC, trimmed, at most 200 code points; empty when null or blank (an untitled item). */
    public static Optional<String> displayName(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String name = Normalizer.normalize(text, Normalizer.Form.NFC).strip();
        if (name.codePointCount(0, name.length()) > MAX_NAME_CODE_POINTS) {
            name = name.substring(0, name.offsetByCodePoints(0, MAX_NAME_CODE_POINTS)).strip();
        }
        return Optional.of(name);
    }

    /** A {@code LIKE} pattern matching names whose key contains {@code key}; {@code %}, {@code _} and {@code \} are literal. */
    public static String containsPattern(String key) {
        return "%" + key.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
