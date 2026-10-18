package com.flowcortex.user.application;

import java.net.URI;
import java.net.URISyntaxException;

/** Keeps a post-login redirect on this origin: only a plain absolute path is accepted, anything else becomes {@code /}. */
public final class SafeReturnTo {

    private static final String ROOT = "/";

    private static final int MAX_LENGTH = 512;

    private SafeReturnTo() {
    }

    public static String of(String candidate) {
        if (candidate == null || candidate.isEmpty() || candidate.length() > MAX_LENGTH
                || !candidate.startsWith("/") || candidate.startsWith("//")) {
            return ROOT;
        }
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (c == '\\' || c < 0x20 || c == 0x7f) {
                return ROOT;
            }
        }
        try {
            URI uri = new URI(candidate);
            return uri.getScheme() == null && uri.getRawAuthority() == null ? candidate : ROOT;
        } catch (URISyntaxException ex) {
            return ROOT;
        }
    }
}
