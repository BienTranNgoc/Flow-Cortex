package com.flowcortex.user.oauth;

import com.flowcortex.user.application.OpaqueTokens;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.net.URLDecoder;

/**
 * Client authentication at the token and revocation endpoints (RFC 6749 section 2.3): an HTTP Basic
 * header wins over form fields, and a secret is compared by its hash in constant time.
 */
final class ClientCredentials {

    private static final String BASIC = "Basic ";

    private ClientCredentials() {
    }

    /** {@code secret} is null when none was sent; {@code viaBasic} chooses the {@code WWW-Authenticate} answer. */
    record Credentials(String clientId, String secret, boolean viaBasic) {
    }

    /**
     * Empty when no usable credentials were sent: no client id, or a Basic header that cannot be read
     * (which is not retried as a form, so a client cannot mix two identities).
     */
    static Optional<Credentials> from(String authorizationHeader, String formClientId, String formSecret) {
        if (authorizationHeader != null && authorizationHeader.regionMatches(true, 0, BASIC, 0, BASIC.length())) {
            return basic(authorizationHeader.substring(BASIC.length()).trim());
        }
        if (formClientId == null || formClientId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new Credentials(formClientId, blankToNull(formSecret), false));
    }

    /** Whether the credentials authenticate {@code client}: a public client sends no secret, any other must. */
    static boolean verify(OAuthClient client, Credentials credentials) {
        if (!client.clientId().equals(credentials.clientId())) {
            return false;
        }
        if (client.isPublic()) {
            return credentials.secret() == null;
        }
        if (credentials.secret() == null || client.secretHash() == null) {
            return false;
        }
        return MessageDigest.isEqual(OpaqueTokens.hash(credentials.secret()).getBytes(StandardCharsets.US_ASCII),
                client.secretHash().getBytes(StandardCharsets.US_ASCII));
    }

    private static Optional<Credentials> basic(String encoded) {
        try {
            String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon <= 0) {
                return Optional.empty();
            }
            String id = URLDecoder.decode(decoded.substring(0, colon), StandardCharsets.UTF_8);
            String secret = URLDecoder.decode(decoded.substring(colon + 1), StandardCharsets.UTF_8);
            return id.isBlank() ? Optional.empty() : Optional.of(new Credentials(id, blankToNull(secret), true));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
