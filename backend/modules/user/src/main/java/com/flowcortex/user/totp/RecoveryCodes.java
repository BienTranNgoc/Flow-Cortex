package com.flowcortex.user.totp;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** One-time recovery codes: ten characters of the base32 alphabet shown as {@code XXXXX-XXXXX}. */
final class RecoveryCodes {

    static final int COUNT = 10;

    private static final int GROUP = 5;
    private static final Pattern SHAPE = Pattern.compile("[A-Z2-7]{" + (GROUP * 2) + "}");

    private RecoveryCodes() {
    }

    /** {@value #COUNT} distinct codes, formatted for display. */
    static List<String> generate(SecureRandom random) {
        Set<String> codes = new LinkedHashSet<>();
        while (codes.size() < COUNT) {
            StringBuilder code = new StringBuilder();
            for (int i = 0; i < GROUP * 2; i++) {
                if (i == GROUP) {
                    code.append('-');
                }
                code.append(TotpCodes.ALPHABET.charAt(random.nextInt(TotpCodes.ALPHABET.length())));
            }
            codes.add(code.toString());
        }
        return new ArrayList<>(codes);
    }

    /** The form that is hashed and compared: spaces and hyphens removed, upper-cased. */
    static String normalize(String code) {
        return code.replace(" ", "").replace("-", "").toUpperCase(Locale.ROOT);
    }

    static boolean looksLikeRecoveryCode(String code) {
        return code != null && SHAPE.matcher(normalize(code)).matches();
    }
}
