package com.flowcortex.support.kb;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** The form text is searched in: lower case without Vietnamese diacritics ("Nạp CREDIT" → "nap credit"). */
public final class SearchText {

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final int MIN_TOKEN_LENGTH = 2;

    private SearchText() {
    }

    public static String normalize(String text) {
        String withoutD = text.replace('đ', 'd').replace('Đ', 'D');
        String stripped = COMBINING_MARKS.matcher(Normalizer.normalize(withoutD, Normalizer.Form.NFD)).replaceAll("");
        return stripped.toLowerCase(Locale.ROOT);
    }

    /** The distinct words of the normalized text that are long enough to search for, in order. */
    public static List<String> tokens(String text) {
        return Arrays.stream(NON_WORD.split(normalize(text)))
                .filter(token -> token.length() >= MIN_TOKEN_LENGTH)
                .distinct()
                .toList();
    }
}
