package com.flowcortex.shared.web;

import java.util.List;

/** Hides bearer-style path segments (share keys, email-link tokens) from log lines and telemetry. */
public final class LoggedPaths {

    static final String REDACTED = "[redacted]";

    private static final List<String> SECRET_PREFIXES = List.of("/api/v1/workflows/by-key/", "/api/v1/email-links/");
    private static final List<String> SCHEMES = List.of("http://", "https://");

    private LoggedPaths() {
    }

    /**
     * A request target, or the path of an absolute http(s) URL, with the segment after a secret prefix replaced;
     * anything else is returned as is.
     */
    public static String redact(String uri) {
        if (uri == null) {
            return null;
        }
        int pathStart = pathStart(uri);
        return pathStart < 0 ? uri : uri.substring(0, pathStart) + redactPath(uri.substring(pathStart));
    }

    /** Index where the path begins: 0 for a request target, after the authority for an absolute URL, else -1. */
    private static int pathStart(String uri) {
        if (uri.startsWith("/")) {
            return 0;
        }
        for (String scheme : SCHEMES) {
            if (uri.regionMatches(true, 0, scheme, 0, scheme.length())) {
                return uri.indexOf('/', scheme.length());
            }
        }
        return -1;
    }

    private static String redactPath(String path) {
        for (String prefix : SECRET_PREFIXES) {
            if (path.startsWith(prefix)) {
                int end = path.indexOf('/', prefix.length());
                return prefix + REDACTED + (end < 0 ? "" : path.substring(end));
            }
        }
        return path;
    }
}
