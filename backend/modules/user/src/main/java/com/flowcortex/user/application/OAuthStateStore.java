package com.flowcortex.user.application;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * One-shot CSRF store for the Google OAuth {@code state} parameter. The
 * implementation is in-memory (single backend instance); cross-replica
 * sharing is out of scope for B3.
 */
public interface OAuthStateStore {

    String put(OAuthStateRecord record);

    Optional<OAuthStateRecord> consume(String state);

    final class Generator {

        private static final SecureRandom RANDOM = new SecureRandom();

        private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

        private Generator() {
        }

        public static String newState() {
            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            return URL_ENCODER.encodeToString(bytes);
        }
    }
}
