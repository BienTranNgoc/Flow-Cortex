package com.flowcortex.user.totp;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Bound from {@code flowcortex.auth.totp.*}. {@code keys} is the keyring (id to 32 random bytes in
 * base64) and {@code currentKeyId} the entry new secrets are sealed under. Blank ring entries are
 * dropped so an empty environment variable reads as a missing key, not as a key that fails to load.
 * {@code devKey} is set only by the dev profile: when no usable key is configured it becomes the entry
 * {@code currentKeyId} names, so an empty variable never leaves a dev run without a key.
 */
@Validated
@ConfigurationProperties("flowcortex.auth.totp")
public record TotpProperties(
        @NotEmpty Map<String, String> keys,
        @NotBlank String currentKeyId,
        @DefaultValue("true") boolean adminRequired,
        @DefaultValue("10") @Min(3) @Max(100) int maxFailedAttempts,
        @DefaultValue("15m") @NotNull Duration lockout,
        @DefaultValue("5m") @NotNull Duration challengeTtl,
        @DefaultValue("5") @Min(1) int challengeMaxWrongCodes,
        @DefaultValue("Nodi") @NotBlank String issuer,
        @DefaultValue("") String devKey) {

    private static final Duration MIN_LOCKOUT = Duration.ofMinutes(1);
    private static final Duration MAX_LOCKOUT = Duration.ofMinutes(1440);

    public TotpProperties {
        keys = keys == null ? Map.of() : keys.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        if (keys.isEmpty() && devKey != null && !devKey.isBlank() && currentKeyId != null && !currentKeyId.isBlank()) {
            keys = Map.of(currentKeyId, devKey);
        }
        if (lockout != null && (lockout.compareTo(MIN_LOCKOUT) < 0 || lockout.compareTo(MAX_LOCKOUT) > 0)) {
            throw new IllegalArgumentException(
                    "flowcortex.auth.totp.lockout must be between 1 and 1440 minutes, was " + lockout);
        }
        if (challengeTtl != null && (challengeTtl.isZero() || challengeTtl.isNegative())) {
            throw new IllegalArgumentException(
                    "flowcortex.auth.totp.challenge-ttl must be positive, was " + challengeTtl);
        }
    }

    @AssertTrue(message = "current-key-id must name an entry of keys")
    boolean isCurrentKeyInTheRing() {
        return keys.isEmpty() || currentKeyId == null || keys.containsKey(currentKeyId);
    }
}
