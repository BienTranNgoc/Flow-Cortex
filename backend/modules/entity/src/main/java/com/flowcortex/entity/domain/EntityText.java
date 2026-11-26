package com.flowcortex.entity.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** The text key search, tags, categories and label grouping compare by, and the label form kept beside it. */
public final class EntityText {

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("[\\s\\p{Z}]+");

    private EntityText() {
    }

    /** NFC, {@code đ}/{@code Đ} as {@code d}, marks removed, lower case, whitespace collapsed and trimmed; empty for {@code null}. */
    public static String key(String text) {
        if (text == null) {
            return "";
        }
        String withoutBar = Normalizer.normalize(text, Normalizer.Form.NFC).replace('đ', 'd').replace('Đ', 'D');
        String unmarked = COMBINING_MARKS.matcher(Normalizer.normalize(withoutBar, Normalizer.Form.NFD)).replaceAll("");
        return collapse(unmarked.toLowerCase(Locale.ROOT));
    }

    /** NFC, whitespace collapsed and trimmed; empty for {@code null}. */
    public static String label(String text) {
        return text == null ? "" : collapse(Normalizer.normalize(text, Normalizer.Form.NFC));
    }

    private static String collapse(String text) {
        return WHITESPACE_RUN.matcher(text).replaceAll(" ").strip();
    }
}
