package com.flowcortex.user.phone;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.OptionalInt;
import tools.jackson.databind.JsonNode;

/** The pure decisions of the VietGuys token chain: when to renew, when to reseed, what an answer means. */
final class VietguysTokenPolicy {

    /** An access token with less than this left is renewed. */
    static final Duration RENEW_WITHIN = Duration.ofHours(2);

    private static final Duration FALLBACK_LIFETIME = Duration.ofHours(1);
    private static final int REVOKED_ERROR = -15;

    /** How a renewal answer reads. */
    enum Renewal {
        /** An access token and a refresh token to store. */
        RENEWED,
        /** The refresh token no longer belongs to a live chain; only a new seed helps. */
        REVOKED,
        /** Nothing usable; the stored chain stays and a later send tries again. */
        FAILED
    }

    private VietguysTokenPolicy() {
    }

    static boolean renewalDue(Instant accessExpiresAt, Instant now) {
        return accessExpiresAt == null || accessExpiresAt.isBefore(now.plus(RENEW_WITHIN));
    }

    static boolean seedWrites(String storedFingerprint, String seedFingerprint) {
        return storedFingerprint == null || !storedFingerprint.equals(seedFingerprint);
    }

    /** SHA-256 as 64 hex characters; the seed itself is never stored in the clear. */
    static String fingerprint(String seed) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    /** {@code data.expired_at} (epoch milliseconds) of a renewal answer; one hour from now when unreadable. */
    static Instant expiryFrom(JsonNode answer, Instant now) {
        JsonNode expiredAt = answer == null ? null : answer.path("data").path("expired_at");
        if (expiredAt != null && expiredAt.isIntegralNumber() && expiredAt.canConvertToLong()
                && expiredAt.longValue() > 0) {
            return Instant.ofEpochMilli(expiredAt.longValue());
        }
        return now.plus(FALLBACK_LIFETIME);
    }

    static Renewal renewalAccepted(JsonNode answer) {
        if (answer == null || !answer.isObject()) {
            return Renewal.FAILED;
        }
        OptionalInt error = VietguysAnswer.integerOf(answer.get("error"));
        if (error.isPresent() && error.getAsInt() == REVOKED_ERROR) {
            return Renewal.REVOKED;
        }
        if (error.isEmpty() || error.getAsInt() != 0) {
            return Renewal.FAILED;
        }
        JsonNode data = answer.path("data");
        return nonEmptyText(data.path("access_token")) && nonEmptyText(data.path("refresh_token"))
                ? Renewal.RENEWED
                : Renewal.FAILED;
    }

    private static boolean nonEmptyText(JsonNode value) {
        return value.isString() && !value.stringValue().isBlank();
    }
}
