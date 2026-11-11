package com.flowcortex.workflow.application;

import java.security.SecureRandom;
import java.util.Base64;

/** Mints share keys: 16 random bytes, URL-safe base64 without padding, 22 characters. */
final class ShareKeys {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private ShareKeys() {
    }

    static String mint() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }
}
