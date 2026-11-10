package com.flowcortex.connector.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One member's connection to one provider. Read-only here: every change is one conditional
 * statement in {@code ConnectorAuthRepository}. The two token columns hold ciphertext;
 * {@code metadata} is JSON text of non-secret display data.
 */
@Entity
@Table(name = "connector_auth")
public class ConnectorAuth {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "provider", nullable = false, updatable = false)
    private String provider;

    @Column(name = "access_token", nullable = false)
    private String accessToken;

    @Column(name = "refresh_token")
    private String refreshToken;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "scopes", nullable = false)
    private List<String> scopes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false)
    private String metadata;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "usage_enabled", nullable = false)
    private boolean usageEnabled;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ConnectorAuth() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getProvider() {
        return provider;
    }

    /** Ciphertext. */
    public String getAccessToken() {
        return accessToken;
    }

    /** Ciphertext, or {@code null}. */
    public String getRefreshToken() {
        return refreshToken;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public List<String> getScopes() {
        return scopes;
    }

    public String getMetadata() {
        return metadata;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isUsageEnabled() {
        return usageEnabled;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
