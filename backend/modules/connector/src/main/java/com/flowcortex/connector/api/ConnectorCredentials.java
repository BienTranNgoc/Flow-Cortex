package com.flowcortex.connector.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The per-member store of provider credentials. Every call is scoped to {@code userId}: a
 * connection that belongs to someone else answers exactly like one that does not exist (empty
 * or {@code false}). {@code provider} must match {@code [a-z][a-z0-9_-]{0,63}}, otherwise
 * {@link IllegalArgumentException}. The implementation never logs or returns a token except
 * through {@link #readTokens} and {@link #readActive}.
 */
public interface ConnectorCredentials {

    /** The slug every {@code provider} argument must match. */
    Pattern PROVIDER_PATTERN = Pattern.compile("[a-z][a-z0-9_-]{0,63}");

    /** Creates or replaces the connection: active, usage enabled, not revoked; scopes come from {@code tokens}. */
    void connect(UUID userId, String provider, ConnectorTokens tokens, Map<String, Object> metadata);

    List<ConnectorConnection> list(UUID userId, boolean includeInactive);

    Optional<ConnectorConnection> find(UUID userId, String provider);

    /**
     * Plaintext tokens of an active connection; empty when inactive or absent.
     *
     * @throws CredentialUnreadableException when the active connection's ciphertext fails authentication
     */
    Optional<ConnectorTokens> readTokens(UUID userId, String provider);

    /**
     * The connection view and a handle that decrypts the tokens of that same row, read in one transaction so the
     * two can never come from different saves; empty when inactive or absent. Nothing is decrypted here, so an
     * unreadable ciphertext surfaces only when the handle is opened.
     */
    Optional<ActiveCredential> readActive(UUID userId, String provider);

    /** A connection view together with a decrypt-later handle on the tokens of the same stored row. */
    record ActiveCredential(ConnectorConnection connection, SealedTokens tokens) {
    }

    /** Still-encrypted tokens of one stored row. */
    @FunctionalInterface
    interface SealedTokens {

        /**
         * Decrypts the tokens.
         *
         * @throws CredentialUnreadableException when the ciphertext fails authentication
         */
        ConnectorTokens open();
    }

    /**
     * Stores new tokens on an active connection; a {@code null} {@code refreshToken} or {@code scopes}
     * keeps the stored value, and the usage flag is untouched. {@code false} when nothing changed.
     */
    boolean refreshTokens(UUID userId, String provider, String accessToken, String refreshToken, Instant expiresAt,
            List<String> scopes);

    /** Marks an active connection inactive and stamps the revocation time once; {@code false} when nothing changed. */
    boolean markRevoked(UUID userId, String provider);

    /** Sets (not toggles) the usage flag of an active connection; {@code false} when nothing changed. */
    boolean setUsageEnabled(UUID userId, String provider, boolean enabled);

    /**
     * Merges {@code patch} into the metadata of an active connection at the top level: keys of the patch
     * replace the stored ones, every other key is kept, and the usage flag is untouched. When
     * {@code requires} is not {@code null}, the stored metadata must contain it (jsonb containment, so a
     * list matches when one of its elements contains the required element), otherwise nothing changes.
     * The metadata is readable by any caller of {@link #find}, so it must hold no secret.
     *
     * @return {@code false} when the connection is absent, inactive, not the member's, or {@code requires} failed
     */
    boolean mergeMetadata(UUID userId, String provider, Map<String, Object> patch, Map<String, Object> requires);

    /** Deletes the connection; {@code false} when none existed. */
    boolean disconnect(UUID userId, String provider);
}
