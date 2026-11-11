package com.flowcortex.connector.application.http;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The structural comparison that keeps an HTTP connector call on the origin and path its member saved.
 * A string prefix check accepts {@code api.example.com.evil.com} and {@code api.example.com@evil.com};
 * this compares the parsed scheme, host, effective port, user info and path segments instead.
 */
final class BaseUrlPin {

    private static final int HTTPS_PORT = 443;
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private BaseUrlPin() {
    }

    /**
     * Validates a base URL typed by a member and returns its stored form (host lower-cased, default port
     * and trailing slashes dropped), or empty when it is not a plain https origin and path with a public
     * dotted host name. Whether a dotted name resolves to something internal is the egress proxy's call.
     */
    static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        URI uri = parse(raw.trim());
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawAuthority() == null) {
            return Optional.empty();
        }
        String host = uri.getHost();
        if (host == null || !publicHostName(host.toLowerCase(Locale.ROOT)) || uri.getPort() == 0) {
            return Optional.empty();
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (hasDotSegment(path)) {
            return Optional.empty();
        }
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') {
            end--;
        }
        int port = uri.getPort() == HTTPS_PORT ? -1 : uri.getPort();
        return Optional.of("https://" + host.toLowerCase(Locale.ROOT) + (port == -1 ? "" : ":" + port)
                + path.substring(0, end));
    }

    /** Whether {@code target} stays inside {@code baseUrl}, a value produced by {@link #normalize}. */
    static boolean allows(String baseUrl, String target) {
        URI base = parse(baseUrl);
        URI uri = target == null ? null : parse(target.trim());
        if (base == null || uri == null || uri.getScheme() == null || uri.getHost() == null
                || !uri.getScheme().equalsIgnoreCase(base.getScheme())
                || !uri.getHost().equalsIgnoreCase(base.getHost())
                || effectivePort(uri) != effectivePort(base)
                || uri.getRawUserInfo() != null) {
            return false;
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String basePath = base.getRawPath() == null ? "" : base.getRawPath();
        if (hasDotSegment(path)) {
            return false;
        }
        return basePath.isEmpty() || path.equals(basePath) || path.startsWith(basePath + "/");
    }

    private static URI parse(String text) {
        try {
            return new URI(text);
        } catch (URISyntaxException ex) {
            return null;
        }
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() == -1 ? HTTPS_PORT : uri.getPort();
    }

    private static boolean publicHostName(String host) {
        return host.indexOf('.') > 0 && !host.startsWith("[") && !IPV4.matcher(host).matches()
                && !host.equals("localhost") && !host.endsWith(".localhost");
    }

    /**
     * A segment an upstream may resolve as a traversal: {@code .} or {@code ..} (literal, percent-encoded or
     * followed by a {@code ;} path parameter), or any segment hiding an encoded slash or backslash.
     * Refused rather than normalised because the backend cannot know how the upstream reads the path.
     */
    private static boolean hasDotSegment(String rawPath) {
        for (String segment : rawPath.split("/", -1)) {
            String lower = segment.toLowerCase(Locale.ROOT);
            if (lower.contains("%2f") || lower.contains("%5c")) {
                return true;
            }
            int param = segment.indexOf(';');
            String decoded = decode(param < 0 ? segment : segment.substring(0, param));
            if (decoded.equals(".") || decoded.equals("..")) {
                return true;
            }
        }
        return false;
    }

    private static String decode(String segment) {
        try {
            return URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return segment;
        }
    }
}
