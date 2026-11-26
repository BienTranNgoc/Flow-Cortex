package com.flowcortex.user.phone;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import tools.jackson.databind.JsonNode;

/** The pure decisions of the VietGuys token chain: when to renew, when to reseed, what an answer means. */
final class VietguysTokenPolicy {

    /** An access token with less than this left is renewed. */
    static final Duration RENEW_WITHIN = Duration.ofHours(2);

    /** Lifetime of a token whose expiry cannot be read; above {@link #RENEW_WITHIN} so it is reused, not renewed per send. */
    static final Duration FALLBACK_LIFETIME = RENEW_WITHIN.plus(Duration.ofHours(1));

    private static final Duration MAX_LIFETIME = Duration.ofDays(30);
    /** Below this an epoch value is seconds (year 5138 in seconds, 1973 in milliseconds). */
    private static final long SECONDS_CEILING = 100_000_000_000L;
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

    /**
     * {@code data.expired_at} of a renewal answer, as a number or numeric string in epoch milliseconds (seconds
     * are scaled); empty when absent, malformed, already past or more than 30 days ahead.
     */
    static Optional<Instant> readableExpiry(JsonNode answer, Instant now) {
        JsonNode expiredAt = answer == null ? null : answer.path("data").path("expired_at");
        OptionalLong epoch = epochOf(expiredAt);
        if (epoch.isEmpty() || epoch.getAsLong() <= 0) {
            return Optional.empty();
        }
        long millis = epoch.getAsLong() < SECONDS_CEILING ? epoch.getAsLong() * 1000 : epoch.getAsLong();
        Instant expiry = Instant.ofEpochMilli(millis);
        return expiry.isAfter(now) && !expiry.isAfter(now.plus(MAX_LIFETIME)) ? Optional.of(expiry) : Optional.empty();
    }

    /** The readable expiry, else {@link #FALLBACK_LIFETIME} from now, which is never already due. */
    static Instant expiryFrom(JsonNode answer, Instant now) {
        return readableExpiry(answer, now).orElse(now.plus(FALLBACK_LIFETIME));
    }

    /** An integral number, or a string holding one, as a long; anything else is empty. */
    private static OptionalLong epochOf(JsonNode value) {
        if (value == null) {
            return OptionalLong.empty();
        }
        if (value.isIntegralNumber()) {
            return value.canConvertToLong() ? OptionalLong.of(value.longValue()) : OptionalLong.empty();
        }
        if (value.isString()) {
            String text = value.stringValue().trim();
            if (text.matches("-?\\d{1,18}")) {
                return OptionalLong.of(Long.parseLong(text));
            }
        }
        return OptionalLong.empty();
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
