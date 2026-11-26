package com.flowcortex.user.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Pattern;

/** PKCE with the {@code S256} method only (RFC 7636). */
final class Pkce {

    private static final Pattern VERIFIER = Pattern.compile("[A-Za-z0-9\\-._~]{43,128}");
    private static final Pattern CHALLENGE = Pattern.compile("[A-Za-z0-9_-]{43,128}");

    private Pkce() {
    }

    static boolean validChallenge(String challenge) {
        return challenge != null && CHALLENGE.matcher(challenge).matches();
    }

    /** Whether the base64url SHA-256 of {@code verifier} equals {@code challenge}, compared in constant time. */
    static boolean matches(String verifier, String challenge) {
        if (verifier == null || challenge == null || !VERIFIER.matcher(verifier).matches()) {
            return false;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            String computed = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            return MessageDigest.isEqual(computed.getBytes(StandardCharsets.US_ASCII),
                    challenge.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
