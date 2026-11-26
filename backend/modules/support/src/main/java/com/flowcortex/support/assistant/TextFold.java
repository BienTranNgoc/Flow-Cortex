package com.flowcortex.support.assistant;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** The accent fold the assistant's text rules share: lower-case, no combining marks, "đ" as "d". */
final class TextFold {

    private static final Pattern COMBINING_MARKS = Pattern.compile("[\\u0300-\\u036f]");

    private TextFold() {
    }

    static String fold(String text) {
        if (text == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        return COMBINING_MARKS.matcher(decomposed).replaceAll("")
                .toLowerCase(Locale.ROOT)
                .replace('đ', 'd');
    }

    /** Lower-case in composed form, so a term typed in either Unicode form is found as a substring. */
    static String lower(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }
}
