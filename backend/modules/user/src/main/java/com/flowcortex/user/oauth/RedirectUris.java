package com.flowcortex.user.oauth;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The redirect URI policy: https anywhere, plain http only for a loopback host. Matching is exact,
 * except that the port of a registered loopback http URI is ignored, because a native client picks a
 * free port per run (RFC 8252 section 7.3).
 */
final class RedirectUris {

    static final int MAX_URIS = 5;
    static final int MAX_LENGTH = 2000;

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private RedirectUris() {
    }

    /** The reason the list cannot be registered, or empty when every URI is acceptable. */
    static Optional<String> validateForRegistration(List<String> uris) {
        if (uris == null || uris.isEmpty()) {
            return Optional.of("redirect_uris must hold at least one URI");
        }
        if (uris.size() > MAX_URIS) {
            return Optional.of("redirect_uris holds at most " + MAX_URIS + " URIs");
        }
        for (String uri : uris) {
            Optional<String> problem = validateOne(uri);
            if (problem.isPresent()) {
                return problem;
            }
        }
        return Optional.empty();
    }

    /** Whether {@code requested} is the registered URI, or its loopback twin on another port. */
    static boolean matches(String registered, String requested) {
        if (registered == null || requested == null) {
            return false;
        }
        if (registered.equals(requested)) {
            return true;
        }
        URI registeredUri = parse(registered);
        URI requestedUri = parse(requested);
        if (registeredUri == null || requestedUri == null || !isLoopbackHttp(registeredUri)) {
            return false;
        }
        return isLoopbackHttp(requestedUri)
                && registeredUri.getHost().equalsIgnoreCase(requestedUri.getHost())
                && requestedUri.getRawUserInfo() == null
                && requestedUri.getRawFragment() == null
                && java.util.Objects.equals(registeredUri.getRawPath(), requestedUri.getRawPath())
                && java.util.Objects.equals(registeredUri.getRawQuery(), requestedUri.getRawQuery());
    }

    private static Optional<String> validateOne(String uri) {
        if (uri == null || uri.isBlank()) {
            return Optional.of("a redirect URI is empty");
        }
        if (uri.length() > MAX_LENGTH) {
            return Optional.of("a redirect URI is longer than " + MAX_LENGTH + " characters");
        }
        URI parsed = parse(uri);
        if (parsed == null || !parsed.isAbsolute() || parsed.getHost() == null) {
            return Optional.of("a redirect URI must be an absolute http(s) URI");
        }
        if (parsed.getRawFragment() != null) {
            return Optional.of("a redirect URI must not carry a fragment");
        }
        if (parsed.getRawUserInfo() != null) {
            return Optional.of("a redirect URI must not carry user information");
        }
        String scheme = parsed.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (scheme.equals("https") || isLoopbackHttp(parsed)) {
            return Optional.empty();
        }
        return Optional.of("a redirect URI must use https, or http on a loopback host");
    }

    private static boolean isLoopbackHttp(URI uri) {
        return uri.getScheme() != null && uri.getScheme().equalsIgnoreCase("http") && uri.getHost() != null
                && LOOPBACK_HOSTS.contains(uri.getHost().toLowerCase(java.util.Locale.ROOT));
    }

    private static URI parse(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
