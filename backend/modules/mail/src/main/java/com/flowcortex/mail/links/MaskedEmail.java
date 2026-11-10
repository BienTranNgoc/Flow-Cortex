package com.flowcortex.mail.links;

/** Hides an address down to its first character and domain; a malformed value is never echoed. */
public final class MaskedEmail {

    private static final String UNKNOWN = "***";

    private MaskedEmail() {
    }

    public static String of(String email) {
        if (email == null) {
            return UNKNOWN;
        }
        int at = email.indexOf('@');
        if (at < 1 || at == email.length() - 1) {
            return UNKNOWN;
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
