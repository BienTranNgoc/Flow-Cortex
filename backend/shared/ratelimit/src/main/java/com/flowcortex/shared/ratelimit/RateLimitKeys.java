package com.flowcortex.shared.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Stored keys and log identities. The digest covers the allowance, so a changed capacity or
 * period starts fresh budgets, and it keeps the raw identity (address, email) out of the table.
 */
public final class RateLimitKeys {

    private static final int LOG_IDENTITY_LENGTH = 12;

    private RateLimitKeys() {
    }

    /** {@code <limiter>:<64 lowercase hex>}. */
    public static String storageKey(RateLimitDefinition definition, String identity) {
        return definition.name() + ":" + digest(definition, identity);
    }

    /** The first 12 hex characters of the digest: enough to correlate refusals, useless to identify a caller. */
    public static String logIdentity(RateLimitDefinition definition, String identity) {
        return digest(definition, identity).substring(0, LOG_IDENTITY_LENGTH);
    }

    private static String digest(RateLimitDefinition definition, String identity) {
        String material = definition.name() + "|" + definition.capacity() + "|" + definition.period().toMillis()
                + "|" + identity;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", e);
        }
    }
}
