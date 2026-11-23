package com.flowcortex.user.application;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Folds a search term the way the {@code app_user_search_fold} SQL function folds the stored text:
 * lower case, Vietnamese composed letters mapped to their base letter. The two constants must stay
 * identical to the {@code translate} arguments of that function.
 */
public final class SearchFold {

    static final String FROM = "àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ"
            + "ÀÁẠẢÃÂẦẤẬẨẪĂẰẮẶẲẴÈÉẸẺẼÊỀẾỆỂỄÌÍỊỈĨÒÓỌỎÕÔỒỐỘỔỖƠỜỚỢỞỠÙÚỤỦŨƯỪỨỰỬỮỲÝỴỶỸĐ";
    static final String TO = "aaaaaaaaaaaaaaaaaeeeeeeeeeeeiiiiiooooooooooooooooouuuuuuuuuuuyyyyyd"
            + "aaaaaaaaaaaaaaaaaeeeeeeeeeeeiiiiiooooooooooooooooouuuuuuuuuuuyyyyyd";

    private SearchFold() {
    }

    /** Trimmed, NFC-normalized and folded; empty for {@code null} or blank input. */
    public static String fold(String value) {
        if (value == null) {
            return "";
        }
        String lowered = Normalizer.normalize(value.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        StringBuilder folded = new StringBuilder(lowered.length());
        for (int i = 0; i < lowered.length(); i++) {
            char c = lowered.charAt(i);
            int at = FROM.indexOf(c);
            folded.append(at >= 0 ? TO.charAt(at) : c);
        }
        return folded.toString();
    }
}
