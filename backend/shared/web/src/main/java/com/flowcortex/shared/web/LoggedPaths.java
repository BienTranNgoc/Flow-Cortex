package com.flowcortex.shared.web;

import java.util.List;

/** Hides bearer-style path segments (share keys, email-link tokens) from log lines. */
public final class LoggedPaths {

    static final String REDACTED = "[redacted]";

    private static final List<String> SECRET_PREFIXES = List.of("/api/v1/workflows/by-key/", "/api/v1/email-links/");

    private LoggedPaths() {
    }

    /** The request path with the segment after a secret prefix replaced; any other path is returned as is. */
    public static String redact(String uri) {
        if (uri == null) {
            return null;
        }
        for (String prefix : SECRET_PREFIXES) {
            if (uri.startsWith(prefix)) {
                int end = uri.indexOf('/', prefix.length());
                return prefix + REDACTED + (end < 0 ? "" : uri.substring(end));
            }
        }
        return uri;
    }
}
