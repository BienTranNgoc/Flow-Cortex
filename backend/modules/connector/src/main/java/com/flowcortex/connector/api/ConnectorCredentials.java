package com.flowcortex.connector.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The per-member store of provider credentials. Every call is scoped to {@code userId}: a
 * connection that belongs to someone else answers exactly like one that does not exist (empty
 * or {@code false}). {@code provider} must match {@code [a-z][a-z0-9_-]{0,63}}, otherwise
 * {@link IllegalArgumentException}. The implementation never logs or returns a token except
 * through {@link #readTokens}.
 */
public interface ConnectorCredentials {

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
     * Stores new tokens on an active connection; a {@code null} {@code refreshToken} or {@code scopes}
     * keeps the stored value, and the usage flag is untouched. {@code false} when nothing changed.
     */
    boolean refreshTokens(UUID userId, String provider, String accessToken, String refreshToken, Instant expiresAt,
            List<String> scopes);

    /** Marks an active connection inactive and stamps the revocation time once; {@code false} when nothing changed. */
    boolean markRevoked(UUID userId, String provider);

    /** Sets (not toggles) the usage flag of an active connection; {@code false} when nothing changed. */
    boolean setUsageEnabled(UUID userId, String provider, boolean enabled);

    /** Deletes the connection; {@code false} when none existed. */
    boolean disconnect(UUID userId, String provider);
}
